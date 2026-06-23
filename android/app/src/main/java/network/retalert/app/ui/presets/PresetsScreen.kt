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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.domain.LORA_THROTTLE_PRESETS
import network.retalert.domain.PAYLOAD_CLASSES

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsScreen(vm: PresetsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Presets") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
    ) { inner ->
        if (state.rows.isEmpty()) {
            Text(
                "(no presets configured)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(inner).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
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
                            TextButton(onClick = { vm.toggleExpand(row.id) }) {
                                Text(if (state.expandedId == row.id) "▾ hide" else "▸ details")
                            }
                            AnimatedVisibility(visible = state.expandedId == row.id) {
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
            }
        }
        if (state.flash.isNotEmpty()) {
            Text(state.flash, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}