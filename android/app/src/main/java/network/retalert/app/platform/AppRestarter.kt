package network.retalert.app.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import network.retalert.app.MainActivity

/**
 * Restarts the whole app process. Used when the Reticulum connection mode
 * changes (shared instance on/off, port): reticulum-kt and LXMF-kt keep
 * process-wide singleton state, and restarting them in-process left LXMF
 * outbound delivery wedged. A fresh process brings every stack up clean —
 * the same approach Columba/Sideband take. Pending alerts survive: they are
 * replayed from the outbox on start.
 */
object AppRestarter {
    fun restart(context: Context) {
        val intent = Intent(context, PhoenixActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PhoenixActivity.EXTRA_PID, Process.myPid())
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

/** Trampoline in its own `:phoenix` process: waits for the main process to
 *  be gone, relaunches [MainActivity] (which starts the mesh service), exits. */
class PhoenixActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0) Process.killProcess(pid)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object { const val EXTRA_PID = "main_pid" }
}
