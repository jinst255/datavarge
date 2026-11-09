package app.datavarge.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.datavarge.analysis.Quality
import app.datavarge.service.SimTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Quality.color() = Color(argb)

fun fmtDbm(v: Double?): String = when {
    v == null -> "–"
    v <= app.datavarge.analysis.NO_SERVICE_DBM -> "No service"
    else -> "${v.toInt()} dBm"
}

fun fmt1(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
fun fmtPct(v: Double?) = v?.let { String.format(Locale.US, "%.0f%%", it) } ?: "–"
fun fmtTime(ms: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(ms))

/** Increments every time the screen resumes, so permission/battery state is re-read after visiting Settings. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, container: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (container != null) CardDefaults.cardColors(containerColor = container) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelChips(selected: String, onSelect: (String) -> Unit, onOther: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SimTracker.SUGGESTIONS.forEach { s ->
            FilterChip(selected = selected == s, onClick = { onSelect(s) }, label = { Text(s) })
        }
        FilterChip(
            selected = selected.isNotEmpty() && selected !in SimTracker.SUGGESTIONS,
            onClick = onOther,
            label = { Text(if (selected.isNotEmpty() && selected !in SimTracker.SUGGESTIONS) selected else "Other") },
        )
    }
}

/** "New SIM detected. What should we call it?" */
@Composable
fun LabelDialog(title: String, initial: String, operatorName: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var typing by remember { mutableStateOf(initial.isNotEmpty() && initial !in SimTracker.SUGGESTIONS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (operatorName != null) Text("The SIM reports \"$operatorName\".", style = MaterialTheme.typography.bodySmall)
                LabelChips(selected = value, onSelect = { value = it; typing = false }, onOther = { typing = true; if (value in SimTracker.SUGGESTIONS) value = "" })
                if (typing) OutlinedTextField(
                    value = value, onValueChange = { value = it.take(40) }, singleLine = true,
                    label = { Text("Name, e.g. US Mobile (Verizon)") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

object SystemScreens {
    private fun tryStart(c: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        return false
    }

    fun appDetails(c: Context) = tryStart(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(c: Context) = tryStart(
        c,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    /** Samsung "Background usage limits" (Sleeping apps lives here), falling back to generic battery screens. */
    fun samsungBackgroundLimits(c: Context) = tryStart(
        c,
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
    )

    fun locationSettings(c: Context) = tryStart(c, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}
