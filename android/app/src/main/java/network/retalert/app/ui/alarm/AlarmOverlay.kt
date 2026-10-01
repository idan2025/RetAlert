package network.retalert.app.ui.alarm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.app.platform.AlarmPlayer

/** Shown on top of every screen while an incoming alert is ringing. */
@Composable
fun AlarmOverlay(alarm: AlarmPlayer) {
    val msg by alarm.ringing.collectAsStateWithLifecycle()
    val m = msg ?: return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("ALERT · ${m.severity.uppercase()}", color = MaterialTheme.colorScheme.error) },
        text = {
            Column {
                Text(m.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("From ${m.sourceHash.take(8)}… — open Inbox to reply.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { alarm.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop alarm") }
        },
    )
}
