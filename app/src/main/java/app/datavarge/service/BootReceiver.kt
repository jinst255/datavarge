package app.datavarge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.datavarge.util.DebugLog
import app.datavarge.util.Notifs
import app.datavarge.util.Perms
import app.datavarge.util.Prefs

/**
 * After a reboot (or an app update, which kills the service), try to resume logging.
 * If Android refuses, post a notification so one tap brings it back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val prefs = Prefs(c)
        if (!prefs.loggingEnabled || !prefs.wizardDone) return
        val boot = i.action == Intent.ACTION_BOOT_COMPLETED
        DebugLog.i("BootReceiver: ${i.action}")
        if (boot) prefs.rebootNotePending = true
        if (!Perms.canLog(c)) return
        try {
            LoggingService.start(c)
        } catch (e: Exception) {
            DebugLog.w("Could not resume after ${i.action}", e)
            Notifs.alert(c, Notifs.ID_REBOOT, if (boot) "Phone restarted" else "Datavarge updated", "Tap to resume logging.")
        }
    }
}
