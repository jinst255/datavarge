package app.datavarge.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import app.datavarge.Config
import app.datavarge.util.DebugLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class LocMode { MOVING, STILL }

/** Fused Location Provider when Play Services is present, plain LocationManager otherwise. */
interface LocationSource {
    fun start(mode: LocMode, onFix: (Location) -> Unit)
    fun stop()
    suspend fun current(highAccuracy: Boolean, timeoutMs: Long): Location?
    suspend fun last(): Location?

    companion object {
        fun create(c: Context): LocationSource {
            val ok = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS
            DebugLog.i("Location source: ${if (ok) "fused" else "LocationManager"}")
            return if (ok) FusedSource(c) else ManagerSource(c)
        }
    }
}

@SuppressLint("MissingPermission")
private class FusedSource(c: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(c)
    private var cb: LocationCallback? = null

    override fun start(mode: LocMode, onFix: (Location) -> Unit) {
        stop()
        val req = when (mode) {
            LocMode.MOVING -> LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Config.MOVING_INTERVAL_MS)
                .setMinUpdateIntervalMillis(Config.MOVING_FASTEST_INTERVAL_MS)
            LocMode.STILL -> LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, Config.STILL_INTERVAL_MS)
                .setMinUpdateIntervalMillis(Config.STILL_FASTEST_INTERVAL_MS)
        }.setWaitForAccurateLocation(false).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.lastLocation?.let(onFix) }
        }
        cb = callback
        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    override fun stop() {
        cb?.let { client.removeLocationUpdates(it) }
        cb = null
    }

    override suspend fun current(highAccuracy: Boolean, timeoutMs: Long): Location? {
        val cts = CancellationTokenSource()
        val req = CurrentLocationRequest.Builder()
            .setPriority(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(if (highAccuracy) 5_000 else 60_000)
            .build()
        return try {
            withTimeoutOrNull(timeoutMs + 2_000) { client.getCurrentLocation(req, cts.token).await() }
        } catch (e: Exception) {
            DebugLog.w("getCurrentLocation failed", e); null
        } finally {
            cts.cancel()
        }
    }

    override suspend fun last(): Location? = runCatching { client.lastLocation.await() }.getOrNull()
}

@SuppressLint("MissingPermission")
private class ManagerSource(private val c: Context) : LocationSource {
    private val lm = c.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    override fun start(mode: LocMode, onFix: (Location) -> Unit) {
        stop()
        val l = LocationListener { onFix(it) }
        listener = l
        val provider = if (mode == LocMode.MOVING) LocationManager.GPS_PROVIDER else bestCoarse()
        val interval = if (mode == LocMode.MOVING) Config.MOVING_INTERVAL_MS else Config.STILL_INTERVAL_MS
        runCatching { lm.requestLocationUpdates(provider, interval, 0f, l, Looper.getMainLooper()) }
            .onFailure { DebugLog.e("requestLocationUpdates($provider) failed", it) }
    }

    private fun bestCoarse(): String =
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
        else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
        else LocationManager.GPS_PROVIDER

    override fun stop() {
        listener?.let { lm.removeUpdates(it) }
        listener = null
    }

    override suspend fun current(highAccuracy: Boolean, timeoutMs: Long): Location? {
        val provider = if (highAccuracy) LocationManager.GPS_PROVIDER else bestCoarse()
        val signal = CancellationSignal()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, c.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        }
    }

    override suspend fun last(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
}
