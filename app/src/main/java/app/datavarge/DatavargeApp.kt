package app.datavarge

import android.app.Application
import app.datavarge.service.Recorder
import app.datavarge.service.Watchdog
import app.datavarge.util.DebugLog
import app.datavarge.util.Notifs
import org.osmdroid.config.Configuration
import java.io.File

class DatavargeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        Notifs.createChannels(this)
        Recorder.init(this)
        Configuration.getInstance().apply {
            userAgentValue = "app.datavarge/${BuildConfig.VERSION_NAME} (personal, non-commercial)"
            // Tiles live in app storage (no storage permission) and persist, so visited areas work offline.
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
            expirationOverrideDuration = 30L * 24 * 3600 * 1000
        }
        Watchdog.schedule(this)
    }
}
