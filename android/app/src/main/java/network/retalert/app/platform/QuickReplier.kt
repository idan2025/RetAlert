package network.retalert.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import network.retalert.domain.IncomingMessage
import network.retalert.reticulum.ReticulumEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * One-tap reply to an incoming alert ("On my way", "Can't come"…), shared by
 * the full-screen pop-up, the notification actions and the Inbox: sends the
 * reply, stops the alarm and marks the notification as answered.
 */
@Singleton
class QuickReplier @Inject constructor(
    private val engine: ReticulumEngine,
    private val alarm: AlarmPlayer,
    private val notifier: AlertNotifier,
    private val myReplies: MyReplies,
) {
    fun reply(msg: IncomingMessage, text: String) {
        notifier.markReplied(msg, text)
        if (!isTest(msg)) myReplies.set(msg.alertId, text)
        alarm.stop()
        if (isTest(msg)) return // the test alarm has no real sender
        thread(name = "retalert-quick-reply", isDaemon = true) {
            runCatching { engine.reply(msg.alertId, msg.sourceHash, text) }
                .onFailure { Log.w(TAG, "quick reply failed", it) }
        }
    }

    companion object {
        private const val TAG = "RetAlert/QuickReply"
        const val TEST_ALERT_ID = "test-alarm"
        fun isTest(msg: IncomingMessage) = msg.alertId == TEST_ALERT_ID
    }
}

/** A quick-reply action on the alert notification. */
@AndroidEntryPoint
class QuickReplyReceiver : BroadcastReceiver() {
    @Inject lateinit var replier: QuickReplier

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val msg = IncomingMessage(
            sourceHash = intent.getStringExtra(EXTRA_SOURCE).orEmpty(),
            text = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            timestamp = System.currentTimeMillis() / 1000.0,
            kind = "alert",
            severity = intent.getStringExtra(EXTRA_SEVERITY).orEmpty(),
            alertId = intent.getStringExtra(EXTRA_ALERT_ID) ?: return,
        )
        replier.reply(msg, text)
    }

    companion object {
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SEVERITY = "severity"
        const val EXTRA_BODY = "body"
        const val EXTRA_TEXT = "reply"
    }
}
