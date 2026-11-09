package app.datavarge.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import app.datavarge.analysis.Quality
import app.datavarge.data.ServiceStates
import app.datavarge.util.DebugLog
import app.datavarge.util.Perms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Everything the radio told us, at one moment. */
data class SignalSnapshot(
    val tsMs: Long = 0,
    val dbm: Int? = null,
    val rsrq: Int? = null,
    val snr: Int? = null,
    val level: Int = 0,
    val networkType: String = "none",
    val band: String? = null,
    val pci: Int? = null,
    val cellId: Long? = null,
    val serviceState: String = ServiceStates.UNKNOWN,
    val isRoaming: Boolean = false,
    val wifiConnected: Boolean = false,
    val simOperatorName: String? = null,
    val mccMnc: String? = null,
) {
    val quality: Quality get() = if (ServiceStates.isNoService(serviceState)) Quality.NONE else Quality.of(dbm)

    /** "4G LTE", "5G (NSA)", ... for display. */
    val networkLabel: String
        get() = when (networkType) {
            "LTE" -> "4G LTE"
            "NR_NSA" -> "5G (NSA)"
            "NR_SA" -> "5G (SA)"
            "UMTS" -> "3G"
            "GSM" -> "2G"
            else -> "No network"
        }
}

/**
 * Keeps the latest signal and cell info in memory, fed by TelephonyCallback (no polling).
 * Process-wide singleton: the logging service and the open UI share it.
 */
@SuppressLint("MissingPermission")
class SignalMonitor private constructor(private val ctx: Context) {
    private val thread = HandlerThread("signal").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var tm: TelephonyManager = ctx.getSystemService(TelephonyManager::class.java)
    private var callback: Callback? = null
    private val users = mutableSetOf<String>()

    @Volatile private var signalStrength: SignalStrength? = null
    @Volatile private var serviceState: ServiceState? = null
    @Volatile private var cellInfo: List<CellInfo> = emptyList()
    @Volatile private var displayInfo: TelephonyDisplayInfo? = null

    private val _live = MutableStateFlow(SignalSnapshot())
    val live: StateFlow<SignalSnapshot> = _live

    /** Called with (old, new) service state strings whenever it changes. */
    @Volatile var onServiceStateChange: ((String, String) -> Unit)? = null

