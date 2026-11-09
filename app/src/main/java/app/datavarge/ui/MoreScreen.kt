package app.datavarge.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.datavarge.BuildConfig
import app.datavarge.data.AppDatabase
import app.datavarge.data.SimLabel
import app.datavarge.data.UNLABELED
import app.datavarge.export.ExportResult
import app.datavarge.export.Exporter
import app.datavarge.service.SimTracker
import app.datavarge.util.DebugLog
import app.datavarge.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MoreScreen(onLabelCurrentSim: () -> Unit, onOpenWizard: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(c) }
    val labels by db.sims().all().collectAsState(initial = emptyList())
    val counts by db.samples().labelCounts().collectAsState(initial = emptyList())
    val sim by SimTracker.get(c).current.collectAsState()
    var renaming by remember { mutableStateOf<SimLabel?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var lastExport by remember { mutableStateOf<ExportResult?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tick = rememberResumeTick()
    val exempt = remember(tick) { Perms.batteryExempt(c) }
    val missing = remember(tick) { Perms.missing(c) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // SIM labels
        SectionCard {
            Text("SIM labels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Phones often report the host network (e.g. T-Mobile for Tello), so you name each SIM yourself. Renaming relabels all of that SIM's points.", style = MaterialTheme.typography.bodySmall)
            Text("In the phone now: ${if (sim.present) "${sim.label}${sim.operatorName?.let { " (reports \"$it\")" } ?: ""}" else "no SIM"}")
            if (sim.present && sim.label == UNLABELED) Button(onClick = onLabelCurrentSim) { Text("Label this SIM") }
            labels.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.label, fontWeight = FontWeight.Medium)
                        Text("${counts.firstOrNull { it.carrier_label == l.label }?.n ?: 0} points • first seen ${fmtTime(l.firstSeenMs)}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { renaming = l }) { Text("Rename") }
                }
            }
            counts.firstOrNull { it.carrier_label == UNLABELED }?.let { Text("Unlabeled points: ${it.n}", style = MaterialTheme.typography.bodySmall) }
        }

        // Export
        SectionCard {
            Text("Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Saves a zip (CSV + heatmap.html + README) to Downloads. Open heatmap.html on your computer.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !exporting, onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            val r = Exporter.export(c); lastExport = r
                            Toast.makeText(c, if (r.savedToDownloads) "Saved to Downloads/${r.fileName}" else "Couldn't save to Downloads; use Share", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            DebugLog.e("Export failed", e); Toast.makeText(c, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally { exporting = false }
                    }
                }) { Text("Export") }
                lastExport?.let { r -> OutlinedButton(onClick = { c.startActivity(Exporter.shareIntent(r.shareUri)) }) { Text("Share") } }
                if (exporting) CircularProgressIndicator()
            }
            lastExport?.let { Text("Downloads/${it.fileName}\nFrom the computer: adb pull /sdcard/Download/${it.fileName} .", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = {
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        val out = File(c.cacheDir, "exports").apply { mkdirs() }.let { File(it, "datavarge_debug_log.txt") }
                        out.writeText(DebugLog.files().joinToString("") { it.readText() })
                        out
                    }
                    val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
                    c.startActivity(Exporter.shareIntent(uri, "text/plain"))
                }
            }) { Text("Share debug log") }
        }

        // Permissions & battery help
        SectionCard {
            Text("Permissions and battery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (missing.isEmpty()) "✓ All permissions granted" else "Missing: ${missing.joinToString(", ")}")
            Text(if (exempt) "✓ Battery optimization: exempt" else "Battery optimization is ON for Datavarge, so Samsung may stop it.")
            Text("Samsung: Settings > Apps > Datavarge > Battery > Unrestricted. Then Settings > Battery > Background usage limits: Datavarge must not be in Sleeping apps or Deep sleeping apps.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { SystemScreens.appDetails(c) }) { Text("App settings") }
                OutlinedButton(onClick = { SystemScreens.samsungBackgroundLimits(c) }) { Text("Battery settings") }
            }
            TextButton(onClick = onOpenWizard) { Text("Run setup again") }
        }

        // Privacy & delete
        SectionCard {
            Text("Your data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Everything stays on this phone: no accounts, analytics, or cloud backup. Network use is map tiles from OpenStreetMap and speed tests to Cloudflare. Uninstalling deletes everything, so export first.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { confirmDelete = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete all data") }
        }
        Text("Datavarge ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
    }

    renaming?.let { l ->
        LabelDialog(title = "Rename SIM", initial = l.label, operatorName = null, onDismiss = { renaming = null }) { name ->
            scope.launch { SimTracker.get(c).setLabel(l.simKey, name); renaming = null }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete all data?") },
        text = { Text("This permanently removes every logged point, speed test, and SIM label on this phone. Export first if you want to keep it.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        db.speedTests().deleteAll(); db.samples().deleteAll(); db.sims().deleteAll(); db.sims().deleteAllActive()
                    }
                    DebugLog.i("All data deleted by user")
                    SimTracker.get(c).refresh()
                    Toast.makeText(c, "All data deleted", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

