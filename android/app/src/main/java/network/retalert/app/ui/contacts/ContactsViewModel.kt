package network.retalert.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.Contact
import network.retalert.domain.ContactRepository
import network.retalert.domain.Discover
import network.retalert.domain.DiscoveredPeer
import network.retalert.domain.Group
import network.retalert.domain.GroupRepository
import network.retalert.domain.StarredRepository
import network.retalert.domain.normalizeHash
import javax.inject.Inject

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val groups: List<Group> = emptyList(),
    /** Announced peers that are not contacts yet. */
    val discovered: List<DiscoveredPeer> = emptyList(),
    val starred: Set<String> = emptySet(),
    val flash: String = "",
)

/** Contacts screen: add/remove (hash+name), groups of contacts, the discover
 *  list of announced LXMF dests, star, and add a discovered peer to contacts. */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val groups: GroupRepository,
    private val starred: StarredRepository,
    private val discover: Discover,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val cs = contacts.list()
            val known = cs.map { it.hash }.toSet()
            _state.update {
                it.copy(
                    contacts = cs,
                    groups = groups.list(),
                    discovered = discover.list().filter { p -> p.hash !in known }.distinctBy { p -> p.hash },
                    starred = starred.starred().toSet(),
                )
            }
        }
    }

    private fun flash(msg: String) = _state.update { it.copy(flash = msg) }

    fun add(hash: String, name: String) = viewModelScope.launch(Dispatchers.IO) {
        val h = normalizeHash(hash)
        when {
            h == null -> flash("invalid hash: needs 32 hex characters")
            name.isBlank() -> flash("name required")
            else -> { contacts.add(h, name.trim()); flash("added ${name.trim()}") }
        }
        refresh()
    }

    fun remove(hash: String) = viewModelScope.launch(Dispatchers.IO) {
        contacts.remove(hash)
        refresh()
    }

    fun star(hash: String) = viewModelScope.launch(Dispatchers.IO) {
        starred.star(hash); discover.star(hash); refresh()
    }

    fun unstar(hash: String) = viewModelScope.launch(Dispatchers.IO) {
        starred.unstar(hash); discover.unstar(hash); refresh()
    }

    /** Add a discovered peer to contacts (uses its display name or hash). */
    fun addDiscovered(peer: DiscoveredPeer) = viewModelScope.launch(Dispatchers.IO) {
        val name = peer.displayName.ifBlank { peer.hash.take(8) }
        contacts.add(peer.hash, name)
        flash("added $name")
        refresh()
    }

    /** Create a group from comma-separated contact names. */
    fun createGroup(name: String, memberNames: String) = viewModelScope.launch(Dispatchers.IO) {
        val n = name.trim()
        val byName = contacts.list().associateBy { it.name.lowercase() }
        val parts = memberNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val missing = parts.filter { it.lowercase() !in byName && normalizeHash(it) == null }
        when {
            n.isEmpty() -> flash("group name required")
            parts.isEmpty() -> flash("add at least one member")
            missing.isNotEmpty() -> flash("unknown contact(s): ${missing.joinToString()}")
            else -> {
                val members = parts.map { byName[it.lowercase()]?.hash ?: normalizeHash(it)!! }.distinct()
                if (groups.create(n, members)) flash("created group $n (${members.size})")
                else flash("group $n already exists")
            }
        }
        refresh()
    }

    fun removeGroup(name: String) = viewModelScope.launch(Dispatchers.IO) {
        groups.remove(name)
        refresh()
    }
}
