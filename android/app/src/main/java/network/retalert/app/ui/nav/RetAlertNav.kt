@file:OptIn(ExperimentalMaterial3Api::class)

package network.retalert.app.ui.nav

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import network.retalert.app.ui.contacts.ContactsScreen
import network.retalert.app.ui.home.HomeScreen
import network.retalert.app.ui.inbox.InboxScreen
import network.retalert.app.ui.map.MapScreen
import network.retalert.app.ui.outbox.OutboxScreen
import network.retalert.app.ui.presets.PresetsScreen
import network.retalert.app.ui.send.SendScreen
import network.retalert.app.ui.settings.SettingsScreen

/** The 8 top-level screens, in bottom-nav order. */
enum class RetDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Speed),
    Send("send", "Send", Icons.Filled.Send),
    Inbox("inbox", "Inbox", Icons.Filled.Inbox),
    Outbox("outbox", "Outbox", Icons.Filled.Call),
    Presets("presets", "Presets", Icons.Filled.Apps),
    Map("map", "Map", Icons.Filled.Map),
    Contacts("contacts", "Contacts", Icons.Filled.People),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
private fun isWide() = LocalConfiguration.current.screenWidthDp >= 600

/** Adaptive nav: a rail on wide screens (>=600dp), a bottom bar otherwise. */
@Composable
fun RetAlertApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val wide = isWide()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    RetDest.entries.forEach { d ->
                        val selected = current?.hierarchy?.any { it.route == d.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTo(d) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        val host: @Composable (Modifier) -> Unit = { mod ->
            NavHost(navController = nav, startDestination = RetDest.Home.route, modifier = mod) {
                composable(RetDest.Home.route) { HomeScreen() }
                composable(RetDest.Send.route) { SendScreen() }
                composable(RetDest.Inbox.route) { InboxScreen() }
                composable(RetDest.Outbox.route) { OutboxScreen() }
                composable(RetDest.Presets.route) { PresetsScreen() }
                composable(RetDest.Map.route) { MapScreen() }
                composable(RetDest.Contacts.route) { ContactsScreen() }
                composable(RetDest.Settings.route) { SettingsScreen() }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(inner)) {
                NavigationRail {
                    RetDest.entries.forEach { d ->
                        val selected = current?.hierarchy?.any { it.route == d.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { nav.navigateTo(d) },
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

private fun NavHostController.navigateTo(d: RetDest) {
    navigate(d.route) {
        popUpTo(graph.findStartDestination().route!!) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}