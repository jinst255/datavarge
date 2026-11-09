package app.datavarge.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Perms {
    fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun fineLocation(c: Context) = has(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocation(c: Context) = has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun phoneState(c: Context) = has(c, Manifest.permission.READ_PHONE_STATE)
    fun notifications(c: Context) = android.os.Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryExempt(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** What logging strictly needs to run. */
    fun canLog(c: Context) = fineLocation(c) && phoneState(c)

    fun missing(c: Context): List<String> = buildList {
        if (!fineLocation(c)) add("Precise location")
        if (!backgroundLocation(c)) add("Location \"Allow all the time\"")
        if (!phoneState(c)) add("Phone")
        if (!notifications(c)) add("Notifications")
    }
}
