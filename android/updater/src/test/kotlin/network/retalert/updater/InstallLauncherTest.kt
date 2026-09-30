package network.retalert.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** InstallLauncher.cleanup — deletes cached APKs, leaves non-apk files alone. */
class InstallLauncherTest {

    @Test
    fun `cleanup deletes cached apks`() {
        val dir = Files.createTempDirectory("upd-cache").toFile()
        File(dir, "0.1.0-rc2.apk").writeBytes(byteArrayOf(1))
        File(dir, "0.2.0.apk").writeBytes(byteArrayOf(2))
        File(dir, "keep.txt").writeText("leftover metadata")

        InstallLauncher.cleanup(dir)

        val remaining = dir.listFiles()!!
        assertEquals(1, remaining.size)
        assertTrue(File(dir, "keep.txt").exists())
        assertFalse(File(dir, "0.1.0-rc2.apk").exists())
        assertFalse(File(dir, "0.2.0.apk").exists())
        dir.deleteRecursively()
    }

    @Test
    fun `cleanup on empty dir is a no-op`() {
        val dir = Files.createTempDirectory("upd-empty").toFile()
        InstallLauncher.cleanup(dir)
        assertEquals(0, dir.listFiles()!!.size)
        dir.deleteRecursively()
    }
}