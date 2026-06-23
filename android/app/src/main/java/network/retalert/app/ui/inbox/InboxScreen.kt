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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val CANNED_REPLIES = listOf("Acknowledged", "On my way", "Cannot help", "Stand by")

@Composable
fun InboxScreen(vm: InboxViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var replyFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inbox") }, actions = {
            androidx.compose.material3.TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
    ) { inner ->
        if (state.entries.isEmpty()) {
            Text(
                "(inbox empty)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(inner).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.entries, key = { it.alertId }) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("[${e.severity}] ${e.alertId.take(8)}…", style = MaterialTheme.typography.labelSmall)
                            Text(e.text.take(140), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { vm.ackAlert(e.alertId) }) { Text("Ack") }
                                OutlinedButton(onClick = { replyFor = e.alertId }) { Text("Reply") }
                            }
                        }
                    }
                }
            }
        }
        Text(state.flash, modifier = Modifier.padding(8.dp))
    }

    replyFor?.let { id ->
        ReplyDialog(
            onDismiss = { replyFor = null },
            onReply = { text ->
                vm.reply(id, text)
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CANNED_REPLIES.take(3).forEach { canned ->
                        TextButton(onClick = { text = canned }) { Text(canned) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onReply(text) }) { Text("Send reply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}