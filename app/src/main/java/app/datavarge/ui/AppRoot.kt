package app.datavarge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.datavarge.service.SimTracker
import app.datavarge.util.Prefs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    MAP("Map", Icons.Filled.LocationOn),
    COMPARE("Compare", Icons.AutoMirrored.Filled.List),
    SAVINGS("Savings", Icons.Filled.ShoppingCart),
    MORE("More", Icons.Filled.Menu),
}

@Composable
fun AppRoot(openRequest: StateFlow<String?>, consumeOpen: () -> Unit) {
    val c = LocalContext.current
    val prefs = remember { Prefs(c) }
    var wizardDone by rememberSaveable { mutableStateOf(prefs.wizardDone) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var labelDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sim by SimTracker.get(c).current.collectAsState()
    val open by openRequest.collectAsState()

    LaunchedEffect(open) {
        if (open == "label") { tab = Tab.HOME; labelDialog = true; consumeOpen() }
    }

    if (!wizardDone) {
        WizardScreen(onDone = { wizardDone = true })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.title) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                Tab.HOME -> HomeScreen(onLabelSim = { labelDialog = true })
                Tab.MAP -> MapScreen()
                Tab.COMPARE -> CompareScreen()
                Tab.SAVINGS -> SavingsScreen()
                Tab.MORE -> MoreScreen(onLabelCurrentSim = { labelDialog = true }, onOpenWizard = { prefs.wizardDone = false; wizardDone = false })
            }
        }
    }

    if (labelDialog && sim.present) {
        LabelDialog(
            title = "New SIM detected. What should we call it?",
            initial = SimTracker.suggest(sim.operatorName) ?: "",
            operatorName = sim.operatorName,
            onDismiss = { labelDialog = false },
        ) { name -> scope.launch { SimTracker.get(c).setLabel(sim.key, name); labelDialog = false } }
    }
}
