@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import network.retalert.domain.ANNOUNCE_PRESETS

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var filterHash by remember { mutableStateOf("") }
    var combo by remember { mutableStateOf("") }
    var comboTrigger by remember { mutableStateOf("") }
    var comboArm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Receive only from contacts", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = state.receiveOnly, onCheckedChange = vm::setReceiveOnly)
                    }
                    Text(
                        "allow (${state.allow.size}): ${state.allow.joinToString(",") { it.take(8) }.ifEmpty { "-" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "deny (${state.deny.size}): ${state.deny.joinToString(",") { it.take(8) }.ifEmpty { "-" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Distance units", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = state.units == "km", onClick = { vm.setUnits(false) }, label = { Text("km") })
                        FilterChip(selected = state.units == "mi", onClick = { vm.setUnits(true) }, label = { Text("mi") })
                    }
                    HorizontalDivider()
                    Text("Per-sender allow/deny", style = MaterialTheme.typography.titleSmall)
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
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Auto-announce", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.autoAnnounce,
                            onCheckedChange = { vm.setAutoAnnounce(it) },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ANNOUNCE_PRESET_VALUES.forEach { secs ->
                            FilterChip(
                                selected = state.announceInterval == secs,
                                onClick = { vm.setAnnounceInterval(secs) },
                                label = { Text(ANNOUNCE_PRESETS[secs] ?: "${secs.toInt()}s") },
                            )
                        }
                    }
                    Text(
                        "interval: ${state.announceInterval.toInt()}s (clamp ${ANNOUNCE_MIN_INTERVAL.toInt()}-${ANNOUNCE_MAX_INTERVAL.toInt()}s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = state.announceInterval.toFloat(),
                        onValueChange = { vm.setAnnounceInterval(it.toDouble()) },
                        valueRange = ANNOUNCE_MIN_INTERVAL.toFloat()..ANNOUNCE_MAX_INTERVAL.toFloat(),
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Interface-tier override", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("auto", "high", "medium", "low").forEach { tier ->
                            FilterChip(
                                selected = state.tierOverride == tier,
                                onClick = { vm.setTierOverride(tier) },
                                label = { Text(tier) },
                            )
                        }
                    }
                    HorizontalDivider()
                    Text("Share-instance host", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.shareInstanceHost,
                            onValueChange = vm::setShareInstanceHost,
                            label = { Text("host (attach)") },
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            singleLine = true,
                        )
                        OutlinedButton(onClick = vm::detachShareInstance) { Text("Detach") }
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Include prereleases (updater)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = state.includePrereleases,
                            onCheckedChange = vm::setIncludePrereleases,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hardware triggers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Register a volume-key sequence that arms or fires a preset alert. " +
                            "After editing combos, toggle the accessibility service off/on to reload them.",
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("arm combo (arm, don't fire)", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = comboArm, onCheckedChange = { comboArm = it })
                    }
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
                            )
                            OutlinedButton(onClick = { vm.removeKeyCombo(kc.combo) }) { Text("Remove") }
                        }
                    }
                }
            }

            if (state.flash.isNotEmpty()) {
                Text(state.flash, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val ANNOUNCE_PRESET_VALUES = network.retalert.domain.ANNOUNCE_PRESET_VALUES