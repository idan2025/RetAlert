package network.retalert.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Resolves the newest applicable GitHub release for a repo.
 *  Pure core; HTTP is injected via [fetch] so tests pass a fake JSON string. */
class UpdateChecker(
    private val repo: String,                       // "owner/name"
    private val fetch: suspend (String) -> String,   // URL -> response body
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Return the highest release newer than [current] that ships an APK asset,
     *  or null if none applies. When [includePrereleases] is false, releases whose
     *  tag carries a prerelease marker are skipped. Rate-limit 403/404 and parse
     *  failures are swallowed: returns null with a log line. */
    suspend fun latestNewerThan(current: Semver, includePrereleases: Boolean): GithubRelease? {
        val body = try {
            fetch("https://api.github.com/repos/$repo/releases")
        } catch (e: Exception) {
            Log.w(TAG, "fetch releases failed: ${e.message}")
            return null
        }
        val releases = try {
            json.decodeFromString<List<GithubRelease>>(body)
        } catch (e: Exception) {
            Log.w(TAG, "parse releases failed: ${e.message}")
            return null
        }
        return releases
            .asSequence()
            .filter { includePrereleases || !it.version.isPrerelease }
            .filter { it.apkAsset != null }
            .filter { it.version > current }
            .maxByOrNull { it.version }
    }

    companion object {
        private const val TAG = "UpdateChecker"

        /** Real HTTP GET using HttpURLConnection with a RetAlert User-Agent. */
        suspend fun httpFetch(url: String): String = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RetAlert-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "HTTP $code for $url")
                    throw java.io.IOException("HTTP $code")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}