package app.datavarge.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import app.datavarge.Config
import app.datavarge.analysis.CapDecision
import app.datavarge.analysis.CapPoint
import app.datavarge.analysis.DensityCap
import app.datavarge.analysis.Geo
import app.datavarge.analysis.Quality
import app.datavarge.data.AppDatabase
import app.datavarge.data.Sample
import app.datavarge.data.ServiceStates
import app.datavarge.data.SpeedTest
import app.datavarge.data.Trigger
import app.datavarge.speedtest.SpeedPhase
import app.datavarge.speedtest.SpeedResult
import app.datavarge.speedtest.SpeedTester
import app.datavarge.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AutoOutcome { LOGGED, SKIP_ACCURACY, SKIP_CAP, SKIP_RADIO_OFF, SKIP_NO_SIM, ERROR }

data class ManualResult(
    val sampleId: Long?,
    val dbm: Int?,
    val quality: Quality,
    val networkLabel: String,
    val accuracyM: Float?,
    val approximate: Boolean,
    val speed: SpeedResult?,
    val error: String?,
)

sealed interface TestState {
    data object Idle : TestState
    data class Running(val step: String) : TestState
    data class Done(val result: ManualResult) : TestState
}

/** Builds and stores samples. Shared by the logging service (automatic) and Test now (manual). */
object Recorder {
    private lateinit var ctx: Context
    private val autoLock = Mutex()
    private val manualLock = Mutex()
    private var locSource: LocationSource? = null

    @Volatile private var pressureHpa: Float? = null
    var hasBarometer = false
        private set

    /** App-wide scope so a running Test now survives switching tabs or closing the screen. */
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _test

