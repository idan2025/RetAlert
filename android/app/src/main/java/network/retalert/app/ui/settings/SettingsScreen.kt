@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.app.ui.common.BackButton

private val SHARE_INTERVALS = listOf(10.0 to "10 s", 15.0 to "15 s", 30.0 to "30 s", 60.0 to "1 min")
private val SHARE_MINUTES = listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h")
private val ANNOUNCE_CHOICES = listOf(1800.0 to "30 min", 3600.0 to "1 h", 7200.0 to "2 h", 10800.0 to "3 h", 21600.0 to "6 h", 43200.0 to "12 h")
private val KEY_LABELS = mapOf("volume_up" to "Vol +", "volume_down" to "Vol −")

@Composable
fun SettingsScreen(onBack: (() -> Unit)? = null, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.flash) {
        if (state.flash.isNotEmpty()) {
            snackbar.showSnackbar(state.flash)
            vm.clearFlash()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmergencySection(state, vm)
            ConnectionSection(state, vm)
            AnnounceSection(state, vm)
            IncomingSection(state, vm)
            HardwareSection(state, vm)
            Section("Display") {
                Text("Distance units", style = MaterialTheme.typography.bodyLarge)
                ChoiceChips(listOf("km" to "Kilometres", "mi" to "Miles"), state.units) { vm.setUnits(it == "mi") }
            }
            Section("About") {
                LabelValue("Version", state.version.ifEmpty { "—" })
                Text("Your address (share it so others can add you)", style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(state.ownHash.ifEmpty { "mesh not running yet" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EmergencySection(state: SettingsUiState, vm: SettingsViewModel) = Section(
    "Emergency location",
    "What happens to your location when you raise an alert.",
) {
    SwitchItem(
        "Panic shares live location",
        "Panic alerts all contacts and keeps sending them your position. Presets choose their own (No / Once / Live).",
        state.panicShareLocation, vm::setPanicShareLocation,
    )
    HorizontalDivider()
    Text("Send an update every", style = MaterialTheme.typography.bodyLarge)
    ChoiceChips(SHARE_INTERVALS, state.liveShareIntervalS) { vm.setLiveShareInterval(it) }
    Text("Over LoRa-only links updates slow down to the preset's LoRa limit.", style = MaterialTheme.typography.bodySmall)
    Text("Keep sharing for", style = MaterialTheme.typography.bodyLarge)
    ChoiceChips(SHARE_MINUTES, state.liveShareMinutes) { vm.setLiveShareMinutes(it) }
    HorizontalDivider()
    BackgroundLocationRow()
}

/** "Allow all the time" location: needed when an alert fires with the screen
 *  off (hardware trigger) — Android blocks GPS for apps that aren't visible
 *  unless this is granted. */
@Composable
private fun BackgroundLocationRow() {
    val ctx = LocalContext.current
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var fg by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var bg by remember { mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bg = it }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { fg = it }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Location when the screen is off", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !fg -> "Location is off for RetAlert — sharing can't work."
                    bg -> "Allowed all the time — hardware triggers can share your location."
                    else -> "Only while using the app. Choose \"Allow all the time\" so a hardware trigger can share your location."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!fg) OutlinedButton(onClick = { fgLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow") }
        else if (!bg) OutlinedButton(onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Allow") }
    }
}

@Composable
private fun ConnectionSection(state: SettingsUiState, vm: SettingsViewModel) {
    var tcpSpec by remember { mutableStateOf("") }
    Section("Connection", "How RetAlert reaches the Reticulum network.") {
        StatusLine(
            when {
                state.restarting -> "Restarting…"
                !state.meshRunning -> "Mesh not running"
                state.sharedInstance -> "Connected through a shared instance"
                else -> "Running its own stack"
            },
        )
        SwitchItem(
            "Use shared instance",
            "Connect through Columba, Sideband or MeshChat on this phone (they must have instance sharing on). Changing this restarts RetAlert.",
            state.useSharedInstance, vm::setUseSharedInstance,
        )
        if (state.useSharedInstance) {
            var port by remember(state.sharedInstancePort) { mutableStateOf(state.sharedInstancePort.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port on 127.0.0.1") },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    singleLine = true,
                )
                OutlinedButton(onClick = { vm.setSharedInstancePort(port) }, enabled = port != state.sharedInstancePort.toString()) { Text("Save") }
            }
            OutlinedButton(onClick = vm::reconnect, enabled = !state.restarting, modifier = Modifier.fillMaxWidth()) {
                Text("Reconnect (e.g. after starting Columba)")
            }
        }
        HorizontalDivider()
        Text(
            if (state.sharedInstance) "Own interfaces (used when not on a shared instance)" else "Own interfaces",
            style = MaterialTheme.typography.titleSmall,
        )
        SwitchItem("AutoInterface", "Find RetAlert/Reticulum peers on the same Wi-Fi or LAN automatically.", state.autoInterface, vm::setAutoInterface)
        Text("TCP connections", style = MaterialTheme.typography.bodyLarge)
        if (state.tcpInterfaces.isEmpty()) Text("None — add a Reticulum node to reach the wider network.", style = MaterialTheme.typography.bodySmall)
        state.tcpInterfaces.forEach { spec ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(spec, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { vm.removeTcpInterface(spec) }) { Icon(Icons.Filled.Close, "Remove $spec") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tcpSpec,
                onValueChange = { tcpSpec = it },
                label = { Text("host:port") },
                placeholder = { Text("rns.example.org:4242") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                singleLine = true,
            )
            OutlinedButton(onClick = { vm.addTcpInterface(tcpSpec); tcpSpec = "" }, enabled = tcpSpec.isNotBlank()) { Text("Add") }
        }
    }
}

@Composable
private fun AnnounceSection(state: SettingsUiState, vm: SettingsViewModel) = Section(
    "Announce",
    "Announcing tells others on the network how to reach you.",
) {
    OutlinedButton(onClick = vm::announceNow, modifier = Modifier.fillMaxWidth()) { Text("Announce now") }
    SwitchItem("Announce automatically", null, state.autoAnnounce, vm::setAutoAnnounce)
    if (state.autoAnnounce) {
        Text("Every", style = MaterialTheme.typography.bodyLarge)
        ChoiceChips(ANNOUNCE_CHOICES, state.announceInterval) { vm.setAnnounceInterval(it) }
    }
}

@Composable
private fun IncomingSection(state: SettingsUiState, vm: SettingsViewModel) {
    var filterHash by remember { mutableStateOf("") }
    Section("Incoming alerts", "Who can reach you.") {
        SwitchItem(
            "Only from contacts",
            "Ignore messages from anyone who isn't a contact (unless allowed below).",
            state.receiveOnly, vm::setReceiveOnly,
        )
        OutlinedTextField(
            value = filterHash,
            onValueChange = { filterHash = it },
            label = { Text("Address to always allow or block") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.allow(filterHash); filterHash = "" }, enabled = filterHash.isNotBlank()) { Text("Always allow") }
            OutlinedButton(onClick = { vm.deny(filterHash); filterHash = "" }, enabled = filterHash.isNotBlank()) { Text("Block") }
        }
        (state.allow.map { it to "Allowed" } + state.deny.map { it to "Blocked" }).forEach { (h, kind) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$kind · ${h.take(12)}…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { vm.forget(h) }) { Icon(Icons.Filled.Close, "Forget ${h.take(8)}") }
            }
        }
    }
}

@Composable
private fun HardwareSection(state: SettingsUiState, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val keys = remember { mutableStateListOf<String>() }
    var trigger by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf(false) }
    Section("Hardware trigger", "Fire a preset with a volume-key sequence, even with the screen off.") {
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("1. Turn on the RetAlert accessibility service") }
        Text("2. Tap the sequence", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (keys.size < 8) keys.add("volume_up") }) { Text("Vol +") }
            OutlinedButton(onClick = { if (keys.size < 8) keys.add("volume_down") }) { Text("Vol −") }
            TextButton(onClick = { keys.clear() }, enabled = keys.isNotEmpty()) { Text("Clear") }
        }
        Text(
            if (keys.isEmpty()) "No keys yet" else keys.joinToString("  →  ") { KEY_LABELS[it] ?: it },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("3. Pick the preset it fires", style = MaterialTheme.typography.bodyLarge)
        if (state.presetNames.isEmpty()) {
            Text("Create a preset first (More → Presets).", style = MaterialTheme.typography.bodySmall)
        } else {
            ChoiceChips(state.presetNames.map { it to it }, trigger) { trigger = it }
        }
        SwitchItem("Arming sequence", "This sequence only arms; the next sequence within 5 s fires. Prevents pocket triggers.", arm) { arm = it }
        OutlinedButton(
            onClick = {
                vm.addKeyCombo(keys.joinToString(","), trigger, arm)
                keys.clear(); trigger = ""; arm = false
            },
            enabled = keys.isNotEmpty() && trigger.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save trigger") }
        state.keyCombos.forEach { kc ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kc.combo.joinToString(" → ") { KEY_LABELS[it] ?: it } + "  ⇒  " + (if (kc.arm) "arm" else kc.trigger),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { vm.removeKeyCombo(kc.combo) }) { Icon(Icons.Filled.Close, "Remove trigger") }
            }
        }
    }
}

// -- building blocks -----------------------------------------------------------

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    AssistChip(onClick = {}, label = { Text(text) })
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