    private inner class Callback : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.CellInfoListener,
        TelephonyCallback.DisplayInfoListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) { signalStrength = s; publish() }
        override fun onServiceStateChanged(s: ServiceState) {
            val old = serviceState?.let { mapState(it) } ?: ServiceStates.UNKNOWN
            serviceState = s
            publish()
            val new = mapState(s)
            if (old != new) onServiceStateChange?.invoke(old, new)
        }
        override fun onCellInfoChanged(c: MutableList<CellInfo>) { cellInfo = c.toList(); publish() }
        override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) { displayInfo = d; publish() }
    }

    /** Start listening on behalf of [who]; listening continues until every user has stopped. */
    @Synchronized
    fun start(who: String) {
        users += who
        if (callback != null || !Perms.canLog(ctx)) return
        tm = ctx.getSystemService(TelephonyManager::class.java) // follows the current default subscription
        val cb = Callback()
        try {
            tm.registerTelephonyCallback(executor, cb)
            callback = cb
            DebugLog.i("SignalMonitor registered (users=$users)")
        } catch (e: Exception) {
            DebugLog.e("registerTelephonyCallback failed", e)
        }
        handler.post { signalStrength = tm.signalStrength; publish() }
    }

    @Synchronized
    fun stop(who: String) {
        users -= who
        if (users.isEmpty()) unregister()
    }

    @Synchronized
    private fun unregister() {
        callback?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
        callback = null
    }

    /** SIM changed: the default subscription may be different now, so re-register. */
    @Synchronized
    fun restart() {
        unregister()
        signalStrength = null; serviceState = null; cellInfo = emptyList(); displayInfo = null
        if (users.isNotEmpty()) {
            val u = users.toSet(); users.clear(); u.forEach { start(it) }
        }
    }

    /**
     * A fresh snapshot for logging. Asks the modem for an up-to-date cell list (the platform may
     * rate-limit this and hand back recent data), falling back to the cached callback values.
     */
    suspend fun snapshot(): SignalSnapshot {
        if (Perms.canLog(ctx)) {
            val fresh = withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine { cont ->
                    try {
                        tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(c: MutableList<CellInfo>) { if (cont.isActive) cont.resume(c.toList()) }
                            override fun onError(errorCode: Int, detail: Throwable?) { if (cont.isActive) cont.resume(null) }
                        })
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (!fresh.isNullOrEmpty()) cellInfo = fresh
            runCatching { tm.signalStrength }.getOrNull()?.let { signalStrength = it }
            if (serviceState == null) serviceState = runCatching { tm.serviceState }.getOrNull()
        }
        return build().also { _live.value = it }
    }

    private fun publish() { _live.value = build() }

    private fun mapState(s: ServiceState): String = when (s.state) {
        ServiceState.STATE_IN_SERVICE -> ServiceStates.IN_SERVICE
        ServiceState.STATE_OUT_OF_SERVICE -> ServiceStates.OUT_OF_SERVICE
        ServiceState.STATE_EMERGENCY_ONLY -> ServiceStates.EMERGENCY_ONLY
        ServiceState.STATE_POWER_OFF -> ServiceStates.POWER_OFF
        else -> ServiceStates.UNKNOWN
    }

    private fun networkTypeName(): String {
        val d = displayInfo
        if (d != null) {
            when (d.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> return "NR_NSA"
            }
        }
        val raw = d?.networkType?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
            ?: runCatching { if (Perms.phoneState(ctx)) tm.dataNetworkType else TelephonyManager.NETWORK_TYPE_UNKNOWN }.getOrDefault(0)
                .takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
            ?: runCatching { if (Perms.phoneState(ctx)) tm.voiceNetworkType else 0 }.getOrDefault(0)
        return when (raw) {
            TelephonyManager.NETWORK_TYPE_NR -> "NR_SA"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> if (raw == TelephonyManager.NETWORK_TYPE_LTE) "LTE" else fromSignal()
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "UMTS"
            TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "GSM"
            else -> fromSignal()
        }
    }

    /** When the type is unknown (e.g. mobile data off), infer it from which signal is present. */
    private fun fromSignal(): String {
        val list = signalStrength?.cellSignalStrengths.orEmpty()
        return when {
            list.any { it is CellSignalStrengthNr } && list.any { it is CellSignalStrengthLte } -> "NR_NSA"
            list.any { it is CellSignalStrengthNr } -> "NR_SA"
            list.any { it is CellSignalStrengthLte } -> "LTE"
            list.any { it is CellSignalStrengthWcdma } -> "UMTS"
            list.any { it is CellSignalStrengthGsm } -> "GSM"
            else -> "none"
        }
    }

    private fun valid(v: Int): Int? = v.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }

    private fun build(): SignalSnapshot {
        val ss = serviceState
        val state = ss?.let(::mapState) ?: ServiceStates.UNKNOWN
        val netType = networkTypeName()

        // Registered (serving) cells from the latest cell list.
        val registered = cellInfo.filter { it.isRegistered }
        val nrCell = registered.filterIsInstance<CellInfoNr>().firstOrNull()
        val lteCell = registered.filterIsInstance<CellInfoLte>().firstOrNull()
        val wcdmaCell = registered.filterIsInstance<CellInfoWcdma>().firstOrNull()
        val gsmCell = registered.filterIsInstance<CellInfoGsm>().firstOrNull()

        val strengths: List<CellSignalStrength> = signalStrength?.cellSignalStrengths.orEmpty()
        val nrS = (nrCell?.cellSignalStrength as? CellSignalStrengthNr)?.takeIf { valid(it.ssRsrp) != null }
            ?: strengths.filterIsInstance<CellSignalStrengthNr>().firstOrNull { valid(it.ssRsrp) != null }
        val lteS = lteCell?.cellSignalStrength?.takeIf { valid(it.rsrp) != null }
            ?: strengths.filterIsInstance<CellSignalStrengthLte>().firstOrNull { valid(it.rsrp) != null }
        val wcdmaS = wcdmaCell?.cellSignalStrength ?: strengths.filterIsInstance<CellSignalStrengthWcdma>().firstOrNull()
        val gsmS = gsmCell?.cellSignalStrength ?: strengths.filterIsInstance<CellSignalStrengthGsm>().firstOrNull()

        var dbm: Int? = null; var rsrq: Int? = null; var snr: Int? = null
        val on5g = netType == "NR_NSA" || netType == "NR_SA"
        when {
            on5g && nrS != null -> { dbm = valid(nrS.ssRsrp); rsrq = valid(nrS.ssRsrq); snr = valid(nrS.ssSinr) }
            lteS != null -> { dbm = valid(lteS.rsrp); rsrq = valid(lteS.rsrq); snr = valid(lteS.rssnr) }
            nrS != null -> { dbm = valid(nrS.ssRsrp); rsrq = valid(nrS.ssRsrq); snr = valid(nrS.ssSinr) }
            wcdmaS != null -> dbm = valid(wcdmaS.dbm)
            gsmS != null -> dbm = valid(gsmS.dbm)
        }

        var band: String? = null; var pci: Int? = null; var cid: Long? = null
        if (on5g && nrCell != null) {
            val id = nrCell.cellIdentity as CellIdentityNr
            band = id.bands.firstOrNull()?.let { "n$it" } ?: valid(id.nrarfcn)?.let { "NR-ARFCN $it" }
            pci = valid(id.pci); cid = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG && it != Long.MAX_VALUE }
        }
        if (band == null && lteCell != null) {
            val id: CellIdentityLte = lteCell.cellIdentity
            band = id.bands.firstOrNull()?.let { "B$it" } ?: valid(id.earfcn)?.let { "EARFCN $it" }
            pci = valid(id.pci); cid = valid(id.ci)?.toLong()
        }
        if (cid == null) {
            wcdmaCell?.cellIdentity?.let { cid = valid(it.cid)?.toLong(); band = band ?: valid(it.uarfcn)?.let { u -> "UARFCN $u" } }
            gsmCell?.cellIdentity?.let { cid = cid ?: valid(it.cid)?.toLong() }
        }

        val noService = ServiceStates.isNoService(state) || state == ServiceStates.POWER_OFF
        return SignalSnapshot(
            tsMs = System.currentTimeMillis(),
            dbm = if (noService) null else dbm,
            rsrq = if (noService) null else rsrq,
            snr = if (noService) null else snr,
            level = if (noService) 0 else signalStrength?.level ?: 0,
            networkType = if (noService) "none" else netType,
            band = band, pci = pci, cellId = cid,
            serviceState = state,
            isRoaming = ss?.roaming ?: false,
            wifiConnected = wifiConnected(),
            simOperatorName = runCatching { tm.simOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            mccMnc = runCatching { tm.simOperator }.getOrNull()?.takeIf { it.isNotBlank() },
        )
    }

    private fun wifiConnected(): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    companion object {
        @Volatile private var inst: SignalMonitor? = null
        fun get(c: Context): SignalMonitor = inst ?: synchronized(this) {
            inst ?: SignalMonitor(c.applicationContext).also { inst = it }
        }
    }
}
