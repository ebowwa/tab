package com.ebowwa.tab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ebowwa.tab.feature.ledger.TabRoute

/**
 * Deliberately thin: it owns the window and translates the launch intent, then hands off.
 * All UI, state and agent wiring live in :feature:ledger.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TabRoute(
                autoPrompt = intent?.getStringExtra("prompt"),
                autoDownload = intent?.getBooleanExtra("download", false) == true,
            )
        }
    }
}
