@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package network.retalert.app.ui.interfaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.retalert.app.ui.common.BackButton
import network.retalert.domain.IfaceConfig
import network.retalert.domain.IfaceParam
import network.retalert.domain.IfaceType
import network.retalert.domain.MeshLink
import network.retalert.domain.MeshPort
import network.retalert.domain.defaultParams
import network.retalert.domain.validateIface

@Composable
fun InterfacesScreen(onBack: (() -> Unit)? = null, vm: InterfacesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<IfaceConfig?>(null) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<IfaceConfig?>(null) }
    LaunchedEffect(state.flash) {
        if (state.flash.isNotEmpty()) {
            snackbar.showSnackbar(state.flash)
            vm.clearFlash()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Interfaces") }, navigationIcon = { BackButton(onBack) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add interface") },
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    if (state.sharedInstance) {
                        "RetAlert is attached to a shared instance (Columba, Sideband…), which owns the radios. " +
                            "These interfaces are used only when no shared instance is running — turn \"Use shared instance\" off in Settings to use them now."
                    } else {
                        "How RetAlert's own Reticulum stack reaches other nodes. Changes apply immediately."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.rows.isEmpty()) {
                item { Text("No interfaces — add one to reach the mesh.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rows, key = { it.config.id }) { row ->
                IfaceCard(
                    row,
                    onToggle = { vm.setEnabled(row.config.id, it) },
                    onEdit = { editing = row.config },
                    onDelete = { deleting = row.config },
                )
            }
            item { Text("", Modifier.padding(bottom = 72.dp)) } // clear the FAB
        }
    }

    if (picking) {
        TypePicker(vm.addableTypes(), onDismiss = { picking = false }) { type ->
            picking = false
            editing = IfaceConfig(id = vm.newId(), type = type, name = defaultName(type), params = defaultParams(type))
        }
    }
    editing?.let { cfg ->
        EditDialog(cfg, onDismiss = { editing = null }) { vm.save(it); editing = null }
    }
    deleting?.let { cfg ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${cfg.name}?") },
            confirmButton = { TextButton(onClick = { vm.remove(cfg.id); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun defaultName(type: String) = when (type) {
    IfaceType.AUTO -> "Local network"
    IfaceType.BLE -> "Bluetooth mesh"
    IfaceType.RNODE -> "RNode"
    IfaceType.I2P -> "I2P"
    IfaceType.UDP -> "UDP broadcast"
    IfaceType.TCP_SERVER -> "TCP server"
    IfaceType.MESHTASTIC -> "Meshtastic"
    else -> ""
}

@Composable
private fun IfaceCard(row: IfaceRow, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val color = when {
        row.error -> MaterialTheme.colorScheme.error
        row.online -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit), colors = CardDefaults.cardColors()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.config.name, style = MaterialTheme.typography.titleSmall)
                Text("${IfaceType.label(row.config.type)}${summary(row.config)}", style = MaterialTheme.typography.bodySmall)
                Text(row.status, style = MaterialTheme.typography.bodySmall, color = color)
            }
            Switch(checked = row.config.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove ${row.config.name}") }
        }
    }
}

private fun summary(c: IfaceConfig): String = when (c.type) {
    IfaceType.TCP_CLIENT -> " · ${c.param(IfaceParam.HOST)}:${c.param(IfaceParam.PORT)}"
    IfaceType.TCP_SERVER -> " · port ${c.param(IfaceParam.PORT)}"
    IfaceType.UDP -> " · ${c.param(IfaceParam.LISTEN_PORT)} → ${c.param(IfaceParam.FORWARD_IP)}:${c.param(IfaceParam.FORWARD_PORT)}"
    IfaceType.RNODE -> " · ${(c.longParam(IfaceParam.FREQUENCY) ?: 0) / 1e6} MHz SF${c.param(IfaceParam.SF)}"
    IfaceType.MESHTASTIC -> " · channel ${c.param(IfaceParam.CHANNEL)} · " +
        if (c.param(IfaceParam.LINK) == MeshLink.TCP) c.param(IfaceParam.HOST) else "Bluetooth"
    else -> ""
}

@Composable
private fun TypePicker(types: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add interface") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                types.forEach { t ->
                    ListItem(
                        headlineContent = { Text(IfaceType.label(t)) },
                        supportingContent = { Text(typeHelp(t)) },
                        modifier = Modifier.clickable { onPick(t) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun typeHelp(t: String) = when (t) {
    IfaceType.AUTO -> "Find Reticulum peers on the same Wi-Fi automatically."
    IfaceType.TCP_CLIENT -> "Connect to a Reticulum node or transport over the internet."
    IfaceType.TCP_SERVER -> "Let other nodes connect to this phone (same network, or port-forwarded)."
    IfaceType.UDP -> "Broadcast to Reticulum nodes on the local network over UDP."
    IfaceType.RNODE -> "LoRa radio: an RNode paired over Bluetooth Classic. Works with no internet."
    IfaceType.MESHTASTIC -> "Use a Meshtastic radio as a LoRa link for Reticulum (compatible with RNS_Over_Meshtastic)."
    IfaceType.BLE -> "Phone-to-phone Bluetooth mesh with nearby Reticulum apps (e.g. Columba)."
    IfaceType.I2P -> "Anonymous overlay. Needs an I2P router app with SAM enabled on this phone."
    else -> ""
}

private val BANDWIDTHS = listOf("62500" to "62.5 kHz", "125000" to "125 kHz", "250000" to "250 kHz", "500000" to "500 kHz")

@Composable
private fun EditDialog(initial: IfaceConfig, onDismiss: () -> Unit, onSave: (IfaceConfig) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    val p = remember { mutableStateMapOf<String, String>().apply { putAll(initial.params) } }
    val current = initial.copy(name = name, params = p.toMap())
    val error = validateIface(current)
    val btPerms = bluetoothPermissions(current)
    var btGranted by remember(btPerms) { mutableStateOf(btPerms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        btGranted = res.values.all { it }
    }
    LaunchedEffect(btPerms) { if (btPerms.isNotEmpty() && !btGranted) btLauncher.launch(btPerms.toTypedArray()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(IfaceType.label(initial.type)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name", name) { name = it }
                when (initial.type) {
                    IfaceType.AUTO -> {
                        Field("Group ID (optional)", p[IfaceParam.GROUP_ID].orEmpty(), "reticulum") { p[IfaceParam.GROUP_ID] = it }
                        Hint("Only nodes with the same group ID see each other. Leave empty for the default.")
                    }
                    IfaceType.TCP_CLIENT -> {
                        Field("Host", p[IfaceParam.HOST].orEmpty(), "rns.example.org") { p[IfaceParam.HOST] = it }
                        NumField("Port", p, IfaceParam.PORT)
                        IfacFields(p)
                    }
                    IfaceType.TCP_SERVER -> {
                        Field("Listen address", p[IfaceParam.BIND].orEmpty(), "0.0.0.0") { p[IfaceParam.BIND] = it }
                        NumField("Port", p, IfaceParam.PORT)
                        IfacFields(p)
                    }
                    IfaceType.UDP -> {
                        NumField("Listen port", p, IfaceParam.LISTEN_PORT)
                        Field("Forward address", p[IfaceParam.FORWARD_IP].orEmpty(), "255.255.255.255") { p[IfaceParam.FORWARD_IP] = it }
                        NumField("Forward port", p, IfaceParam.FORWARD_PORT)
                    }
                    IfaceType.RNODE -> {
                        if (btGranted) PairedDevicePicker(p[IfaceParam.BT_ADDRESS].orEmpty(), prefer = Regex("rnode", RegexOption.IGNORE_CASE)) { p[IfaceParam.BT_ADDRESS] = it }
                        else Hint("Allow Bluetooth access to pick your RNode.")
                        Hint("Pair the RNode in Android's Bluetooth settings first. All nodes that should hear each other need the same radio settings.")
                        val mhz = (p[IfaceParam.FREQUENCY]?.toLongOrNull() ?: 0L) / 1_000_000.0
                        var freqText by remember { mutableStateOf(if (mhz > 0) mhz.toString() else "") }
                        Field("Frequency (MHz)", freqText, "869.525", KeyboardType.Decimal) {
                            freqText = it
                            p[IfaceParam.FREQUENCY] = it.toDoubleOrNull()?.let { m -> (m * 1_000_000).toLong().toString() }.orEmpty()
                        }
                        Text("Bandwidth", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BANDWIDTHS.forEach { (v, l) ->
                                FilterChip(selected = p[IfaceParam.BANDWIDTH] == v, onClick = { p[IfaceParam.BANDWIDTH] = v }, label = { Text(l) })
                            }
                        }
                        NumField("Spreading factor (5–12)", p, IfaceParam.SF)
                        NumField("Coding rate (5–8)", p, IfaceParam.CR)
                        NumField("TX power (dBm)", p, IfaceParam.TX_POWER)
                    }
                    IfaceType.MESHTASTIC -> MeshtasticFields(p, btGranted)
                    IfaceType.BLE -> {
                        if (!btGranted) Hint("Allow Bluetooth access (nearby devices) for the mesh to work.")
                        Hint("Connects to nearby phones running a Reticulum BLE mesh (Columba and others). Short range, no internet needed.")
                    }
                    IfaceType.I2P -> {
                        Field("Peers (b32 addresses, comma-separated)", p[IfaceParam.PEERS].orEmpty(), "xxxx.b32.i2p") { p[IfaceParam.PEERS] = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Accept incoming connections", Modifier.weight(1f))
                            Switch(p[IfaceParam.CONNECTABLE] == "true", { p[IfaceParam.CONNECTABLE] = it.toString() })
                        }
                        Hint("Requires an I2P router app (e.g. i2pd) running on this phone with SAM on 127.0.0.1:7656.")
                        IfacFields(p)
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }, enabled = error == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Interface access code: only nodes with the same network name / passphrase can talk. */
@Composable
private fun IfacFields(p: MutableMap<String, String>) {
    Field("Network name (optional)", p[IfaceParam.NETNAME].orEmpty()) { p[IfaceParam.NETNAME] = it }
    OutlinedTextField(
        value = p[IfaceParam.NETKEY].orEmpty(),
        onValueChange = { p[IfaceParam.NETKEY] = it },
        label = { Text("Passphrase (optional)") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@SuppressLint("MissingPermission")
@Composable
private fun PairedDevicePicker(selected: String, prefer: Regex, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember {
        runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.map { (it.name ?: it.address) to it.address }
                ?.sortedByDescending { (n, _) -> prefer.containsMatchIn(n) }
        }.getOrNull().orEmpty()
    }
    Text("Paired device", style = MaterialTheme.typography.bodyMedium)
    if (devices.isEmpty()) Hint("No paired Bluetooth devices. Pair your RNode in Android settings, then come back.")
    devices.forEach { (n, addr) ->
        FilterChip(
            selected = selected.equals(addr, ignoreCase = true),
            onClick = { onPick(addr) },
            label = { Text("$n · $addr") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun bluetoothPermissions(c: IfaceConfig): List<String> = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> emptyList()
    c.type == IfaceType.BLE -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    c.type == IfaceType.RNODE -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
    c.type == IfaceType.MESHTASTIC && c.param(IfaceParam.LINK) == MeshLink.BLE -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
    else -> emptyList()
}

@Composable
private fun MeshtasticFields(p: MutableMap<String, String>, btGranted: Boolean) {
    val link = p[IfaceParam.LINK] ?: MeshLink.BLE
    Text("Connect to the node over", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = link == MeshLink.BLE, onClick = { p[IfaceParam.LINK] = MeshLink.BLE }, label = { Text("Bluetooth") })
        FilterChip(selected = link == MeshLink.TCP, onClick = { p[IfaceParam.LINK] = MeshLink.TCP }, label = { Text("Wi-Fi (TCP)") })
    }
    if (link == MeshLink.BLE) {
        if (btGranted) {
            PairedDevicePicker(p[IfaceParam.BT_ADDRESS].orEmpty(), prefer = Regex("meshtastic|_[0-9a-f]{4}$", RegexOption.IGNORE_CASE)) {
                p[IfaceParam.BT_ADDRESS] = it
            }
        } else Hint("Allow Bluetooth access to pick your node.")
        Hint(
            "Pair the node in Android's Bluetooth settings first (PIN on its screen, or 123456). " +
                "Disconnect the Meshtastic app from it — a node talks to one app at a time.",
        )
    } else {
        Field("Node IP address", p[IfaceParam.HOST].orEmpty(), "192.168.1.50") { p[IfaceParam.HOST] = it }
        NumField("Port", p, IfaceParam.PORT)
        Hint("The node needs Wi-Fi enabled in its Network settings (API port 4403).")
    }
    NumField("Channel index (0 = primary)", p, IfaceParam.CHANNEL)
    Hint(
        "Use a private channel: add a secondary channel (e.g. \"RNS\", random key) with the same name and key on every node " +
            "you want to reach. Reticulum traffic then never touches the public channel, and other users can't read it.",
    )
    NumField("Hop limit (0–7)", p, IfaceParam.HOP_LIMIT)
    Hint("How many Meshtastic nodes may relay each packet. Keep it low (1) so the tunnel doesn't load the wider mesh.")
    Text("Meshtastic port", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = p[IfaceParam.MESH_PORT] != MeshPort.PRIVATE, onClick = { p[IfaceParam.MESH_PORT] = MeshPort.RETICULUM },
            label = { Text("Reticulum tunnel (76)") },
        )
        FilterChip(
            selected = p[IfaceParam.MESH_PORT] == MeshPort.PRIVATE, onClick = { p[IfaceParam.MESH_PORT] = MeshPort.PRIVATE },
            label = { Text("Private app (256)") },
        )
    }
    Hint("All nodes must use the same port. 76 matches the RNS_Over_Meshtastic Python interface.")
}

@Composable
private fun Field(label: String, value: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumField(label: String, p: MutableMap<String, String>, key: String) =
    Field(label, p[key].orEmpty(), keyboard = KeyboardType.Number) { p[key] = it.filter(Char::isDigit).take(10) }

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)
