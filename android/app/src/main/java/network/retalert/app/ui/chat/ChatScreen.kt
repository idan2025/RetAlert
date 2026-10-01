@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.app.ui.common.BackButton
import network.retalert.domain.ChatBubble
import java.text.DateFormat
import java.util.Date

/** Which alert's chat is on screen, so its messages don't also notify. */
object ChatVisibility {
    @Volatile var openAlertId: String? = null
}

@Composable
fun ChatScreen(onBack: (() -> Unit)? = null, vm: ChatViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    // "Open" only while actually on screen: a backgrounded chat must still notify.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state.alertId, lifecycle) {
        fun hide() { if (ChatVisibility.openAlertId == state.alertId) ChatVisibility.openAlertId = null }
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> ChatVisibility.openAlertId = state.alertId
                Lifecycle.Event.ON_PAUSE -> hide()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); hide() }
    }
    LaunchedEffect(state.bubbles.size) { if (state.bubbles.isNotEmpty()) list.animateScrollToItem(state.bubbles.size) }
    LaunchedEffect(state.flash) {
        if (state.flash.isNotEmpty()) { snackbar.showSnackbar(state.flash); vm.clearFlash() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Alert chat")
                        if (state.with.isNotEmpty()) {
                            Text("with ${state.with}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { AlertHeader(state) }
                items(state.bubbles) { b -> Bubble(b, state.names, multi = state.recipients > 1) }
            }
            if (!state.sentByUs) {
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.quickReplies.forEach { q -> AssistChip(onClick = { vm.send(q) }, label = { Text(q) }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                IconButton(onClick = { vm.send(text); text = "" }, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun AlertHeader(s: ChatUiState) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (if (s.sentByUs) "You sent · " else "Alert · ") + s.severity.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(s.alertText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun Bubble(b: ChatBubble, names: Map<String, String>, multi: Boolean) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((b.ts * 1000).toLong()))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (b.outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (b.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!b.outgoing) {
                Text(names[b.peer] ?: (b.peer.take(8) + "…"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(b.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (b.outgoing) "$time · ${status(b, multi)}" else time,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

private fun status(b: ChatBubble, multi: Boolean): String = when {
    b.total > 1 || multi -> when {
        b.failed == b.total -> "not delivered"
        else -> "✓ ${b.delivered}/${b.total}"
    }
    b.delivered == 1 -> "✓ delivered"
    b.failed == 1 -> "not delivered"
    else -> "sending…"
}
