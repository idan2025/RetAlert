@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.settings

import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.domain.ANNOUNCE_MAX_INTERVAL
import network.retalert.domain.ANNOUNCE_MIN_INTERVAL

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var filterHash by remember { mutableStateOf("") }
    var tcpSpec by remember { mutableStateOf("") }
    var combo by remember { mutableStateOf("") }
    var comboTrigger by remember { mutableStateOf("") }
    var comboArm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.flash.isNotEmpty()) {
                Text(state.flash, style = MaterialTheme.typography.bodyMedium)
            }

            SettingsCard {
                Text("Shared instance", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Use the Reticulum instance another app on this phone shares (Columba, Sideband, MeshChat) " +
                        "on 127.0.0.1 instead of running a separate stack. Enable instance sharing in that app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow(
                    "Connect to a shared instance",
                    checked = state.useSharedInstance,
                    onChange = vm::setUseSharedInstance,
                )
                var port by remember(state.sharedInstancePort) { mutableStateOf(state.sharedInstancePort.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("port on 127.0.0.1") },
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        singleLine = true,
                        enabled = state.useSharedInstance,
                    )
                    OutlinedButton(
                        onClick = { vm.setSharedInstancePort(port) },
                        enabled = state.useSharedInstance && port != state.sharedInstancePort.toString(),
                    ) { Text("Save") }
                }
                Text(
                    when {
                        state.restarting -> "reconnecting…"
                        !state.meshRunning -> "mesh not running"
                        state.sharedInstance -> "status: attached to shared instance"
                        else -> "status: standalone (own interfaces below)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = vm::reconnect,
                    enabled = !state.restarting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reconnect") }
            }

            SettingsCard {
                Text("Interfaces", style = MaterialTheme.typography.titleSmall)
                if (state.sharedInstance) {
                    Text(
                        "Attached to a shared instance — it provides the interfaces. " +
                            "These apply when RetAlert runs its own stack.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SwitchRow(
                    "AutoInterface (LAN / Wi-Fi peers)",
                    checked = state.autoInterface,
                    onChange = vm::setAutoInterface,
                )
                HorizontalDivider()
                Text("TCP interfaces", style = MaterialTheme.typography.bodyMedium)
                if (state.tcpInterfaces.isEmpty()) {
                    Text("(none)", style = MaterialTheme.typography.bodySmall)
                }
                state.tcpInterfaces.forEach { spec ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(spec, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.removeTcpInterface(spec) }) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tcpSpec,
                        onValueChange = { tcpSpec = it },
                        label = { Text("host:port") },
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                        singleLine = true,
                    )
                    OutlinedButton(onClick = { vm.addTcpInterface(tcpSpec); tcpSpec = "" }) { Text("Add") }
                }
            }

            SettingsCard {
                Text("Announce", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = vm::announceNow, modifier = Modifier.fillMaxWidth()) { Text("Announce now") }
                SwitchRow("Auto-announce", checked = state.autoAnnounce, onChange = vm::setAutoAnnounce)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.announcePresets().forEach { secs ->
                        FilterChip(
                            selected = state.announceInterval == secs,
                            onClick = { vm.setAnnounceInterval(secs) },
                            label = { Text(vm.announceLabels()[secs] ?: "${secs.toInt()}s") },
                        )
                    }
                }
                // Local slider state; the interval is committed on release only.
                var slider by remember(state.announceInterval) { mutableFloatStateOf(state.announceInterval.toFloat()) }
                Text(
                    "interval: ${(slider / 60).toInt()} min (${(ANNOUNCE_MIN_INTERVAL / 60).toInt()} min – ${(ANNOUNCE_MAX_INTERVAL / 3600).toInt()} h)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { vm.setAnnounceInterval(slider.toDouble()) },
                    valueRange = ANNOUNCE_MIN_INTERVAL.toFloat()..ANNOUNCE_MAX_INTERVAL.toFloat(),
                )
            }

            SettingsCard {
                Text("Incoming filter", style = MaterialTheme.typography.titleSmall)
                SwitchRow("Receive only from contacts", checked = state.receiveOnly, onChange = vm::setReceiveOnly)
                OutlinedTextField(
                    value = filterHash,
                    onValueChange = { filterHash = it },
                    label = { Text("hex hash to allow/deny") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.allow(filterHash); filterHash = "" }) { Text("Allow") }
                    OutlinedButton(onClick = { vm.deny(filterHash); filterHash = "" }) { Text("Deny") }
                }
                (state.allow.map { it to "allow" } + state.deny.map { it to "deny" }).forEach { (h, kind) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("$kind: ${h.take(12)}…", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.forget(h) }) { Text("Forget") }
                    }
                }
            }

            SettingsCard {
                Text("Distance units", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = state.units == "km", onClick = { vm.setUnits(false) }, label = { Text("km") })
                    FilterChip(selected = state.units == "mi", onClick = { vm.setUnits(true) }, label = { Text("mi") })
                }
            }

            SettingsCard {
                Text("Hardware triggers", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Register a volume-key sequence that arms or fires a preset alert.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enable hardware-trigger service") }
                HorizontalDivider()
                OutlinedTextField(
                    value = combo,
                    onValueChange = { combo = it },
                    label = { Text("combo (e.g. volume_up,volume_up,volume_down)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = comboTrigger,
                    onValueChange = { comboTrigger = it },
                    label = { Text("trigger (preset name)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SwitchRow("arm combo (arm, don't fire)", checked = comboArm, onChange = { comboArm = it })
                OutlinedButton(
                    onClick = {
                        vm.addKeyCombo(combo, comboTrigger, comboArm)
                        combo = ""; comboTrigger = ""; comboArm = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Register") }
                state.keyCombos.forEach { kc ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${kc.combo.joinToString(",")} → ${kc.trigger}${if (kc.arm) " (arm)" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.removeKeyCombo(kc.combo) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
