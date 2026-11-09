package app.datavarge.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.datavarge.Config
import app.datavarge.data.AppDatabase
import app.datavarge.service.LoggerState
import app.datavarge.service.LoggerStatus
import app.datavarge.service.LoggingService
import app.datavarge.service.Recorder
import app.datavarge.service.SignalMonitor
import app.datavarge.service.SimTracker
import app.datavarge.service.TestState
import app.datavarge.util.DebugLog
import app.datavarge.util.Perms
import app.datavarge.util.Prefs
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun HomeScreen(onLabelSim: () -> Unit) {
    val c = LocalContext.current
    val prefs = remember { Prefs(c) }
    val scope = rememberCoroutineScope()
    val monitor = remember { SignalMonitor.get(c) }
    val tick = rememberResumeTick()
    var permTick by remember { mutableIntStateOf(0) }

    // Live signal while Home is visible, even when logging is paused.
    DisposableEffect(tick, permTick) {
        monitor.start("home")
        SimTracker.get(c).startListening()
        onDispose { monitor.stop("home") }
    }

    val live by monitor.live.collectAsState()
    val sim by SimTracker.get(c).current.collectAsState()
    val status by LoggerState.status.collectAsState()
    val prefKey by remember { prefs.changes() }.collectAsState(initial = null)
    val enabled = remember(prefKey, status) { prefs.loggingEnabled }
    val pendingLabel = remember(prefKey, sim) { prefs.pendingLabelSimKey }
    val rebootNote = remember(prefKey, tick) { prefs.rebootNotePending }
    val dao = remember { AppDatabase.get(c).samples() }
    val total by dao.countAll().collectAsState(initial = 0)
    val startOfDay = remember(tick) {
        Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }
    val today by remember(startOfDay) { dao.countSince(startOfDay) }.collectAsState(initial = 0)
    val test by Recorder.testState.collectAsState()
    var speedOn by remember { mutableStateOf(prefs.speedTestOn) }
    val missing = remember(tick, permTick) { Perms.missing(c) }
    val fixLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        permTick++
        if (r.values.any { !it }) SystemScreens.appDetails(c)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (missing.isNotEmpty()) {
            SectionCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text("Missing permission: ${missing.joinToString(", ")}", fontWeight = FontWeight.SemiBold)
                Text("Logging needs these to work in the background.")
                Button(onClick = {
                    val needFine = !Perms.fineLocation(c)
                    when {
                        needFine || !Perms.phoneState(c) || !Perms.notifications(c) -> fixLauncher.launch(
                            listOfNotNull(
                                Manifest.permission.ACCESS_FINE_LOCATION.takeIf { needFine },
                                Manifest.permission.ACCESS_COARSE_LOCATION.takeIf { needFine },
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ).toTypedArray()
                        )
                        else -> SystemScreens.appDetails(c) // background location: Settings > Permissions > Location > Allow all the time
                    }
                }) { Text("Fix") }
            }
        }
        if (rebootNote) {
            SectionCard {
                Text(if (LoggingService.isRunning) "Your phone restarted. Logging resumed." else "Your phone restarted. Tap Resume to keep logging.")
                TextButton(onClick = { prefs.rebootNotePending = false }) { Text("OK") }
            }
        }
        if (pendingLabel != null && pendingLabel == sim.key) {
            SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("New SIM detected. What should we call it?", fontWeight = FontWeight.SemiBold)
                Button(onClick = onLabelSim) { Text("Label SIM") }
            }
        }

        // Status line: "Logging • Boost • 4G LTE • -92 dBm (Good)"
        SectionCard {
            val state = when {
                !enabled -> "Paused"
                status == LoggerStatus.LOCATION_OFF -> "Paused (location is off)"
                status == LoggerStatus.AIRPLANE -> "Paused (airplane mode)"
                status == LoggerStatus.LOGGING -> "Logging"
                else -> "Starting…"
            }
            val signal = when {
                !Perms.canLog(c) -> "no permission"
                live.tsMs == 0L -> "reading signal…"
                live.dbm == null -> live.quality.label
                else -> "${live.dbm} dBm (${live.quality.label})"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.Canvas(Modifier.size(14.dp)) { drawCircle(live.quality.color()) }
                Text(
                    listOf(state, sim.label, live.networkLabel, signal).joinToString(" • "),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text("Today: $today points • Total: $total", style = MaterialTheme.typography.bodyMedium)
        }

        // Test now
        SectionCard {
            val running = test is TestState.Running
            Button(
                onClick = { Recorder.startManual(speedOn) },
                enabled = !running && Perms.fineLocation(c),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Test now", style = MaterialTheme.typography.titleLarge) }
            Text("Logs this spot. Speed test uses about ${Config.SPEED_EST_MB} MB.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include speed test (about ${Config.SPEED_EST_MB} MB of your plan's data)", Modifier.weight(1f))
                Switch(checked = speedOn, onCheckedChange = { speedOn = it; prefs.speedTestOn = it })
            }
            when (val t = test) {
                is TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(t.step)
                }
                is TestState.Done -> ResultCard(t)
                TestState.Idle -> {}
            }
        }

        // Pause / Resume
        if (enabled) {
            OutlinedButton(onClick = { LoggingService.pause(c) }, modifier = Modifier.fillMaxWidth()) { Text("Pause") }
        } else {
            Button(onClick = {
                prefs.loggingEnabled = true
                try { LoggingService.start(c) } catch (e: Exception) {
                    DebugLog.e("Resume failed", e); Toast.makeText(c, "Couldn't start logging: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, enabled = Perms.canLog(c), modifier = Modifier.fillMaxWidth()) { Text("Resume") }
        }
    }
}

@Composable
private fun ResultCard(t: TestState.Done) {
    val scope = rememberCoroutineScope()
    var note by remember(t) { mutableStateOf("") }
    val r = t.result
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Recorder.summary(r), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        r.accuracyM?.let { Text("Location accuracy: ±${it.toInt()} m", style = MaterialTheme.typography.bodySmall) }
        if (r.speed != null && !r.speed.ranOnCellular) Text("Speed test did not run on cellular, so it isn't counted.", style = MaterialTheme.typography.bodySmall)
        if (r.sampleId != null) OutlinedTextField(
            value = note, onValueChange = { note = it.take(200) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Add a note (for example: garage level 2)") },
        )
        Button(onClick = {
            scope.launch {
                if (r.sampleId != null && note.isNotBlank()) Recorder.setNote(r.sampleId, note)
                Recorder.dismissTest()
            }
        }) { Text("Done") }
    }
}
