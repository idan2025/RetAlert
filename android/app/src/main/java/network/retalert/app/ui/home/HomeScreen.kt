@file:OptIn(ExperimentalMaterial3Api::class)

package network.retalert.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onCustomAlert: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("RetAlert") }) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.meshStatus, style = MaterialTheme.typography.labelMedium)
            Text("Own delivery hash", style = MaterialTheme.typography.labelSmall)
            SelectionContainer {
                Text(
                    state.ownHash.ifEmpty { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.interfaces.isEmpty()) {
                    AssistChip(onClick = {}, label = { Text("no interfaces") })
                }
                state.interfaces.forEach {
                    AssistChip(
                        onClick = {},
                        label = { Text("${it.tier}:${it.name}" + if (it.online) "" else " (down)") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            HoldToConfirmPanic(
                label = "PANIC",
                sublabel = "hold to confirm",
                progress = state.firing,
            ) { vm.panic() }

            if (state.flash.isNotEmpty()) {
                Text(state.flash, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.share.active) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sharing your live location", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("with ${state.share.recipients.size} contact(s) · ${state.share.updatesSent} updates sent", style = MaterialTheme.typography.bodySmall)
                            if (state.share.problem.isNotEmpty()) {
                                Text(state.share.problem, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(onClick = onOpenMap) { Text("Map") }
                        Button(
                            onClick = vm::stopSharing,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Stop") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCustomAlert, modifier = Modifier.weight(1f)) { Text("Custom alert") }
                OutlinedButton(onClick = onOpenMap, modifier = Modifier.weight(1f)) { Text("Share location") }
            }

            Text("Status feed", style = MaterialTheme.typography.titleSmall)
            if (state.feed.isEmpty()) {
                Text("(no activity yet)", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.feed, key = { it.id + it.kind }) { item ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    "${if (item.kind == "sent") "↑" else "↓"} ${item.id.take(8)}…",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(item.line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A panic button that requires a sustained hold to fire (hold-to-confirm). */
@Composable
private fun HoldToConfirmPanic(
    label: String,
    sublabel: String,
    progress: Boolean,
    onConfirm: () -> Unit,
) {
    val holdMs = 1200
    val anim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }

    LaunchedEffect(progress) { if (progress) anim.snapTo(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // A plain Surface, not a Button: a Button's own click handling consumes
        // the press before this gesture detector would see it.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { role = Role.Button; contentDescription = "$label, $sublabel" }
                .pointerInput(Unit) {
                    // Fire only if the press is held for the full duration;
                    // releasing early cancels.
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val job = scope.launch {
                                anim.snapTo(0f)
                                anim.animateTo(1f, tween(holdMs))
                                onConfirm()
                            }
                            tryAwaitRelease()
                            job.cancel()
                            holding = false
                            anim.snapTo(0f)
                        },
                    )
                },
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = CircleShape,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(if (holding) "firing…" else sublabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (holding) {
            LinearProgressIndicator(
                progress = { anim.value },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}