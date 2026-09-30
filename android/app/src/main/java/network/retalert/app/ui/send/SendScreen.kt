@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import network.retalert.domain.Severity

@Composable
fun SendScreen(vm: SendViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshSuggestions() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Send alert") }) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.target,
                onValueChange = vm::onTarget,
                label = { Text("recipient (contact / group / hash)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (state.suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.suggestions.forEach { name ->
                        AssistChip(onClick = { vm.onTarget(name) }, label = { Text(name) })
                    }
                }
            }

            Text("Severity", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Severity.ALL.forEach { sev ->
                    FilterChip(
                        selected = state.severity == sev,
                        onClick = { vm.onSeverity(sev) },
                        label = { Text(sev) },
                    )
                }
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = vm::onText,
                label = { Text("message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            Button(
                onClick = vm::send,
                enabled = !state.sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.sending) "Sending…" else "Send") }

            if (state.flash.isNotEmpty()) {
                Text(state.flash, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
