package network.retalert.reticulum

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import network.retalert.domain.IfaceConfig
import network.retalert.domain.IfaceParam
import network.retalert.domain.IfaceType
import network.retalert.domain.MeshLink
import network.retalert.domain.rnodeLink
import network.reticulum.android.ble.AndroidBLEDriver
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.auto.AutoInterface
import network.reticulum.interfaces.ble.BLEInterface
import network.reticulum.interfaces.i2p.I2PInterface
import network.reticulum.interfaces.rnode.RNodeInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.tcp.TCPServerInterface
import network.reticulum.interfaces.udp.UDPInterface
import java.io.File
import java.util.UUID

/**
 * Builds a reticulum-kt [Interface] from a user [IfaceConfig]. The interface
 * is not started. Throws [IllegalStateException] with a user-facing message
 * when the hardware or a permission is missing.
 */
internal class InterfaceFactory(private val context: Context) {

    /** RNS interface name. The prefix encodes the class for tier classification
     *  (see [rnsClass]); the rest is the user's name. */
    fun rnsName(c: IfaceConfig): String = when (c.type) {
        IfaceType.AUTO -> "${ReticulumEngine.AUTO_NAME} ${c.name}"
        IfaceType.TCP_CLIENT -> "${ReticulumEngine.TCP_PREFIX}${c.name}"
        IfaceType.TCP_SERVER -> "TCPServerInterface ${c.name}"
        IfaceType.UDP -> "UDPInterface ${c.name}"
        IfaceType.RNODE -> "RNodeInterface ${c.name}"
        IfaceType.BLE -> "BLEInterface ${c.name}"
        IfaceType.I2P -> "I2PInterface ${c.name}"
        else -> c.name
    }

    fun create(c: IfaceConfig, transportIdentityHash: ByteArray): Interface {
        val name = rnsName(c)
        val netname = c.param(IfaceParam.NETNAME).ifEmpty { null }
        val netkey = c.param(IfaceParam.NETKEY).ifEmpty { null }
        return when (c.type) {
            IfaceType.AUTO -> {
                val group = c.param(IfaceParam.GROUP_ID)
                if (group.isEmpty()) AutoInterface(name = name)
                else AutoInterface(name = name, groupId = group.toByteArray(Charsets.UTF_8))
            }
            IfaceType.TCP_CLIENT -> TCPClientInterface(
                name = name,
                targetHost = c.param(IfaceParam.HOST),
                targetPort = c.intParam(IfaceParam.PORT) ?: error("bad port"),
                keepAlive = true,
                ifacNetname = netname,
                ifacNetkey = netkey,
            )
            IfaceType.TCP_SERVER -> TCPServerInterface(
                name = name,
                bindAddress = c.param(IfaceParam.BIND).ifEmpty { "0.0.0.0" },
                bindPort = c.intParam(IfaceParam.PORT) ?: error("bad port"),
                ifacNetname = netname,
                ifacNetkey = netkey,
            )
            IfaceType.UDP -> {
                val fwd = c.param(IfaceParam.FORWARD_IP)
                UDPInterface(
                    name = name,
                    bindIp = "0.0.0.0",
                    bindPort = c.intParam(IfaceParam.LISTEN_PORT) ?: error("bad listen port"),
                    forwardIp = fwd,
                    forwardPort = c.intParam(IfaceParam.FORWARD_PORT) ?: error("bad forward port"),
                    broadcast = fwd.endsWith(".255"),
                )
            }
            IfaceType.RNODE -> createRNode(name, c)
            IfaceType.BLE -> {
                requireBluetooth(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                val bm = context.getSystemService(BluetoothManager::class.java) ?: error("this phone has no Bluetooth")
                check(bm.adapter?.isEnabled == true) { "Bluetooth is off" }
                BLEInterface(name = name, driver = AndroidBLEDriver(context, bm), transportIdentity = transportIdentityHash)
            }
            IfaceType.I2P -> I2PInterface(
                name = name,
                storagePath = File(context.filesDir, "i2p").apply { mkdirs() }.absolutePath,
                peers = c.param(IfaceParam.PEERS).split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() },
                connectable = c.param(IfaceParam.CONNECTABLE) == "true",
                ifacNetname = netname,
                ifacNetkey = netkey,
            )
            else -> error(IfaceType.REMOVED[c.type] ?: "unknown interface type ${c.type}")
        }
    }

    /**
     * RNode over USB serial or Bluetooth. Over Bluetooth, current RNodes (nRF52,
     * ESP32-S3) only do Bluetooth LE; older ESP32 ones only Classic (SPP). The
     * paired device's type decides. Blocks while connecting.
     */
    @SuppressLint("MissingPermission")
    private fun createRNode(name: String, c: IfaceConfig): Interface {
        if (rnodeLink(c) == MeshLink.USB) {
            val usb = UsbSerial.open(context, c.param(IfaceParam.USB_DEVICE))
            return rnodeOver(name, c, usb.input, usb.output)
        }
        requireBluetooth(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("this phone has no Bluetooth")
        check(adapter.isEnabled) { "Bluetooth is off" }
        val device = adapter.getRemoteDevice(c.param(IfaceParam.BT_ADDRESS).uppercase())
        if (device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            val ble = RNodeBle.open(context, device.address)
            return rnodeOver(name, c, ble.input, ble.output)
        }
        runCatching { adapter.cancelDiscovery() }
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        runCatching { socket.connect() }.onFailure {
            runCatching { socket.close() }
            error("could not connect to ${device.name ?: device.address} — is it on and paired?")
        }
        // Closing the streams on detach also closes the socket.
        return rnodeOver(name, c, socket.inputStream, socket.outputStream)
    }

    private fun rnodeOver(name: String, c: IfaceConfig, input: java.io.InputStream, output: java.io.OutputStream): Interface =
        RNodeInterface(
            name = name,
            inputStream = input,
            outputStream = output,
            frequency = c.longParam(IfaceParam.FREQUENCY) ?: error("bad frequency"),
            bandwidth = c.longParam(IfaceParam.BANDWIDTH) ?: error("bad bandwidth"),
            txPower = c.intParam(IfaceParam.TX_POWER) ?: error("bad TX power"),
            spreadingFactor = c.intParam(IfaceParam.SF) ?: error("bad spreading factor"),
            codingRate = c.intParam(IfaceParam.CR) ?: error("bad coding rate"),
        )

    private fun requireBluetooth(vararg perms: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val missing = perms.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        check(missing.isEmpty()) { "Bluetooth permission not granted" }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
