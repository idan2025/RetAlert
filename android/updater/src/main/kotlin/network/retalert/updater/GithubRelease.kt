package network.retalert.updater

import kotlinx.serialization.Serializable

/** One downloadable asset attached to a GitHub release. */
@Serializable
data class GithubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0,
)

/** A GitHub release as returned by `/repos/{owner}/{name}/alerts/releases`.
 *  Unknown JSON fields are ignored so the model never breaks on API additions. */
@Serializable
data class GithubRelease(
    val tag_name: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val html_url: String? = null,
    val assets: List<GithubAsset> = emptyList(),
) {
    /** First asset whose name ends in `.apk`, or null if none. */
    val apkAsset: GithubAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /** Parsed semantic version of [tag_name]. */
    val version: Semver get() = Semver.parse(tag_name)
}