    fun init(c: Context) {
        ctx = c.applicationContext
        val sm = ctx.getSystemService(SensorManager::class.java)
        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = baro != null
        if (baro != null) {
            // Batched, low rate: we only need a recent value at sample time.
            sm.registerListener(object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) { pressureHpa = e.values[0] }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }, baro, 5_000_000, 30_000_000)
        }
    }

    private fun loc(): LocationSource = locSource ?: LocationSource.create(ctx).also { locSource = it }

    private val db get() = AppDatabase.get(ctx)

    private suspend fun mslAltitude(l: Location): Pair<Double?, Float?> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 34) {
            if (!l.hasMslAltitude() && l.hasAltitude()) {
                runCatching { AltitudeConverter().addMslAltitudeToLocation(ctx, l) }
            }
            if (l.hasMslAltitude()) {
                return@withContext l.mslAltitudeMeters to (if (l.hasMslAltitudeAccuracy()) l.mslAltitudeAccuracyMeters else null)
            }
        }
        // Fallback: WGS84 ellipsoid height (differs from sea level by up to ~100 m; informational only).
        (if (l.hasAltitude()) l.altitude else null) to (if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null)
    }

    private suspend fun build(l: Location, trigger: String, snap: SignalSnapshot, note: String?): Sample {
        val sim = SimTracker.get(ctx).current.value
        val (alt, altAcc) = mslAltitude(l)
        return Sample(
            tsUtcMs = System.currentTimeMillis(),
            lat = l.latitude, lon = l.longitude,
            locAccuracyM = if (l.hasAccuracy()) l.accuracy else null,
            altitudeM = alt, altitudeAccuracyM = altAcc,
            pressureHpa = pressureHpa,
            speedMps = if (l.hasSpeed()) l.speed else null,
            carrierLabel = sim.label, simKey = sim.key,
            simOperatorName = snap.simOperatorName ?: sim.operatorName,
            mccMnc = snap.mccMnc ?: sim.mccMnc,
            networkType = snap.networkType,
            signalDbm = snap.dbm, rsrq = snap.rsrq, snr = snap.snr, level = snap.level,
            band = snap.band, pci = snap.pci, cellId = snap.cellId,
            serviceState = snap.serviceState,
            isRoaming = snap.isRoaming, wifiConnected = snap.wifiConnected,
            trigger = trigger, note = note,
        )
    }

    /** Would the density cap let an automatic sample through at this spot right now? */
    suspend fun capAllows(lat: Double, lon: Double): CapDecision {
        val label = SimTracker.get(ctx).current.value.label
        val dLat = Geo.latSpan(Config.CAP_RADIUS_M * 1.2)
        val dLon = Geo.lonSpan(Config.CAP_RADIUS_M * 1.2, lat)
        val near = db.samples().autoInBox(label, lat - dLat, lat + dLat, lon - dLon, lon + dLon)
            .map { CapPoint(it.lat, it.lon, it.tsUtcMs, it.carrierLabel, it.trigger == Trigger.MANUAL, it.pressureHpa) }
        return DensityCap.decide(lat, lon, System.currentTimeMillis(), label, false, near, pressureHpa)
    }

    suspend fun recordAuto(l: Location, trigger: String): AutoOutcome = autoLock.withLock {
        try {
            if (!l.hasAccuracy() || l.accuracy > Config.MAX_ACCURACY_LOG_M) return AutoOutcome.SKIP_ACCURACY
            val sim = SimTracker.get(ctx).current.value
            if (!sim.present) return AutoOutcome.SKIP_NO_SIM
            if (capAllows(l.latitude, l.longitude) != CapDecision.LOG) return AutoOutcome.SKIP_CAP
            val snap = SignalMonitor.get(ctx).snapshot()
            if (snap.serviceState == ServiceStates.POWER_OFF) return AutoOutcome.SKIP_RADIO_OFF
            val s = build(l, trigger, snap, null)
            db.samples().insert(s)
            DebugLog.i("Logged $trigger ${s.carrierLabel} ${s.networkType} ${s.signalDbm} dBm acc=${s.locAccuracyM}")
            AutoOutcome.LOGGED
        } catch (e: Exception) {
            DebugLog.e("Couldn't save this point", e)
            AutoOutcome.ERROR
        }
    }

    fun startManual(withSpeed: Boolean, lastKnown: Location? = null, onDone: (ManualResult) -> Unit = {}) {
        appScope.launch { onDone(runManual(withSpeed, lastKnown)) }
    }

    /** Test now: fresh fix, signal snapshot, MANUAL sample (no cap, no accuracy filter), optional speed test. */
    suspend fun runManual(withSpeed: Boolean, lastKnown: Location? = null): ManualResult {
        if (!manualLock.tryLock()) return (testState.value as? TestState.Done)?.result
            ?: ManualResult(null, null, Quality.NONE, "", null, false, null, "A test is already running.")
        try {
            _test.value = TestState.Running("Getting a precise location…")
            val monitor = SignalMonitor.get(ctx)
            monitor.start("manual")
            val fix = loc().current(highAccuracy = true, timeoutMs = Config.MANUAL_FIX_TIMEOUT_MS)
                ?: lastKnown ?: loc().last()
            if (fix == null) {
                return done(ManualResult(null, null, Quality.NONE, "", null, true, null, "No location available yet. Try again outdoors."))
            }
            _test.value = TestState.Running("Reading signal…")
            val snap = monitor.snapshot()
            if (snap.serviceState == ServiceStates.POWER_OFF) {
                return done(ManualResult(null, null, Quality.NONE, snap.networkLabel, fix.accuracy, false, null, "Airplane mode is on, so there is no signal to measure."))
            }
            val approximate = !fix.hasAccuracy() || fix.accuracy > Config.MAX_ACCURACY_MAP_M
            val sample = build(fix, Trigger.MANUAL, snap, if (approximate) "Location was approximate" else null)
            val id = try {
                db.samples().insert(sample)
            } catch (e: Exception) {
                DebugLog.e("Couldn't save this point", e)
                return done(ManualResult(null, snap.dbm, snap.quality, snap.networkLabel, fix.accuracy, approximate, null, "Couldn't save this point."))
            }
            DebugLog.i("Logged MANUAL ${sample.carrierLabel} ${sample.signalDbm} dBm acc=${sample.locAccuracyM}")
            var speed: SpeedResult? = null
            if (withSpeed) {
                speed = SpeedTester.run(ctx) { phase ->
                    _test.value = TestState.Running(
                        when (phase) {
                            SpeedPhase.CONNECTING -> "Speed test: connecting over cellular…"
                            SpeedPhase.LATENCY -> "Speed test: latency…"
                            SpeedPhase.DOWNLOAD -> "Speed test: download…"
                            SpeedPhase.UPLOAD -> "Speed test: upload…"
                        }
                    )
                }
                runCatching {
                    db.speedTests().insert(
                        SpeedTest(
                            sampleId = id, tsUtcMs = System.currentTimeMillis(),
                            downMbps = speed.downMbps, upMbps = speed.upMbps, latencyMs = speed.latencyMs,
                            bytesUsed = speed.bytesUsed, ranOnCellular = speed.ranOnCellular, error = speed.error,
                        )
                    )
                }.onFailure { DebugLog.e("Couldn't save speed test", it) }
                DebugLog.i("Speed test: down=${speed.downMbps} up=${speed.upMbps} lat=${speed.latencyMs} cell=${speed.ranOnCellular} err=${speed.error}")
            }
            return done(ManualResult(id, snap.dbm, snap.quality, snap.networkLabel, fix.accuracy, approximate, speed, null))
        } catch (e: kotlinx.coroutines.CancellationException) {
            _test.value = TestState.Idle
            throw e
        } finally {
            SignalMonitor.get(ctx).stop("manual")
            manualLock.unlock()
        }
    }

    private fun done(r: ManualResult): ManualResult { _test.value = TestState.Done(r); return r }

    fun dismissTest() { _test.value = TestState.Idle }

    suspend fun setNote(sampleId: Long, note: String) {
        val existing = db.samples().byId(sampleId)?.note
        val merged = listOfNotNull(existing?.takeIf { it.isNotBlank() }, note.trim().takeIf { it.isNotEmpty() }).joinToString(" • ")
        db.samples().setNote(sampleId, merged.ifEmpty { null })
    }

    fun summary(r: ManualResult): String = buildString {
        if (r.error != null && r.sampleId == null) { append(r.error); return@buildString }
        append("Saved. Signal ")
        append(r.dbm?.let { "$it dBm (${r.quality.label})" } ?: "none (${r.quality.label})")
        if (r.networkLabel.isNotEmpty()) append(", ").append(r.networkLabel)
        append('.')
        r.speed?.let { s ->
            if (s.error != null && s.downMbps == null && s.upMbps == null) append(' ').append(s.error)
            else {
                append(" Download ").append(s.downMbps?.let { fmt1(it) + " Mbps" } ?: "failed")
                append(", upload ").append(s.upMbps?.let { fmt1(it) } ?: "failed")
                append(", latency ").append(s.latencyMs?.let { "${it.toInt()} ms" } ?: "failed").append('.')
            }
        }
        if (r.approximate) append(" Location was approximate.")
    }

    fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
