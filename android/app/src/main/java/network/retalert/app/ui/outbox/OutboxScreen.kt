@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.domain.AckState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutboxScreen(vm: OutboxViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Outbox") }, actions = {
            androidx.compose.material3.TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
    ) { inner ->
        if (state.rows.isEmpty()) {
            Text(
                "(nothing sent yet)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(inner).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.alertId }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("[${row.severity}] ${row.alertId.take(8)}…", style = MaterialTheme.typography.labelSmall)
                            Text(row.text.take(80), style = MaterialTheme.typography.bodySmall)
                            FlowRow(
                                Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (row.states.isEmpty()) {
                                    AssistChip(onClick = {}, label = { Text("(no recipients)") })
                                }
                                row.states.forEach { rs ->
                                    AssistChip(onClick = {}, label = { Text("${rs.recipient.take(6)}…=${rs.state}") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Color bucket for a state (kept for parity; chip uses default coloring). */
@Suppress("unused")
private fun stateBucket(s: String): String = when (s) {
    AckState.SENT -> "active"
    AckState.DELIVERED -> "delivered"
    AckState.ACKED, AckState.REPLIED -> "done"
    AckState.FAILED -> "failed"
    else -> "unknown"
}