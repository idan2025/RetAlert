package network.retalert.updater

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** UpdateChecker against a fake releases JSON seam. No network. */
class UpdateCheckerTest {

    // Releases (oldest -> newest mixed): rc2(0.1.0-rc2), 0.1.0, 0.1.5-rc1 (no apk), 0.2.0
    private val json = """
        [
          {"tag_name":"v0.1.0-rc2","name":"rc2","prerelease":true,"assets":[{"name":"RetAlert.apk","browser_download_url":"https://x/RetAlert-rc2.apk","size":1}]},
          {"tag_name":"v0.1.0","name":"first","prerelease":false,"assets":[{"name":"RetAlert.apk","browser_download_url":"https://x/RetAlert-0.1.0.apk","size":4}]},
          {"tag_name":"v0.1.5-rc1","name":"rc","prerelease":true,"assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3}]},
          {"tag_name":"v0.2.0","name":"stable","prerelease":false,"assets":[{"name":"RetAlert.apk","browser_download_url":"https://x/RetAlert-0.2.0.apk","size":2}]}
        ]
    """.trimIndent()

    private val checker = UpdateChecker("idan2025/RetAlert") { json }

    @Test
    fun `picks newest applicable release`() = runTest {
        val rel = checker.latestNewerThan(Semver.parse("v0.1.0-rc2"), includePrereleases = true)
        assertEquals("v0.2.0", rel?.tag_name)
    }

    @Test
    fun `ignores non-apk assets`() = runTest {
        // 0.1.5-rc1 only has notes.txt -> excluded; current 0.1.0 -> newest is 0.2.0
        val rel = checker.latestNewerThan(Semver.parse("v0.1.0"), includePrereleases = true)
        assertEquals("v0.2.0", rel?.tag_name)
    }

    @Test
    fun `returns null when current is newest`() = runTest {
        assertNull(checker.latestNewerThan(Semver.parse("v0.2.0"), includePrereleases = true))
    }

    @Test
    fun `respects includePrereleases flag off`() = runTest {
        // current rc2; prereleases off skips v0.1.0-rc2 and v0.1.5-rc1; newest stable > rc2 is v0.2.0
        val rel = checker.latestNewerThan(Semver.parse("v0.1.0-rc2"), includePrereleases = false)
        assertEquals("v0.2.0", rel?.tag_name)
    }

    @Test
    fun `prereleases off and no stable newer returns null`() = runTest {
        val onlyPre = """
            [{"tag_name":"v0.2.0-rc1","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u","size":1}]}]
        """.trimIndent()
        val c = UpdateChecker("x") { onlyPre }
        assertNull(c.latestNewerThan(Semver.parse("v0.1.0"), includePrereleases = false))
    }

    @Test
    fun `parse failure returns null`() = runTest {
        val c = UpdateChecker("x") { "not json" }
        assertNull(c.latestNewerThan(Semver.parse("v0.1.0"), includePrereleases = true))
    }
}