package com.ebowwa.tab.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The feature's only public entry point.
 *
 * The app module hands over intent-derived inputs and nothing else: it never sees the
 * ViewModel, the ledger store, or an ML Kit type. Everything behind this composable is
 * the feature's business.
 */
@Composable
fun TabRoute(
    autoPrompt: String? = null,
    autoDownload: Boolean = false,
) {
    val context = LocalContext.current
    val viewModel: TabViewModel = viewModel(factory = TabViewModel.factory(context))

    LaunchedEffect(autoDownload) { if (autoDownload) viewModel.prepareModel() }
    LaunchedEffect(autoPrompt) { autoPrompt?.let { viewModel.ask(it) } }

    MaterialTheme {
        Surface {
            TabScreen(viewModel)
        }
    }
}
