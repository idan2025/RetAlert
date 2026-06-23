package network.retalert.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File
import java.io.FileInputStream

/** Seam over the Android PackageInstaller so install + cleanup logic stays unit-testable.
 *  A unit test can substitute a fake [ApkInstaller] to verify cleanup is invoked. */
interface ApkInstaller {
    /** Install [apk] via a PackageInstaller session. Returns the session id. */
    fun install(apk: File): Int
    /** Delete [apk] and any stale `*.apk` leftovers in [baseDir]. */
    fun cleanup(apk: File? = null)
}

/** Real [ApkInstaller] backed by the system PackageInstaller (signed install). */
class InstallLauncher(
    private val context: Context,
    private val baseDir: File,
) : ApkInstaller {

    override fun install(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(apk.nameWithoutExtension)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = ACTION_INSTALL_RESULT
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "committed session $sessionId for ${apk.absolutePath}")
        return sessionId
    }

    override fun cleanup(apk: File?) {
        apk?.takeIf { it.exists() }?.delete()
        cleanup(baseDir)
    }

    companion object {
        private const val TAG = "InstallLauncher"
        const val ACTION_INSTALL_RESULT = "network.retalert.updater.INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "session_id"

        /** Wipe every `*.apk` in [baseDir]. Used by the install-result receiver. */
        fun cleanup(baseDir: File) {
            baseDir.listFiles { f -> f.extension.equals("apk", ignoreCase = true) }
                ?.forEach { it.delete() }
        }
    }
}