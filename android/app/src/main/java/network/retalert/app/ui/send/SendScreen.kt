@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.send

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.app.platform.media.PhotoCaptureView
import network.retalert.domain.Severity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendScreen(vm: SendViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var capturingPhoto by remember { mutableStateOf(false) }

    // Permission launchers. Camera is requested before opening the capture surface;
    // RECORD_AUDIO before starting the recorder.
    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) capturingPhoto = true }

    val audioPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startRecording() }

    if (capturingPhoto) {
        PhotoCaptureView(
            onCaptured = { bytes ->
                vm.onPhotoCaptured(bytes)
                capturingPhoto = false
            },
            onCancel = { capturingPhoto = false },
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Send alert") }) },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.target,
                onValueChange = vm::onTarget,
                label = { Text("recipient (contact / group / hash)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

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

            // Attached photo thumbnail (rendered from the raw JPEG bytes).
            state.attachedPhoto?.let { bytes ->
                val bitmap = remember(bytes) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Attached photo",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
                OutlinedButton(
                    onClick = vm::clearPhoto,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove photo") }
            }

            // Attach photo button — opens the CameraX capture surface.
            OutlinedButton(
                onClick = { cameraPerm.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Attach photo") }

            // Record audio button — toggles the opus recorder.
            Button(
                onClick = {
                    if (state.isRecording) vm.stopRecording()
                    else audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.isRecording) "Stop audio (${state.audioChunks})" else "Record audio") }

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