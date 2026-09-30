@file:OptIn(ExperimentalMaterial3Api::class)

package network.retalert.app.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val CANNED_REPLIES = listOf("Acknowledged", "On my way", "Cannot help", "Stand by")

@Composable
fun InboxScreen(vm: InboxViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var replyFor by remember { mutableStateOf<InboxRow?>(null) }

    // Inbound alerts land in Room from the mesh thread; poll while visible.
    LaunchedEffect(Unit) {
        while (true) { vm.refresh(); delay(3000) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inbox") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (state.flash.isNotEmpty()) {
                Text(state.flash, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (state.entries.isEmpty()) {
                Text(
                    "(inbox empty)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.entries, key = { it.alertId }) { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                val from = e.sourceName.ifBlank { e.sourceHash.take(8) + "…" }
                                Text("[${e.severity}] from $from", style = MaterialTheme.typography.labelSmall)
                                Text(e.text.take(280), style = MaterialTheme.typography.bodyMedium)
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { vm.ackAlert(e) }) { Text("Ack") }
                                    OutlinedButton(onClick = { replyFor = e }) { Text("Reply") }
                                    TextButton(onClick = { vm.remove(e) }) { Text("Dismiss") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    replyFor?.let { row ->
        ReplyDialog(
            onDismiss = { replyFor = null },
            onReply = { text ->
                vm.reply(row, text)
                replyFor = null
            },
        )
    }
}

@Composable
private fun ReplyDialog(onDismiss: () -> Unit, onReply: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reply") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("reply text (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CANNED_REPLIES.forEach { canned ->
                    TextButton(onClick = { text = canned }) { Text(canned) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onReply(text) }) { Text("Send reply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
