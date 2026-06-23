package network.retalert.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads a release APK asset into app-internal cache.
 *  Pure download logic; bytes are injected via [fetch] so tests pass a fake. */
class ApkDownloader(
    private val baseDir: File,
    private val fetch: suspend (String) -> ByteArray,   // asset URL -> bytes
) {
    /** Download [assetUrl] into `<baseDir>/<version>.apk` via a temp file,
     *  then atomically rename. Returns the final file. */
    suspend fun download(assetUrl: String, version: String): File = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        val bytes = fetch(assetUrl)
        val target = File(baseDir, "$version.apk")
        val tmp = File(baseDir, "$version.apk.part")
        tmp.outputStream().use { it.write(bytes) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "downloaded ${bytes.size} bytes -> ${target.absolutePath}")
        target
    }

    companion object {
        private const val TAG = "ApkDownloader"

        /** Real HTTP GET returning the full response body bytes. */
        suspend fun httpFetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RetAlert-Updater")
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            try {
                val code = conn.responseCode
                if (code != 200) throw java.io.IOException("HTTP $code")
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}