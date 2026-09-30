@file:OptIn(ExperimentalMaterial3Api::class)

package network.retalert.app.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun ContactsScreen(vm: ContactsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var newGroup by remember { mutableStateOf(false) }

    // Announces arrive continuously; keep the discover list fresh while visible.
    LaunchedEffect(Unit) {
        while (true) { vm.refresh(); delay(5000) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Contacts") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("name") },
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = hash,
                    onValueChange = { hash = it },
                    label = { Text("hex hash") },
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    singleLine = true,
                )
                OutlinedButton(onClick = { vm.add(hash, name); name = ""; hash = "" }) { Text("Add") }
            }
            if (state.flash.isNotEmpty()) {
                Text(state.flash, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }

            // Keys are namespaced per section: a hash can legitimately appear
            // as a contact and in a group, and LazyColumn keys must be unique.
            LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "h_contacts") {
                    Text("Contacts (${state.contacts.size})", style = MaterialTheme.typography.titleSmall)
                }
                if (state.contacts.isEmpty()) {
                    item(key = "e_contacts") { Text("(no contacts — add one above or from Discover below)", style = MaterialTheme.typography.bodySmall) }
                }
                items(state.contacts, key = { "c_" + it.hash }) { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                Text(c.hash, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { vm.remove(c.hash) }) { Text("Remove") }
                        }
                    }
                }

                item(key = "h_groups") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Groups (${state.groups.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { newGroup = true }) { Text("New group") }
                    }
                }
                items(state.groups, key = { "g_" + it.name }) { g ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(g.name, style = MaterialTheme.typography.bodyMedium)
                                val names = g.members.map { m -> state.contacts.firstOrNull { it.hash == m }?.name ?: m.take(8) }
                                Text(names.joinToString(), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { vm.removeGroup(g.name) }) { Text("Remove") }
                        }
                    }
                }

                item(key = "h_discover") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Discover (${state.discovered.size})", style = MaterialTheme.typography.titleSmall)
                }
                items(state.discovered, key = { "d_" + it.hash }) { peer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.displayName.ifBlank { peer.hash.take(8) }, style = MaterialTheme.typography.bodyMedium)
                                Text("${peer.hash.take(10)}… · ${peer.aspect}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                IconButton(onClick = {
                                    if (peer.hash in state.starred) vm.unstar(peer.hash) else vm.star(peer.hash)
                                }) {
                                    Icon(
                                        if (peer.hash in state.starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "star",
                                    )
                                }
                                OutlinedButton(onClick = { vm.addDiscovered(peer) }) { Text("Add") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (newGroup) {
        var gName by remember { mutableStateOf("") }
        var members by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newGroup = false },
            title = { Text("New group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(gName, { gName = it }, label = { Text("group name") }, singleLine = true)
                    OutlinedTextField(
                        members, { members = it },
                        label = { Text("members") },
                        supportingText = { Text("contact names, comma-separated") },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.createGroup(gName, members); newGroup = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newGroup = false }) { Text("Cancel") } },
        )
    }
}
