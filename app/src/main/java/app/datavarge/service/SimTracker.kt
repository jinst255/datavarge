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

        /** Common US carriers and MVNOs offered as one-tap labels. Any other name can be typed. */
        val SUGGESTIONS = listOf(
            "AT&T", "Verizon", "T-Mobile", "Boost", "Tello", "US Mobile", "Mint", "Visible", "Cricket", "Metro",
        )

        private val BRANDS = listOf(
            "boost" to "Boost", "tello" to "Tello", "us mobile" to "US Mobile", "usmobile" to "US Mobile",
            "mint" to "Mint", "visible" to "Visible", "cricket" to "Cricket", "metro" to "Metro",
            "google fi" to "Google Fi", "project fi" to "Google Fi", "straight talk" to "Straight Talk",
            "total" to "Total Wireless", "tracfone" to "Tracfone", "xfinity" to "Xfinity Mobile",
            "spectrum" to "Spectrum Mobile", "consumer cellular" to "Consumer Cellular", "h2o" to "H2O",
            "red pocket" to "Red Pocket", "ultra" to "Ultra Mobile", "lyca" to "Lyca Mobile",
            "t-mobile" to "T-Mobile", "tmobile" to "T-Mobile", "verizon" to "Verizon", "at&t" to "AT&T", "att" to "AT&T",
        )

        /**
         * Best guess from the SIM's reported operator name, used only to pre-fill the label.
         * MVNOs often report their host network, which is why the user always confirms it.
         */
        fun suggest(operatorName: String?): String? {
            val raw = operatorName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val o = raw.lowercase()
            return BRANDS.firstOrNull { (k, _) -> o.contains(k) }?.second ?: raw.take(40)
        }
    }
}
