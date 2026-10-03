package network.retalert.reticulum

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An RNode over Bluetooth LE: the firmware exposes its serial port as the Nordic
 * UART Service, so this is a byte stream like [UsbStreams]. Notifications on TX
 * feed [input]; [output] writes to RX in MTU-sized chunks, each waiting for its
 * write callback (GATT allows one operation at a time). The device must be
 * paired in Android's Bluetooth settings first. When the link drops, [input]
 * reports end of stream, so the interface goes down and the engine reconnects.
 */
@SuppressLint("MissingPermission")
class RNodeBle private constructor(private val context: Context, private val address: String) : Closeable {

    @Volatile private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var pending: CompletableFuture<Any?>? = null
    private val opLock = Object()
    private val connected = CompletableFuture<Unit>()
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    @Volatile private var chunk = DEFAULT_MTU - ATT_OVERHEAD

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = IOException("Bluetooth disconnected (status $status)")
                connected.completeExceptionally(e)
                pending?.completeExceptionally(e)
                close()
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { pending?.complete(mtu) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { complete(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { complete(status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == NUS_TX && value.isNotEmpty()) incoming.put(value)
        }
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && c.uuid == NUS_TX && value != null && value.isNotEmpty()) {
                incoming.put(value.copyOf())
            }
        }

        private fun complete(status: Int) {
            val p = pending ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) p.complete(Unit)
            else p.completeExceptionally(IOException("GATT error $status"))
        }
    }

    val input: InputStream = object : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) {
                if (closed && incoming.isEmpty()) return -1
                buf = incoming.poll(READ_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                pos = 0
                if (buf === EOF) return -1
            }
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = (buf.size - pos).coerceAtLeast(0)
        override fun close() = this@RNodeBle.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            val end = off + len
            while (at < end) {
                val n = minOf(chunk, end - at)
                writeChunk(b.copyOfRange(at, at + n))
                at += n
            }
        }

        override fun close() = this@RNodeBle.close()
    }

    private fun connect() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("this phone has no Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address.uppercase())
        val name = device.name ?: device.address
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("could not start a Bluetooth connection")
            connected.get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            setUp(gatt!!)
        } catch (e: Exception) {
            close()
            val why = (if (e is ExecutionException) e.cause else e)?.message
            throw IOException("could not connect to $name over Bluetooth LE — is it on, in range and paired?" +
                (why?.let { " ($it)" } ?: ""), e)
        }
    }

    /** MTU, the UART service, and notifications on its TX characteristic. */
    private fun setUp(g: BluetoothGatt) {
        runCatching { op { g.requestMtu(REQUESTED_MTU) } as Int }
            .onSuccess { chunk = (it - ATT_OVERHEAD).coerceAtLeast(DEFAULT_MTU - ATT_OVERHEAD) }
        op { g.discoverServices() }
        val svc = g.getService(NUS_SERVICE) ?: throw IOException("not an RNode (no Bluetooth serial service)")
        rx = svc.getCharacteristic(NUS_RX) ?: throw IOException("RNode serial RX missing")
        val tx = svc.getCharacteristic(NUS_TX) ?: throw IOException("RNode serial TX missing")
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(CCCD) ?: return
        op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun writeChunk(data: ByteArray) {
        val g = gatt ?: throw IOException("RNode not connected")
        val c = rx ?: throw IOException("RNode not connected")
        op {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    /** Run one GATT operation and wait for its callback. */
    private fun op(start: () -> Boolean): Any? = synchronized(opLock) {
        if (closed) throw IOException("RNode link closed")
        val f = CompletableFuture<Any?>()
        pending = f
        try {
            if (!start()) throw IOException("Bluetooth busy or disconnected")
            f.get(OP_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IOException("Bluetooth operation timed out", e)
        } finally {
            pending = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        incoming.put(EOF)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        /** Connects and sets up the serial service; blocks, throws [IOException] on failure. */
        fun open(context: Context, address: String): RNodeBle = RNodeBle(context, address).also { it.connect() }

        private const val CONNECT_TIMEOUT_S = 20L
        private const val OP_TIMEOUT_S = 10L
        private const val READ_POLL_MS = 500L
        private const val REQUESTED_MTU = 512
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
        private val EOF = ByteArray(0)
        private val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // phone -> RNode
        private val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // RNode -> phone
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
