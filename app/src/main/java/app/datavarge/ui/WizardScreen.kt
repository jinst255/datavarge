package app.datavarge.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.datavarge.service.LoggingService
import app.datavarge.service.SimTracker
import app.datavarge.util.DebugLog
import app.datavarge.util.Perms
import app.datavarge.util.Prefs
import kotlinx.coroutines.launch

private const val STEPS = 6

@Composable
fun WizardScreen(onDone: () -> Unit) {
    val c = LocalContext.current
    val prefs = remember { Prefs(c) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    val tick = rememberResumeTick()
    var permTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        Text("Step ${step + 1} of $STEPS", style = MaterialTheme.typography.labelMedium)

        // Re-read permission state whenever we return from Settings or a permission dialog.
        val refresh = tick + permTick
        val fine = remember(refresh) { Perms.fineLocation(c) }
        val bg = remember(refresh) { Perms.backgroundLocation(c) }
        val phone = remember(refresh) { Perms.phoneState(c) }
        val notif = remember(refresh) { Perms.notifications(c) }
        val exempt = remember(refresh) { Perms.batteryExempt(c) }

        val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
        var bgAsked by rememberSaveable { mutableIntStateOf(0) }
        val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bgAsked++; permTick++ }
        val otherLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }

        when (step) {
            0 -> {
                Title("What this does")
                Body("Datavarge quietly records your phone's signal strength and location so you can see which carrier works best where you actually go. Your data stays on this phone.")
                Button(onClick = { step++ }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
            1 -> {
                Title("Location")
                Body("Allow precise location, then choose \"Allow all the time\" so it can log while closed.")
                if (!fine) {
                    Body("First, Android will ask for location access. Choose \"Precise\" and \"While using the app\".")
                    Button(onClick = {
                        fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Allow precise location") }
                } else if (!bg) {
                    Check("Precise location allowed")
                    Body("Next, Android opens a settings page. Choose \"Allow all the time\", then come back.")
                    Button(onClick = {
                        if (bgAsked < 2) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else SystemScreens.appDetails(c)
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (bgAsked < 2) "Allow all the time" else "Open app settings > Permissions > Location") }
                } else {
                    Check("Precise location allowed")
                    Check("Allowed all the time")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = fine, onClick = { step++ }) { Text("Continue") }
                    if (fine && !bg) TextButton(onClick = { step++ }) { Text("Skip (logging stops when closed)") }
                }
            }
            2 -> {
                Title("Other permissions")
                Body("Phone: needed to read the network type and detect SIM swaps. Notifications: the quiet \"logging\" notification and alerts if logging stops.")
                if (phone) Check("Phone allowed")
                if (notif) Check("Notifications allowed")
                if (!phone || !notif) Button(onClick = {
                    otherLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS))
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
                if (!phone && permTick > 0) OutlinedButton(onClick = { SystemScreens.appDetails(c) }) { Text("Open app settings") }
                Button(enabled = phone, onClick = { SimTracker.get(c).startListening(); step++ }) { Text("Continue") }
            }
            3 -> {
                Title("Keep it running")
                Body("Samsung phones put background apps to sleep. Three settings keep Datavarge logging:")
                if (exempt) Check("Battery optimization: exempt") else {
                    Body("1. Allow Datavarge to run without battery optimization.")
                    Button(onClick = { SystemScreens.requestBatteryExemption(c) }, modifier = Modifier.fillMaxWidth()) { Text("Allow background running") }
                }
                Body("2. Settings > Apps > Datavarge > Battery > Unrestricted.")
                OutlinedButton(onClick = { SystemScreens.appDetails(c) }, modifier = Modifier.fillMaxWidth()) { Text("Open Datavarge app settings") }
                Body("3. Settings > Battery > Background usage limits: make sure Datavarge is NOT in \"Sleeping apps\" or \"Deep sleeping apps\". This is the one most people miss.")
                OutlinedButton(onClick = { SystemScreens.samsungBackgroundLimits(c) }, modifier = Modifier.fillMaxWidth()) { Text("Open battery settings") }
                Button(onClick = { step++ }) { Text("Continue") }
            }
            4 -> {
                Title("Your SIM and plan")
                val sim by SimTracker.get(c).current.collectAsState()
                var label by rememberSaveable { mutableStateOf("") }
                LaunchedEffect(sim.key) { if (label.isEmpty()) label = SimTracker.suggest(sim.operatorName) ?: "" }
                var typing by remember { mutableStateOf(false) }
                Body(if (sim.present) "What should we call the SIM that's in the phone now?" + (sim.operatorName?.let { " (It reports \"$it\".)" } ?: "")
                     else "No SIM detected. You can label it later in More.")
                LabelChips(selected = label, onSelect = { label = it; typing = false }, onOther = { typing = true; label = "" })
                if (typing) OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("Name, e.g. US Mobile (Verizon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                var price by rememberSaveable { mutableStateOf(String.format(java.util.Locale.US, "%.2f", prefs.currentMonthly)) }
                var hotspot by rememberSaveable { mutableStateOf(prefs.needsHotspot) }
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                    label = { Text("Current monthly price, before tax ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I need hotspot", Modifier.weight(1f)); Switch(checked = hotspot, onCheckedChange = { hotspot = it })
                }
                Button(onClick = {
                    price.toFloatOrNull()?.let { prefs.currentMonthly = it }
                    prefs.needsHotspot = hotspot
                    if (sim.present && label.isNotBlank()) scope.launch { SimTracker.get(c).setLabel(sim.key, label) }
                    step++
                }) { Text("Continue") }
            }
            else -> {
                Title("Start")
                Body("You're set. Use your phone normally for a couple of days.")
                if (!bg) Body("Note: \"Allow all the time\" is off, so logging pauses when the app is closed.")
                Button(onClick = {
                    prefs.loggingEnabled = true
                    prefs.wizardDone = true
                    try { LoggingService.start(c) } catch (e: Exception) { DebugLog.e("start from wizard failed", e) }
                    onDone()
                }, modifier = Modifier.fillMaxWidth()) { Text("Start logging") }
            }
        }
        if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
@Composable private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge)
@Composable private fun Check(t: String) = Text("✓ $t", color = SaveGreen, style = MaterialTheme.typography.bodyLarge)
