package com.ebowwa.tab.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ebowwa.tab.core.agent.StudioRequests

@Composable
fun TabRoute(
    autoPrompt: String? = null,
    autoDownload: Boolean = false,
    autoHtml: String? = null,
) {
    val context = LocalContext.current
    val viewModel: StudioViewModel = viewModel(factory = StudioViewModel.factory(context))

    LaunchedEffect(autoDownload) { if (autoDownload) viewModel.prepareModel() }
    LaunchedEffect(autoPrompt) { autoPrompt?.let { viewModel.generate(it) } }
    LaunchedEffect(autoHtml) { autoHtml?.let { viewModel.install(it) } }

    // A request from the system Gemini agent, possibly queued while this screen was alive.
    val queued by StudioRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(queued) {
        queued?.let {
            StudioRequests.consume()
            viewModel.generate(it)
        }
    }

    MaterialTheme {
        Surface { TabScreen(viewModel) }
    }
}
