package app.datavarge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.datavarge.service.LoggingService
import app.datavarge.service.SimTracker
import app.datavarge.ui.AppRoot
import app.datavarge.ui.DatavargeTheme
import app.datavarge.util.DebugLog
import app.datavarge.util.Perms
import app.datavarge.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openRequest.value = intent?.getStringExtra("open")
        setContent {
            DatavargeTheme { AppRoot(openRequest) { openRequest.value = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra("open")
    }

    override fun onResume() {
        super.onResume()
        val prefs = Prefs(this)
        if (Perms.phoneState(this) || Perms.fineLocation(this)) SimTracker.get(this).startListening()
        // Restart logging on app open if it should be running (covers OS kills and reboots).
        if (prefs.wizardDone && prefs.loggingEnabled && !LoggingService.isRunning && Perms.canLog(this)) {
            DebugLog.i("Restarting logging on app open")
            try { LoggingService.start(this) } catch (e: Exception) { DebugLog.e("Restart on open failed", e) }
        }
        if (LoggingService.bootCount(this) != prefs.lastBootCount && prefs.lastBootCount != -1 && prefs.loggingEnabled) {
            prefs.rebootNotePending = true
            prefs.lastBootCount = LoggingService.bootCount(this)
        }
    }
}
