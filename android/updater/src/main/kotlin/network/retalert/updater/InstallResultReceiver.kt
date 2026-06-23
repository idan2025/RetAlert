package network.retalert.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Receives the PackageInstaller commit result. On success OR failure, deletes the
 *  cached APK and prunes any stale `*.apk` leftovers in the app cache — no leftover files. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != InstallLauncher.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "install success")
            else -> Log.w(TAG, "install failed: status=$status msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
        // Regardless of outcome: clean the cache so no APK is left behind.
        InstallLauncher.cleanup(context.cacheDir)
    }

    companion object { private const val TAG = "InstallResultReceiver" }
}