package network.retalert.updater

import android.util.Log
import network.retalert.domain.AppVersion
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** User setting governing whether prerelease versions are offered as updates.
 *  The app's Settings adapter implements this; the updater module stays decoupled
 *  from the concrete Settings store to avoid a module cycle. */
interface UpdateSettings {
    val includePrereleases: Boolean
}

/** Orchestrates check -> download -> install -> cleanup.
 *  @Inject-able Hilt singleton; depends on the injected [ApkInstaller] seam. */
@Singleton
class UpdateCoordinator @Inject constructor(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
) {
    /** Check for an applicable update; return the release if one exists, else null. */
    suspend fun check(settings: UpdateSettings): GithubRelease? =
        checker.latestNewerThan(Semver.parse(AppVersion.NAME), settings.includePrereleases)

    /** Download the APK for [release] into app-internal cache; return the file. */
    suspend fun download(release: GithubRelease): File {
        val asset = release.apkAsset ?: error("release ${release.tag_name} has no apk asset")
        return downloader.download(asset.browser_download_url, release.version.toString())
    }

    /** Install the cached [apk] via PackageInstaller. Cleanup runs on the
     *  install-result callback (see [InstallResultReceiver]). */
    fun install(apk: File) = installer.install(apk)

    /** Full pipeline: check -> download -> install. Returns true iff an update was
     *  committed to the installer. */
    suspend fun runIfNeeded(settings: UpdateSettings): Boolean {
        val release = check(settings) ?: return false
        val apk = try {
            download(release)
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            return false
        }
        install(apk)
        return true
    }

    companion object { private const val TAG = "UpdateCoordinator" }
}