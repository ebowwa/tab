package com.ebowwa.tab.feature.ledger

import android.graphics.Color as AndroidColor
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebowwa.tab.core.agent.GeminiRestEngine
import com.ebowwa.tab.core.agent.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(vm: StudioViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Studio", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when {
                                state.busy -> "writing game... ${state.elapsed}s"
                                state.metrics.isNotBlank() -> state.metrics
                                else -> "on-device Gemini Nano"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.history.isNotEmpty()) {
                        TextButton(onClick = vm::undo) { Text("Undo") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            if (state.status != ModelStatus.AVAILABLE) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = when (state.status) {
                                ModelStatus.DOWNLOADABLE -> "Gemini Nano needs a one-time download"
                                ModelStatus.UNAVAILABLE -> "This device has no on-device model"
                                else -> "Checking the on-device model..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.downloadProgress?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.status == ModelStatus.DOWNLOADABLE) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = vm::prepareModel) { Text("Download model") }
                        }
                    }
                }
            }

            if (!state.apiKeyPresent) {
                var key by remember { mutableStateOf("") }
                var model by remember { mutableStateOf(GeminiRestEngine.DEFAULT_MODEL) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("optional: Gemini API key for bigger games") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                    )
                    Button(
                        onClick = { vm.saveApiKey(key, model) },
                        enabled = key.isNotBlank(),
                    ) { Text("Use") }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                if (state.html.isBlank()) {
                    Text(
                        text = "Describe a game.\nIt runs entirely on the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    GameView(state.html, onError = vm::onRuntimeError, modifier = Modifier.fillMaxSize())
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("a game where you catch falling stars") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !state.busy,
                )
                Button(
                    onClick = { val t = input; input = ""; vm.generate(t) },
                    enabled = !state.busy && input.isNotBlank(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Build")
                    }
                }
            }
        }
    }
}

@Composable
private fun GameView(html: String, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val report by rememberUpdatedState(onError)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.blockNetworkLoads = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        val text = "${m.message()} @${m.lineNumber()}"
                        Log.i("Tab", "webview console: ${m.messageLevel()} $text")
                        if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) report(text)
                        return true
                    }
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, injectErrorHook(html), "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * Catch uncaught JS errors so they can be fed back to the model. Injected as the first
 * script in the document, before the game code runs.
 */
private const val ERROR_HOOK =
    "<script>window.onerror=function(m,s,l){console.error('JS '+m+' line '+l);return false;};</script>"

private fun injectErrorHook(html: String): String {
    val anchors = listOf("<head>", "<head ", "<body>", "<body ", "<html>", "<html ")
    val idx = anchors.map { html.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
        ?: return ERROR_HOOK + html
    val gt = html.indexOf('>', idx)
    if (gt < 0) return ERROR_HOOK + html
    return html.substring(0, gt + 1) + ERROR_HOOK + html.substring(gt + 1)
}
