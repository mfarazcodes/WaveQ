package com.waveq.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.waveq.app.navigation.AppRoot
import com.waveq.app.ui.theme.AppBackground
import com.waveq.app.ui.theme.DisasterReportTheme
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Modern SharedPreferences initialization (no deprecated warnings)
        Configuration.getInstance().load(
            applicationContext,
            applicationContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setContent {
            DisasterReportTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(AppBackground),
                    color = AppBackground,
                ) {
                    AppRoot()
                }
            }
        }
    }
}