package app.datavarge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.datavarge.Config
import app.datavarge.analysis.CarrierStats
import app.datavarge.analysis.Compare
import app.datavarge.analysis.HeadToHead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CompareData(val stats: List<CarrierStats>, val pairs: List<HeadToHead>)

@Composable
fun CompareScreen() {
    val c = LocalContext.current
    val data by remember { AppData.observations(c) }.collectAsState(initial = null)
    val active by remember { AppData.activeMsByLabel(c) }.collectAsState(initial = emptyMap())
    val result by produceState<CompareData?>(null, data, active) {
        val d = data ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val obs = d.map { it.obs }
            val labels = obs.map { it.label }.distinct()
            CompareData(
                labels.map { Compare.carrierStats(it, obs, active[it] ?: 0L) },
                labels.flatMapIndexed { i, a -> labels.drop(i + 1).map { b -> Compare.headToHead(a, b, obs) } },
            )
        }
    }
    val r = result ?: return
    if (r.stats.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Need data from at least one carrier. Swap SIMs to compare more.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Signal strength is not speed. Time of day and congestion affect speed tests.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (r.stats.size < 2) item {
            Text("Need data from at least one carrier. Swap SIMs to compare more.", style = MaterialTheme.typography.bodyMedium)
        }
        items(r.stats, key = { it.label }) { s -> StatsCard(s) }
        items(r.pairs, key = { it.a + "|" + it.b }) { h -> HeadToHeadCard(h) }
        item { Text("") }
    }
}

@Composable
private fun StatRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatsCard(s: CarrierStats) {
    SectionCard {
        Text(s.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        StatRow("Samples", "${s.samples}")
        StatRow("Distinct 20 m cells", "${s.cells}")
        StatRow("Hours logged", fmt1(s.hoursLogged))
        StatRow("Place-weighted median signal", fmtDbm(s.placeWeightedMedianDbm))
        StatRow("Worst 10% of places (p10)", fmtDbm(s.p10Dbm))
        StatRow("Cells usable (≥ ${Config.USABLE_DBM} dBm)", fmtPct(s.usableCellPct))
        StatRow("No service (of samples)", fmtPct(s.noServicePct))
        if (s.speedTests == 0) StatRow("Speed tests", "none yet")
        else {
            StatRow("Median download", "${fmt1(s.medianDown)} Mbps")
            StatRow("Median upload", "${fmt1(s.medianUp)} Mbps")
            StatRow("Median latency", s.medianLatency?.let { "${it.toInt()} ms" } ?: "–")
            StatRow("Speed tests (cellular only)", "${s.speedTests}")
        }
    }
}

@Composable
private fun HeadToHeadCard(h: HeadToHead) {
    SectionCard {
        Text("${h.a} vs ${h.b}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Same places only: cells with at least ${Config.HEAD_TO_HEAD_MIN_SAMPLES} samples from both. A difference under ${Config.TIE_THRESHOLD_DB.toInt()} dB is a tie.", style = MaterialTheme.typography.bodySmall)
        StatRow("Shared cells", "${h.sharedCells}")
        if (!h.enoughData) {
            Text("Not enough overlapping places yet. Repeat your usual routes with each SIM.", color = MaterialTheme.colorScheme.error)
        }
        if (h.sharedCells > 0) {
            StatRow("${h.a} stronger", "${h.aWins}")
            StatRow("Ties", "${h.ties}")
            StatRow("${h.b} stronger", "${h.bWins}")
        }
    }
}
