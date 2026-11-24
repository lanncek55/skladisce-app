package si.skladisce.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import si.skladisce.app.ui.MainViewModel
import si.skladisce.app.ui.SkladisceApp
import si.skladisce.app.ui.theme.SkladisceTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            (application as SkladisceApplication).bleSyncManager.startAutoSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        val app = application as SkladisceApplication
        if (!app.bleSyncManager.hasBlePermissions()) {
            blePermissionLauncher.launch(app.bleSyncManager.requiredPermissions())
        }

        setContent {
            SkladisceTheme {
                SkladisceApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (viewModel.onHardwareVolumeKey(isUp = true)) return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (viewModel.onHardwareVolumeKey(isUp = false)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            viewModel.handleIncomingBackupUri(uri)
        }
    }
}
