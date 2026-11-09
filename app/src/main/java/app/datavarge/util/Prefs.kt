package app.datavarge.util

import android.content.Context
import android.content.SharedPreferences
import app.datavarge.savings.SavingsInputs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Small key/value settings. SharedPreferences is plenty for a handful of flags. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("datavarge", Context.MODE_PRIVATE)

    var wizardDone: Boolean
        get() = sp.getBoolean("wizard_done", false)
        set(v) = sp.edit().putBoolean("wizard_done", v).apply()

    /** The user's intent: should logging be running? Pause sets this false. */
    var loggingEnabled: Boolean
        get() = sp.getBoolean("logging_enabled", false)
        set(v) = sp.edit().putBoolean("logging_enabled", v).apply()

    var speedTestOn: Boolean
        get() = sp.getBoolean("speed_test_on", true)
        set(v) = sp.edit().putBoolean("speed_test_on", v).apply()

    var currentMonthly: Float
        get() = sp.getFloat("current_monthly", 25.00f)
        set(v) = sp.edit().putFloat("current_monthly", v).apply()

    var needsHotspot: Boolean
        get() = sp.getBoolean("needs_hotspot", true)
        set(v) = sp.edit().putBoolean("needs_hotspot", v).apply()

    var taxRate: Float
        get() = sp.getFloat("tax_rate", 0.14f)
        set(v) = sp.edit().putFloat("tax_rate", v).apply()

    /** Boot count seen when logging last ran; a mismatch on open means the phone rebooted. */
    var lastBootCount: Int
        get() = sp.getInt("last_boot_count", -1)
        set(v) = sp.edit().putInt("last_boot_count", v).apply()

    /** Written by the logging service on every tick; the watchdog reads it. */
    var lastAliveMs: Long
        get() = sp.getLong("last_alive_ms", 0L)
        set(v) = sp.edit().putLong("last_alive_ms", v).apply()

    var rebootNotePending: Boolean
        get() = sp.getBoolean("reboot_note", false)
        set(v) = sp.edit().putBoolean("reboot_note", v).apply()

    /** sim_key of a SIM that still needs a label, or null. */
    var pendingLabelSimKey: String?
        get() = sp.getString("pending_label_sim", null)
        set(v) = sp.edit().putString("pending_label_sim", v).apply()

    /** Current plan id from plans.json, "other", or null for the snapshot's default. */
    var baselinePlanId: String?
        get() = sp.getString("baseline_plan_id", null)
        set(v) = sp.edit().putString("baseline_plan_id", v).apply()

    var otherTaxesIncluded: Boolean
        get() = sp.getBoolean("other_taxes_included", false)
        set(v) = sp.edit().putBoolean("other_taxes_included", v).apply()

    fun savingsInputs() = SavingsInputs(currentMonthly.toDouble(), needsHotspot, taxRate.toDouble(), baselinePlanId, otherTaxesIncluded)

    fun changes(): Flow<String?> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
        sp.registerOnSharedPreferenceChangeListener(l)
        trySend(null)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
}
