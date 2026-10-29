package com.ebowwa.tab.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TabRoute(
    autoPrompt: String? = null,
    autoDownload: Boolean = false,
) {
    val context = LocalContext.current
    val viewModel: StudioViewModel = viewModel(factory = StudioViewModel.factory(context))

    LaunchedEffect(autoDownload) { if (autoDownload) viewModel.prepareModel() }
    LaunchedEffect(autoPrompt) { autoPrompt?.let { viewModel.generate(it) } }

    MaterialTheme {
        Surface { TabScreen(viewModel) }
    }
}
