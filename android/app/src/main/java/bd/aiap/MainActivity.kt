package bd.aiap

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import bd.aiap.ui.AppRoot
import bd.aiap.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AiApp.from(this).alarms.rescheduleAll()
        // Only on a fresh start: after rotation the old shortcut intent must not start listening again.
        if (savedInstanceState == null) handleShortcut(intent)
        setContent {
            AppTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShortcut(intent: Intent?) {
        if (intent?.action == VOICE_ACTION) viewModel.listenRequested = true
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh() // reminders may have fired while we were away
    }

    companion object {
        const val VOICE_ACTION = "bd.aiap.VOICE"
    }
}
