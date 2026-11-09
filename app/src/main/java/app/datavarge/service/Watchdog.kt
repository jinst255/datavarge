package app.datavarge.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.datavarge.Config
import app.datavarge.util.DebugLog
import app.datavarge.util.Notifs
import app.datavarge.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Every ~30 min: if logging should be on but the service hasn't ticked for 30 min, Samsung (or Android)
 * killed it. Try to restart it (allowed when the app is battery-exempt); otherwise tell the user.
 * Uses the service's own alive timestamp rather than the newest sample, because the density cap
 * legitimately produces long gaps when you stay in one capped place.
 */
class Watchdog(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val prefs = Prefs(c)
        if (!prefs.loggingEnabled) return Result.success()
        val stale = System.currentTimeMillis() - prefs.lastAliveMs > Config.WATCHDOG_STALE_MS
        if (!stale && LoggingService.isRunning) return Result.success()
        DebugLog.w("Watchdog: logging looks stopped (stale=$stale running=${LoggingService.isRunning})")
        try {
            LoggingService.start(c)
            DebugLog.i("Watchdog restarted the service")
        } catch (e: Exception) {
            DebugLog.w("Watchdog could not restart the service", e)
            Notifs.alert(c, Notifs.ID_WATCHDOG, "Logging stopped", "Tap to restart.")
        }
        return Result.success()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<Watchdog>(Config.WATCHDOG_PERIOD_MIN, TimeUnit.MINUTES).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
