package app.datavarge.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.datavarge.Config
import app.datavarge.analysis.CapDecision
import app.datavarge.analysis.Geo
import app.datavarge.data.AppDatabase
import app.datavarge.data.ServiceStates
import app.datavarge.data.Trigger
import app.datavarge.util.DebugLog
import app.datavarge.util.Notifs
import app.datavarge.util.Perms
import app.datavarge.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max

/** What the logger is doing right now, for the UI. */
enum class LoggerStatus { STOPPED, LOGGING, LOCATION_OFF, AIRPLANE }

object LoggerState {
    val status = MutableStateFlow(LoggerStatus.STOPPED)
    val mode = MutableStateFlow(LocMode.MOVING)
}

/**
 * Owns all automatic logging. Foreground service of type "location".
 * Triggers: MOVE (>= 20 m from the last evaluated point), HEARTBEAT (every 10 min while still).
 * Battery: high accuracy while moving, balanced power after 2 min without displacement.
 */
class LoggingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var loc: LocationSource
    private val monitor by lazy { SignalMonitor.get(this) }
    private val sims by lazy { SimTracker.get(this) }

    private var started = false
    private var foregroundOk = false
    private var lastAliveWriteMs = 0L
    private var mode = LocMode.MOVING
    private var lastFix: Location? = null
    private var anchor: Location? = null
    private var lastMoveMs = 0L
    private var lastAutoAttemptMs = 0L
    private var lastAccountMs = 0L
    private var heartbeatRunning = false
    private var airplane = false
    private var locationOn = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { checkRadioAndLocation() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        loc = LocationSource.create(this)
        foregroundOk = goForeground()
    }

    private fun goForeground(): Boolean {
        if (!Perms.canLog(this)) {
            DebugLog.w("LoggingService: missing permissions, stopping")
            stopSelf(); return false
        }
        return try {
            ServiceCompat.startForeground(
                this, Notifs.ID_LOGGING, Notifs.logging(this, "Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: Exception) {
            DebugLog.e("startForeground failed", e)
            if (e is ForegroundServiceStartNotAllowedException || e is SecurityException) {
                Notifs.alert(this, Notifs.ID_WATCHDOG, "Logging stopped", "Tap to restart.")
            }
            stopSelf(); false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundOk) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_PAUSE -> {
                DebugLog.i("Paused by user")
                prefs.loggingEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_NOW -> {
                if (!started) begin()
                val ctx = applicationContext
                Recorder.startManual(prefs.speedTestOn, lastFix) { r ->
                    Notifs.alert(ctx, Notifs.ID_TEST_RESULT, "Test now", Recorder.summary(r))
                }
            }
            else -> {
                // ACTION_START, or a sticky restart (null intent).
                if (intent == null && !prefs.loggingEnabled) { stopSelf(); return START_NOT_STICKY }
                prefs.loggingEnabled = true
                begin()
            }
        }
        return START_STICKY
    }

    private fun begin() {
        if (started || !Perms.canLog(this)) return
        started = true
        isRunning = true
        prefs.lastBootCount = bootCount(this)
        DebugLog.i("Logging started")
        monitor.start("service")
        monitor.onServiceStateChange = { old, new -> onServiceState(old, new) }
        sims.startListening()
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lastMoveMs = System.currentTimeMillis()
        lastAutoAttemptMs = 0L // heartbeat right away if nothing moves
        lastAccountMs = System.currentTimeMillis()
        setMode(LocMode.MOVING)
        checkRadioAndLocation()

        // Backup ticker; location callbacks are the main clock (they also wake the CPU in Doze).
        scope.launch { while (true) { delay(Config.ACTIVE_TICK_MS); tick() } }

        // Keep the notification text current.
        val dao = AppDatabase.get(this).samples()
        scope.launch {
            combine(dao.countAll(), LoggerState.status) { n, st -> n to st }
                .distinctUntilChanged()
                .collect { (n, st) ->
                    val text = when (st) {
                        LoggerStatus.LOCATION_OFF -> "Location is off • $n points"
                        LoggerStatus.AIRPLANE -> "Airplane mode • $n points"
                        else -> "$n points • tap to open"
                    }
                    getSystemService(NotificationManager::class.java).notify(Notifs.ID_LOGGING, Notifs.logging(this@LoggingService, text))
                }
        }
    }

    private fun setMode(m: LocMode) {
        mode = m
        LoggerState.mode.value = m
        DebugLog.i("Location mode -> $m")
        try { loc.start(m, ::onFix) } catch (e: SecurityException) { DebugLog.e("Location permission lost", e); stopSelf() }
    }

    private fun checkRadioAndLocation() {
        airplane = Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val wasOn = locationOn
        locationOn = getSystemService(LocationManager::class.java).isLocationEnabled
        if (!locationOn && wasOn) {
            DebugLog.w("Location turned off")
            Notifs.alert(this, Notifs.ID_LOCATION_OFF, "Datavarge paused", "Location is off, so logging is paused.")
        } else if (locationOn && !wasOn) {
            DebugLog.i("Location back on")
            Notifs.cancel(this, Notifs.ID_LOCATION_OFF)
        }
        LoggerState.status.value = when {
            !locationOn -> LoggerStatus.LOCATION_OFF
            airplane -> LoggerStatus.AIRPLANE
            else -> LoggerStatus.LOGGING
        }
    }

    private fun canRecord() = locationOn && !airplane

    private fun onFix(l: Location) {
        lastFix = l
        val now = System.currentTimeMillis()
        if (canRecord()) {
            val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else 999.0
            val a = anchor
            val dist = a?.let { Geo.distanceM(it.latitude, it.longitude, l.latitude, l.longitude) } ?: Double.MAX_VALUE
            // Jitter guard: a jump must also exceed the fix's own uncertainty to count as movement.
            val moved = dist >= max(Config.MOVE_DISTANCE_M, acc)
            when (mode) {
                LocMode.MOVING -> {
                    if (moved && acc <= Config.MAX_ACCURACY_LOG_M) {
                        anchor = l; lastMoveMs = now; lastAutoAttemptMs = now
                        scope.launch { Recorder.recordAuto(l, Trigger.MOVE) }
                    } else if (now - lastMoveMs >= Config.STILL_AFTER_MS) {
                        if (anchor == null) anchor = l
                        setMode(LocMode.STILL)
                    }
                }
                LocMode.STILL -> if (moved) {
                    // Upgrade; the next high-accuracy fix that is >= 20 m from the anchor logs a MOVE.
                    lastMoveMs = now
                    setMode(LocMode.MOVING)
                }
            }
        }
        tick()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastAliveWriteMs >= 30_000L) { prefs.lastAliveMs = now; lastAliveWriteMs = now }
        // Covers the indoor case where high-accuracy mode produces no fixes at all.
        if (mode == LocMode.MOVING && now - lastMoveMs >= Config.STILL_AFTER_MS) {
            if (anchor == null) anchor = lastFix
            setMode(LocMode.STILL)
        }
        if (!locationOn || now - lastAccountMs >= Config.ACTIVE_TICK_MS) checkRadioAndLocation()
        if (now - lastAccountMs >= Config.ACTIVE_TICK_MS) {
            val gap = (now - lastAccountMs).coerceAtMost(15 * 60_000L)
            lastAccountMs = now
            val sim = sims.current.value
            if (canRecord() && sim.present) scope.launch(Dispatchers.IO) { AppDatabase.get(this@LoggingService).sims().addActive(sim.key, gap) }
        }
        if (canRecord() && !heartbeatRunning && now - lastAutoAttemptMs >= Config.HEARTBEAT_INTERVAL_MS) heartbeat()
    }

    private fun heartbeat() {
        heartbeatRunning = true
        lastAutoAttemptMs = System.currentTimeMillis()
        scope.launch {
            try {
                val base = lastFix?.takeIf { System.currentTimeMillis() - it.time < Config.HEARTBEAT_MAX_FIX_AGE_MS * 5 }
                // Cheap pre-check before powering up GPS: if this spot is already capped, skip.
                if (base != null && Recorder.capAllows(base.latitude, base.longitude) != CapDecision.LOG) {
                    DebugLog.i("Heartbeat skipped (cap)"); return@launch
                }
                val fix = loc.current(highAccuracy = true, timeoutMs = Config.HEARTBEAT_FIX_TIMEOUT_MS) ?: base
                if (fix == null) { DebugLog.i("Heartbeat skipped (no fix)"); return@launch }
                lastFix = fix
                val r = Recorder.recordAuto(fix, Trigger.HEARTBEAT)
                DebugLog.i("Heartbeat -> $r")
            } finally {
                heartbeatRunning = false
            }
        }
    }

    /** Losing service is the most useful result: log it right away at the last known spot. */
    private fun onServiceState(old: String, new: String) {
        DebugLog.i("Service state $old -> $new")
        if (ServiceStates.isNoService(new) && !ServiceStates.isNoService(old)) {
            val fix = lastFix?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000L } ?: return
            scope.launch {
                if (canRecord()) {
                    lastAutoAttemptMs = System.currentTimeMillis()
                    DebugLog.i("No-service sample -> ${Recorder.recordAuto(fix, Trigger.HEARTBEAT)}")
                }
            }
        }
        scope.launch { checkRadioAndLocation() }
    }

    override fun onDestroy() {
        DebugLog.i("Logging service destroyed (enabled=${prefs.loggingEnabled})")
        isRunning = false
        LoggerState.status.value = LoggerStatus.STOPPED
        if (started) {
            runCatching { unregisterReceiver(receiver) }
            loc.stop()
            monitor.onServiceStateChange = null
            monitor.stop("service")
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.datavarge.START"
        const val ACTION_PAUSE = "app.datavarge.PAUSE"
        const val ACTION_TEST_NOW = "app.datavarge.TEST_NOW"

        @Volatile var isRunning = false
            private set

        fun bootCount(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)

        /** Throws if Android refuses a background start; callers decide how to tell the user. */
        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, LoggingService::class.java).setAction(ACTION_START))
        }

        fun pause(c: Context) {
            Prefs(c).loggingEnabled = false
            c.stopService(Intent(c, LoggingService::class.java))
        }
    }
}
