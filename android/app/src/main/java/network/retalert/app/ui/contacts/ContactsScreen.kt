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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ContactsScreen(vm: ContactsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Contacts") }, actions = {
            TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
        }) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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

            LazyColumn(
                Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text("Contacts (${state.contacts.size})", style = MaterialTheme.typography.titleSmall)
                }
                if (state.contacts.isEmpty()) {
                    item { Text("(no contacts)", style = MaterialTheme.typography.bodySmall) }
                }
                items(state.contacts, key = { it.hash }) { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${c.hash.take(10)}…", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { vm.remove(c.hash) }) { Text("Remove") }
                        }
                    }
                }

                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Discover (${state.discovered.size})", style = MaterialTheme.typography.titleSmall)
                }
                items(state.discovered, key = { it.hash }) { peer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
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
        if (state.flash.isNotEmpty()) {
            Text(state.flash, modifier = Modifier.padding(8.dp))
        }
    }
}