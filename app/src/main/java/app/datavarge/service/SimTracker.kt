package app.datavarge.service

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.datavarge.data.AppDatabase
import app.datavarge.data.SimLabel
import app.datavarge.data.UNLABELED
import app.datavarge.util.DebugLog
import app.datavarge.util.Notifs
import app.datavarge.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

data class SimInfo(
    val key: String,
    val label: String,
    val operatorName: String?,
    val mccMnc: String?,
    val present: Boolean,
)

/**
 * Detects SIM swaps. A SIM is keyed by default-data subscription ID + MCC/MNC; both are readable
 * without extra permissions (the subscription ID changes when a different physical SIM is inserted).
 * New SIMs are stored as "Unlabeled" until the user names them; then their samples are backfilled.
 */
class SimTracker private constructor(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.get(ctx)
    private val prefs = Prefs(ctx)
    private val _current = MutableStateFlow(SimInfo("none", UNLABELED, null, null, false))
    val current: StateFlow<SimInfo> = _current
    private var listening = false

    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() { refresh() }
    }

    @Synchronized
    fun startListening() {
        if (listening) return
        listening = true
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        runCatching { sm.addOnSubscriptionsChangedListener(Executor { scope.launch { it.run() } }, listener) }
            .onFailure { DebugLog.e("addOnSubscriptionsChangedListener failed", it) }
        refresh()
    }

    fun refresh() {
        scope.launch {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            val present = tm.simState == TelephonyManager.SIM_STATE_READY
            val mccMnc = tm.simOperator?.takeIf { it.isNotBlank() }
            val op = tm.simOperatorName?.takeIf { it.isNotBlank() }
            if (!present || mccMnc == null) {
                _current.value = SimInfo("none", UNLABELED, op, mccMnc, false)
                return@launch
            }
            val key = "$subId-$mccMnc"
            val prev = _current.value.key
            val existing = db.sims().get(key)
            if (existing == null) {
                // First time we see this SIM: ask for a label, store samples as Unlabeled meanwhile.
                if (prefs.pendingLabelSimKey != key) {
                    prefs.pendingLabelSimKey = key
                    DebugLog.i("New SIM detected: $key ($op)")
                    Notifs.alert(ctx, Notifs.ID_NEW_SIM, "New SIM detected", "What should we call it? Tap to label it.", "label")
                }
            }
            _current.value = SimInfo(key, existing?.label ?: UNLABELED, op, mccMnc, true)
            if (prev != key && prev != "none") {
                DebugLog.i("SIM changed $prev -> $key")
                SignalMonitor.get(ctx).restart()
            }
        }
    }

    /** Label the SIM and backfill all of its Unlabeled samples. */
    suspend fun setLabel(simKey: String, label: String) {
        val clean = label.trim().ifEmpty { return }
        val existing = db.sims().get(simKey)
        db.sims().upsert(SimLabel(simKey, clean, existing?.firstSeenMs ?: System.currentTimeMillis()))
        val n = db.samples().relabel(simKey, clean)
        DebugLog.i("Labeled $simKey as '$clean', backfilled $n samples")
        if (prefs.pendingLabelSimKey == simKey) prefs.pendingLabelSimKey = null
        Notifs.cancel(ctx, Notifs.ID_NEW_SIM)
        if (_current.value.key == simKey) _current.value = _current.value.copy(label = clean)
    }

    companion object {
        @Volatile private var inst: SimTracker? = null
        fun get(c: Context): SimTracker = inst ?: synchronized(this) {
            inst ?: SimTracker(c.applicationContext).also { inst = it }
        }

        val SUGGESTIONS = listOf("Boost", "Tello", "US Mobile")

        /** Best guess from the SIM's operator name, used only to pre-select a chip. */
        fun suggest(operatorName: String?): String? {
            val o = operatorName?.lowercase() ?: return null
            return when {
                "boost" in o -> "Boost"
                "tello" in o -> "Tello"
                "us mobile" in o || "usmobile" in o -> "US Mobile"
                else -> null
            }
        }
    }
}
