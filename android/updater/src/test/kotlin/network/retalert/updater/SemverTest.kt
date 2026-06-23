package network.retalert.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Semver parse + compare behavior. Pure, no Android. */
class SemverTest {

    @Test
    fun `ordering honors prerelease precedence`() {
        assertTrue(Semver.parse("v0.1.0-rc2") < Semver.parse("v0.1.0"), "rc2 < stable 0.1.0")
        assertTrue(Semver.parse("v0.1.0") < Semver.parse("v0.2.0-rc1"), "0.1.0 < 0.2.0-rc1")
        assertTrue(Semver.parse("v0.2.0-rc1") < Semver.parse("v0.2.0"), "0.2.0-rc1 < 0.2.0")
        assertTrue(Semver.parse("v0.1.0-rc2") < Semver.parse("v0.2.0"), "0.1.0-rc2 < 0.2.0")
    }

    @Test
    fun `equal tags compare equal`() {
        assertEquals(0, Semver.parse("v0.1.0-rc2").compareTo(Semver.parse("0.1.0-rc2")))
        assertEquals(Semver.parse("v0.2.0"), Semver.parse("0.2.0"))
    }

    @Test
    fun `prerelease detection`() {
        assertTrue(Semver.parse("v0.1.0-rc2").isPrerelease)
        assertTrue(Semver.parse("v0.2.0-rc1").isPrerelease)
        assertFalse(Semver.parse("v0.1.0").isPrerelease)
        assertFalse(Semver.parse("v0.2.0").isPrerelease)
    }

    @Test
    fun `prerelease numeric versus alphanumeric precedence`() {
        // beta < beta.2 < beta.11 < rc.1 (semver spec chain)
        assertTrue(Semver.parse("1.0.0-beta") < Semver.parse("1.0.0-beta.2"))
        assertTrue(Semver.parse("1.0.0-beta.2") < Semver.parse("1.0.0-beta.11"))
        assertTrue(Semver.parse("1.0.0-beta.11") < Semver.parse("1.0.0-rc.1"))
        // numeric identifier is lower than alphanumeric at same position
        assertTrue(Semver.parse("1.0.0-alpha.1") < Semver.parse("1.0.0-alpha.beta"))
    }

    @Test
    fun `toString roundtrips`() {
        assertEquals("0.1.0-rc2", Semver.parse("v0.1.0-rc2").toString())
        assertEquals("0.2.0", Semver.parse("v0.2.0").toString())
    }
}