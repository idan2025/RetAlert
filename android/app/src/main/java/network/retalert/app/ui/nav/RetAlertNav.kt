@file:OptIn(ExperimentalMaterial3Api::class)

package network.retalert.app.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import network.retalert.app.ui.chat.ChatScreen
import network.retalert.app.ui.contacts.ContactsScreen
import network.retalert.app.ui.home.HomeScreen
import network.retalert.app.ui.inbox.InboxScreen
import network.retalert.app.ui.interfaces.InterfacesScreen
import network.retalert.app.ui.map.MapScreen
import network.retalert.app.ui.outbox.OutboxScreen
import network.retalert.app.ui.presets.PresetsScreen
import network.retalert.app.ui.send.SendScreen
import network.retalert.app.ui.settings.SettingsScreen

/** Bottom-nav tabs. */
enum class RetDest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Home),
    Map("map", "Map", Icons.Filled.Map),
    Inbox("inbox", "Inbox", Icons.Filled.Inbox),
    Contacts("contacts", "Contacts", Icons.Filled.People),
    More("more", "More", Icons.Filled.MoreHoriz),
}

/** Screens reached from More (and from shortcuts on Home). */
enum class SubDest(val route: String, val label: String, val subtitle: String, val icon: ImageVector) {
    Send("send", "Send alert", "Write a custom alert to a contact or group", Icons.AutoMirrored.Filled.Send),
    Outbox("outbox", "Sent alerts", "Delivery and acknowledgement status", Icons.Filled.Outbox),
    Presets("presets", "Presets", "Saved alerts, including what Panic sends", Icons.Filled.Apps),
    Interfaces("interfaces", "Interfaces", "LAN, TCP, UDP, RNode LoRa, Bluetooth, I2P", Icons.Filled.Hub),
    Settings("settings", "Settings", "Connection, alarm, location sharing, triggers", Icons.Filled.Settings),
}

@Composable
private fun isWide() = LocalConfiguration.current.screenWidthDp >= 600

/** Adaptive nav: a rail on wide screens (>=600dp), a bottom bar otherwise. */
@Composable
fun RetAlertApp(openChat: String? = null, onChatOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // Sub-screens highlight the More tab.
    val selectedTab = RetDest.entries.firstOrNull { it.route == route }
        ?: if (SubDest.entries.any { it.route == route }) RetDest.More else RetDest.Home
    val wide = isWide()
    val back: () -> Unit = { nav.popBackStack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    RetDest.entries.forEach { d ->
                        NavigationBarItem(
                            selected = d == selectedTab,
                            onClick = { nav.navigateTab(d) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        val host: @Composable (Modifier) -> Unit = { mod ->
            NavHost(navController = nav, startDestination = RetDest.Home.route, modifier = mod) {
                composable(RetDest.Home.route) {
                    HomeScreen(
                        onCustomAlert = { nav.navigate(SubDest.Send.route) },
                        onOpenMap = { nav.navigateTab(RetDest.Map) },
                    )
                }
                composable(RetDest.Map.route) { MapScreen() }
                composable(RetDest.Inbox.route) {
                    InboxScreen(onShowOnMap = { nav.navigateTab(RetDest.Map) }, onOpenChat = { nav.navigate("chat/$it") })
                }
                composable("chat/{alertId}") { ChatScreen(onBack = back) }
                composable(RetDest.Contacts.route) { ContactsScreen() }
                composable(RetDest.More.route) { MoreScreen(onOpen = { nav.navigate(it.route) }) }
                composable(SubDest.Send.route) { SendScreen(onBack = back) }
                composable(SubDest.Outbox.route) { OutboxScreen(onBack = back, onOpenChat = { nav.navigate("chat/$it") }) }
                composable(SubDest.Presets.route) { PresetsScreen(onBack = back) }
                composable(SubDest.Settings.route) {
                    SettingsScreen(onBack = back, onOpenInterfaces = { nav.navigate(SubDest.Interfaces.route) })
                }
                composable(SubDest.Interfaces.route) { InterfacesScreen(onBack = back) }
            }
        }
        LaunchedEffect(openChat) {
            if (openChat != null) {
                nav.navigate("chat/$openChat") { launchSingleTop = true }
                onChatOpened()
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(inner)) {
                NavigationRail {
                    RetDest.entries.forEach { d ->
                        NavigationRailItem(
                            selected = d == selectedTab,
                            onClick = { nav.navigateTab(d) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
                host(Modifier.fillMaxSize())
            }
        } else {
            host(Modifier.fillMaxSize().padding(inner))
        }
    }
}

@Composable
private fun MoreScreen(onOpen: (SubDest) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { inner ->
        Column(Modifier.padding(inner)) {
            SubDest.entries.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.label) },
                    supportingContent = { Text(d.subtitle) },
                    leadingContent = { Icon(d.icon, contentDescription = null) },
                    modifier = Modifier.clickable { onOpen(d) },
                )
            }
        }
    }
}

private fun NavHostController.navigateTab(d: RetDest) {
    navigate(d.route) {
        popUpTo(graph.findStartDestination().route!!) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
