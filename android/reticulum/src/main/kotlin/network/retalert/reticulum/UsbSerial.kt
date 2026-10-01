package network.retalert.reticulum

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A USB serial device attached to the phone (a Meshtastic node, an RNode…). */
data class UsbSerialDevice(
    /** "vid:pid" in hex, as stored in the interface config. */
    val spec: String,
    val label: String,
    val hasPermission: Boolean,
    val device: UsbDevice,
)

/**
 * USB serial over OTG, via usb-serial-for-android (CDC-ACM for nRF52 /
 * ESP32-S3 native USB, plus CP210x, CH34x, FTDI…). Opens at 115200 8N1 — the
 * speed of both the Meshtastic serial API and RNode firmware.
 */
object UsbSerial {
    const val ACTION_PERMISSION = "network.retalert.action.USB_PERMISSION"
    private const val BAUD = 115_200

    fun specOf(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

    fun list(context: Context): List<UsbSerialDevice> {
        val usb = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { drv ->
            val d = drv.device
            val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB serial device" }
            UsbSerialDevice(specOf(d), "$name (${specOf(d)})", usb.hasPermission(d), d)
        }
    }

    /** Ask Android for access to [device]; the answer arrives as [ACTION_PERMISSION]. */
    fun requestPermission(context: Context, device: UsbDevice) {
        val usb = context.getSystemService(UsbManager::class.java) ?: return
        // The system fills in extras, so the PendingIntent must be mutable on 12+.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
        usb.requestPermission(device, pi)
    }

    /** Open the device matching [spec] (empty = the first one). Throws
     *  [IOException] with a user-facing reason. */
    fun open(context: Context, spec: String): UsbStreams {
        val usb = context.getSystemService(UsbManager::class.java) ?: throw IOException("this phone has no USB host support")
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        val drv = drivers.firstOrNull { spec.isEmpty() || specOf(it.device) == spec.lowercase() }
            ?: throw IOException(if (spec.isEmpty()) "no USB serial device plugged in" else "USB device $spec not plugged in")
        if (!usb.hasPermission(drv.device)) throw IOException("USB permission needed — tap Allow USB in the Interfaces screen")
        val conn = usb.openDevice(drv.device) ?: throw IOException("could not open the USB device")
        val port = drv.ports.first()
        try {
            port.open(conn)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR on: native-USB boards (nRF52) only send once a host asserts it.
            // RTS off so ESP32 auto-reset circuits don't hold the chip in reset.
            runCatching { port.dtr = true }
            runCatching { port.rts = false }
        } catch (e: Exception) {
            runCatching { port.close() }
            throw IOException("could not open the USB serial port: ${e.message}", e)
        }
        return UsbStreams(port)
    }
}

/** Blocking streams over a [UsbSerialPort]. One reader thread at a time. */
class UsbStreams(private val port: UsbSerialPort) : Closeable {
    @Volatile private var closed = false
    private val readBuf = ByteArray(4096)
    private var pending = ByteArray(0)
    private var pendingPos = 0

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pendingPos >= pending.size) {
                if (closed) return -1
                // Short timeout so close() is noticed; 0 bytes just means "nothing yet".
                val n = try { port.read(readBuf, READ_TIMEOUT_MS) } catch (e: IOException) {
                    if (closed) return -1 else throw e
                }
                if (n > 0) { pending = readBuf.copyOf(n); pendingPos = 0 }
            }
            val n = minOf(len, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, b, off, n)
            pendingPos += n
            return n
        }

        override fun close() = this@UsbStreams.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("USB port closed")
            port.write(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len), WRITE_TIMEOUT_MS)
        }
        override fun close() = this@UsbStreams.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { port.close() }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 2_000
    }
}
