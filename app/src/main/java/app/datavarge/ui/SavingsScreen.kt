package app.datavarge.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.datavarge.analysis.Compare
import app.datavarge.savings.PlanResult
import app.datavarge.savings.Savings
import app.datavarge.savings.SavingsInputs
import app.datavarge.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavingsScreen() {
    val c = LocalContext.current
    val prefs = remember { Prefs(c) }
    val snapshot = remember { Savings.load(c) }
    var priceText by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", prefs.currentMonthly)) }
    var taxText by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.0f", prefs.taxRate * 100)) }
    var hotspot by rememberSaveable { mutableStateOf(prefs.needsHotspot) }
    var baseId by rememberSaveable { mutableStateOf(prefs.baselinePlanId ?: snapshot.baselinePlanId) }
    var otherTaxIncl by rememberSaveable { mutableStateOf(prefs.otherTaxesIncluded) }
    val inputs = SavingsInputs(priceText.toDoubleOrNull() ?: 0.0, hotspot, (taxText.toDoubleOrNull() ?: 0.0) / 100, baseId, otherTaxIncl)
    val basePlan = snapshot.plans.firstOrNull { it.id == baseId }
    val results = remember(inputs) { Savings.results(snapshot, inputs) }
    val baseline = remember(inputs) { Savings.baselineEffective(snapshot, inputs) }

    // Cross-link: place-weighted median signal for carriers whose label matches a plan's provider.
    val data by remember { AppData.observations(c) }.collectAsState(initial = null)
    val signalByLabel by produceState(emptyMap<String, Double?>(), data) {
        val d = data ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val obs = d.map { it.obs }
            obs.map { it.label }.distinct().associateWith { Compare.carrierStats(it, obs, 0).placeWeightedMedianDbm }
        }
    }
    fun signalFor(provider: String): Pair<String, Double?>? {
        val p = provider.lowercase()
        return signalByLabel.entries.firstOrNull { (label, _) ->
            val l = label.lowercase(); l.isNotBlank() && (p.startsWith(l) || l.startsWith(p))
        }?.toPair()
    }

    val dateText = remember {
        runCatching { SimpleDateFormat("MMM d, yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(snapshot.snapshotDate)!!) }
            .getOrDefault(snapshot.snapshotDate)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Prices checked $dateText. Verify on the carrier's site before switching.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                Text("Before you switch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                snapshot.generalCaveats.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SectionCard {
                Text("What you pay now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Your current plan", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.plans.forEach { p ->
                        FilterChip(selected = baseId == p.id, onClick = {
                            baseId = p.id; prefs.baselinePlanId = p.id
                            priceText = String.format(Locale.US, "%.2f", p.monthly); prefs.currentMonthly = p.monthly.toFloat()
                        }, label = { Text("${p.provider} ${p.name}") })
                    }
                    FilterChip(selected = baseId == Savings.OTHER, onClick = { baseId = Savings.OTHER; prefs.baselinePlanId = Savings.OTHER },
                        label = { Text("Other / not listed") })
                }
                if (basePlan == null) {
                    Text("Enter what you pay each month, including any add-ons (like hotspot) you pay for.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Price already includes taxes and fees", Modifier.weight(1f))
                        Switch(checked = otherTaxIncl, onCheckedChange = { otherTaxIncl = it; prefs.otherTaxesIncluded = it })
                    }
                }
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { v -> priceText = v.filter { it.isDigit() || it == '.' }.take(7); priceText.toFloatOrNull()?.let { prefs.currentMonthly = it } },
                    label = { Text("Current plan, per month before tax ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I need hotspot", Modifier.weight(1f))
                    Switch(checked = hotspot, onCheckedChange = { hotspot = it; prefs.needsHotspot = it })
                }
                OutlinedTextField(
                    value = taxText,
                    onValueChange = { v -> taxText = v.filter { it.isDigit() || it == '.' }.take(5); taxText.toFloatOrNull()?.let { prefs.taxRate = it / 100f } },
                    label = { Text("Estimated taxes and fees (%)") }, singleLine = true,
                    supportingText = { Text("Rough; the real rate depends on your state and city. Only added for plans that don't include taxes.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Text("Your effective cost: ${Savings.money(baseline)}/mo (${Savings.money(baseline * 12)}/yr)", fontWeight = FontWeight.Medium)
                basePlan?.let { bp -> signalFor(bp.provider)?.let { (l, v) -> Text("Your $l signal: ${fmtDbm(v)} (place-weighted median)", style = MaterialTheme.typography.bodySmall) } }
            }
        }
        items(results, key = { it.plan.id }) { r -> PlanRow(r, signalFor(r.plan.provider)) }
        item { Text("") }
    }
}

@Composable
private fun PlanRow(r: PlanResult, signal: Pair<String, Double?>?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val p = r.plan
    Card(Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.provider, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                }
                AssistChip(onClick = { open = !open }, label = { Text("${p.sourceConfidence} confidence") })
            }
            if (r.savingsMonthly >= 0.005) {
                Text("Saves ${Savings.money(r.savingsMonthly)}/mo, ${Savings.money(r.savingsYearly)}/yr", color = SaveGreen, fontWeight = FontWeight.SemiBold)
            } else if (r.savingsMonthly <= -0.005) {
                Text("Costs ${Savings.money(r.savingsMonthly)}/mo more (${Savings.money(r.savingsYearly)}/yr)", color = CostRed, fontWeight = FontWeight.SemiBold)
            } else Text("Same cost")
            Text("About ${Savings.money(r.effectiveMonthly)}/mo all-in" + if (p.taxesIncluded) " (taxes included)" else " (with estimated taxes)", style = MaterialTheme.typography.bodySmall)
            r.upfrontToday?.let { Text("Paid yearly: about ${Savings.money(it)} today (${Savings.money(r.effectiveMonthly)}/mo equivalent)", style = MaterialTheme.typography.bodySmall) }
            if (signal != null) Text("Your ${signal.first} signal: ${fmtDbm(signal.second)} (place-weighted median)", style = MaterialTheme.typography.bodySmall)
            if (open) {
                Text("Network: ${p.network}", style = MaterialTheme.typography.bodySmall)
                Text("Data: ${p.highSpeedGb?.let { "${it.toInt()} GB high-speed" } ?: "–"}${p.afterCap?.let { ", then $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                Text("Hotspot: ${if (p.hotspotIncluded) "included" else "add-on ${p.hotspotAddonMonthly?.let { Savings.money(it) + "/mo" } ?: ""}"}${p.hotspotNote?.let { ". $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                Text("Billing: ${p.billing}", style = MaterialTheme.typography.bodySmall)
                p.caveats.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            } else Text("Tap for details", style = MaterialTheme.typography.labelSmall)
        }
    }
}
