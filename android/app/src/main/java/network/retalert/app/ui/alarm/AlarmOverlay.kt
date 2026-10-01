package network.retalert.app.ui.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import network.retalert.domain.IncomingMessage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.app.platform.AlarmPlayer

/** Full-screen emergency alert, on top of every screen, while an incoming
 *  alert is ringing or shown (see [AlarmPlayer.shown]). [replies] are one-tap
 *  answers ("On my way"…) sent through [onReply]. */
@Composable
fun AlarmOverlay(
    alarm: AlarmPlayer,
    replies: List<String> = emptyList(),
    onReply: (IncomingMessage, String) -> Unit = { _, _ -> },
    onOpenChat: ((IncomingMessage) -> Unit)? = null,
) {
    val shown by alarm.shown.collectAsStateWithLifecycle()
    val ringing by alarm.ringing.collectAsStateWithLifecycle()
    val m = shown ?: return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = AlertRed, contentColor = Color.White) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Everything but Stop scrolls, so a long message or several
                // replies can never push the Stop button off the screen.
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.height(72.dp).fillMaxWidth())
                    Text("ALERT", fontSize = 44.sp, fontWeight = FontWeight.Black)
                    Text(m.severity.uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(m.text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        if (replies.isEmpty()) "From ${m.sourceHash.take(8)}… — open the Inbox to reply."
                        else "From ${m.sourceHash.take(8)}… — tap a reply to answer and stop the alarm.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    replies.forEach { r ->
                        OutlinedButton(
                            onClick = { onReply(m, r) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            border = BorderStroke(2.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text(r, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    if (onOpenChat != null) {
                        TextButton(onClick = { onOpenChat(m) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                            Text("Open chat", fontSize = 18.sp)
                        }
                    }
                }
                Button(
                    onClick = { alarm.stop() },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AlertRed),
                ) {
                    Text(if (ringing != null) "Stop alarm" else "Dismiss", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private val AlertRed = Color(0xFFB3261E)
