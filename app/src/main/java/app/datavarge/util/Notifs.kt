package app.datavarge.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.datavarge.MainActivity
import app.datavarge.R
import app.datavarge.service.LoggingService

object Notifs {
    const val CH_LOGGING = "logging"
    const val CH_ALERTS = "alerts"
    const val ID_LOGGING = 1
    const val ID_WATCHDOG = 2
    const val ID_LOCATION_OFF = 3
    const val ID_NEW_SIM = 4
    const val ID_TEST_RESULT = 5
    const val ID_REBOOT = 6

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LOGGING, "Logging", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet ongoing notification while Datavarge logs"
                setShowBadge(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Logging stopped, location off, new SIM, test results"
            }
        )
    }

    fun openAppIntent(c: Context, extra: String? = null): PendingIntent {
        val i = Intent(c, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (extra != null) putExtra("open", extra)
        }
        return PendingIntent.getActivity(c, extra?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            c, code, Intent(c, LoggingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun logging(c: Context, text: String) =
        NotificationCompat.Builder(c, CH_LOGGING)
            .setSmallIcon(R.drawable.ic_stat_logging)
            .setContentTitle("Datavarge is logging")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(c))
            .addAction(0, "Test now", serviceAction(c, LoggingService.ACTION_TEST_NOW, 10))
            .addAction(0, "Pause", serviceAction(c, LoggingService.ACTION_PAUSE, 11))
            .build()

    fun alert(c: Context, id: Int, title: String, text: String, extra: String? = null) {
        if (!Perms.notifications(c)) return
        val n = NotificationCompat.Builder(c, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_logging)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(c, extra))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)
}
