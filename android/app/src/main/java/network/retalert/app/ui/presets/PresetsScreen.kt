@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.presets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.domain.LORA_THROTTLE_PRESETS
import network.retalert.domain.PAYLOAD_CLASSES
import network.retalert.domain.Severity

@Composable
fun PresetsScreen(vm: PresetsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Presets") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New preset")
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (state.flash.isNotEmpty()) {
                Text(state.flash, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.rows.isEmpty()) {
                Text(
                    "No presets yet. Tap + to create one. A preset named \"default\" is what the Home panic button fires " +
                        "(without one, panic alerts every contact).",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.id }) { row -> PresetCard(row, state.expandedId == row.id, vm) }
                }
            }
        }
    }

    if (creating) {
        CreatePresetDialog(
            onDismiss = { creating = false },
            onSave = { name, sev, text, targets ->
                vm.create(name, sev, text, targets)
                creating = false
            },
        )
    }
}

@Composable
private fun PresetCard(row: PresetRow, expanded: Boolean, vm: PresetsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${row.name}  [${row.severity}]", style = MaterialTheme.typography.titleSmall)
                    Text("fan-out=${row.fanOut}  to ${row.recipientsLabel}", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { vm.fire(row.name) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Fire") }
            }
            Row {
                TextButton(onClick = { vm.toggleExpand(row.id) }) {
                    Text(if (expanded) "▾ hide" else "▸ details")
                }
                TextButton(onClick = { vm.delete(row.id) }) { Text("Delete") }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Payload", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PAYLOAD_CLASSES.forEach { key ->
                            FilterChip(
                                selected = row.payload[key] == true,
                                onClick = { vm.togglePayload(row.id, key) },
                                label = { Text(key) },
                            )
                        }
                    }
                    Text("LoRa throttle (s)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LORA_THROTTLE_PRESETS.forEach { s ->
                            AssistChip(
                                onClick = { vm.setLoraThrottle(row.id, s.toDouble()) },
                                label = { Text("$s") },
                            )
                        }
                        row.loraThrottle?.let {
                            Text("now: ${it.toInt()}s", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatePresetDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("default") }
    var severity by remember { mutableStateOf(Severity.CRITICAL) }
    var text by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Severity.ALL.forEach { sev ->
                        FilterChip(selected = severity == sev, onClick = { severity = sev }, label = { Text(sev) })
                    }
                }
                OutlinedTextField(text, { text = it }, label = { Text("message") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    targets, { targets = it },
                    label = { Text("recipients") },
                    supportingText = { Text("contact names or hashes, comma-separated — or one group name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, severity, text, targets) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
