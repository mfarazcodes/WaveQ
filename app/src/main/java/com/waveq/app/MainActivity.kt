package com.waveq.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.waveq.app.navigation.AppRoot
import com.waveq.app.settings.ThemeMode
import com.waveq.app.settings.ThemeSettings
import com.waveq.app.ui.theme.WaveQTheme

class MainActivity : ComponentActivity() {

    private var prearmedSosNote by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prearmedSosNote = intent?.getStringExtra(EXTRA_PREARM_SOS_NOTE)
        setContent {
            val context = LocalContext.current
            ThemeSettings.init(context)
            val themeMode by ThemeSettings.mode.collectAsState()
            val useDarkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            WaveQTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(
                        prearmedSosNote = prearmedSosNote,
                        onPrearmedSosConsumed = { prearmedSosNote = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prearmedSosNote = intent.getStringExtra(EXTRA_PREARM_SOS_NOTE)
    }

    companion object {
        /** Set by [com.waveq.app.ui.screens.CriticalAlertActivity]'s "I Need Help" button. */
        const val EXTRA_PREARM_SOS_NOTE = "com.waveq.app.EXTRA_PREARM_SOS_NOTE"
    }
}
