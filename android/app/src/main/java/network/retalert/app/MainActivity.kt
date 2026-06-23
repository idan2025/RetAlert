package network.retalert.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import network.retalert.app.ui.nav.RetAlertApp
import network.retalert.app.ui.theme.RetAlertTheme

/** Single-activity host for the Compose nav graph. Requests the runtime
 *  permissions the Phase-4 native features need (notifications, camera, mic,
 *  location) on first launch. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private fun missingPerms(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }
        missingPerms().takeIf { it.isNotEmpty() }?.let { launcher.launch(it) }
        setContent {
            RetAlertTheme {
                Surface(modifier = Modifier.fillMaxSize()) { RetAlertApp() }
            }
        }
    }
}