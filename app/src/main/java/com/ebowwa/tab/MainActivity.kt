package com.ebowwa.tab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ebowwa.tab.core.agent.StudioRequests
import com.ebowwa.tab.feature.ledger.TabRoute

/**
 * Deliberately thin: it owns the window and translates the launch intent, then hands off.
 * All UI, state and agent wiring live in :feature:ledger.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The Activity only handles a directly-supplied prompt; a prompt queued by the
        // system agent is picked up by TabRoute, which works even when we are already alive.
        val prompt = intent?.getStringExtra("prompt")
        val installed = StudioRequests.takeHtml()
        setContent {
            TabRoute(
                autoPrompt = prompt,
                autoHtml = installed,
                autoDownload = intent?.getBooleanExtra("download", false) == true,
            )
        }
    }
}
