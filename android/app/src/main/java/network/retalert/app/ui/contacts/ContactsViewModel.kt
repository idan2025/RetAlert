package network.retalert.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.ContactRepository
import network.retalert.domain.Discover
import network.retalert.domain.DiscoveredPeer
import network.retalert.domain.StarredRepository
import network.retalert.domain.Contact
import javax.inject.Inject

data class ContactsUiState(
    val contacts: List<Contact> = emptyList(),
    val discovered: List<DiscoveredPeer> = emptyList(),
    val starred: Set<String> = emptySet(),
    val flash: String = "",
)

/** Contacts screen: add/remove (hash+name), discover list of announced LXMF
 *  dests, star, and add a discovered peer to contacts. */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contacts: ContactRepository,
    private val starred: StarredRepository,
    private val discover: Discover,
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.update {
            it.copy(
                contacts = contacts.list(),
                discovered = discover.list(),
                starred = starred.starred().toSet(),
            )
        }
    }

    fun add(hash: String, name: String) = viewModelScope.launch {
        val h = hash.replace(":", "").trim()
        if (name.isBlank() || h.isBlank()) return@launch
        contacts.add(h, name)
        _state.update { it.copy(flash = "added $name") }
        refresh()
    }

    fun remove(hash: String) = viewModelScope.launch {
        contacts.remove(hash)
        refresh()
    }

    fun star(hash: String) = viewModelScope.launch {
        starred.star(hash); refresh()
    }

    fun unstar(hash: String) = viewModelScope.launch {
        starred.unstar(hash); refresh()
    }

    /** Add a discovered peer to contacts (uses its display name or hash). */
    fun addDiscovered(peer: DiscoveredPeer) = viewModelScope.launch {
        contacts.add(peer.hash, peer.displayName.ifBlank { peer.hash.take(8) })
        _state.update { it.copy(flash = "added ${peer.displayName.ifBlank { peer.hash.take(8) }}") }
        refresh()
    }
}