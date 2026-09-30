package network.retalert.updater

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** ApkDownloader pure download logic against a fake byte seam. */
class ApkDownloaderTest {

    @Test
    fun `writes bytes to file with right name`() = runTest {
        val dir = Files.createTempDirectory("updater").toFile()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val dl = ApkDownloader(dir) { _ -> data }
        val f = dl.download("https://x/RetAlert-0.2.0.apk", "0.2.0")
        assertTrue(f.exists())
        assertEquals("0.2.0.apk", f.name)
        assertArrayEquals(data, f.readBytes())
        // temp part must not linger
        assertEquals(1, dir.listFiles { _, n -> n.endsWith(".apk") }!!.size)
        dir.deleteRecursively()
    }
}