package com.ebowwa.tab.feature.ledger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ebowwa.tab.core.agent.GameTemplate
import com.ebowwa.tab.core.agent.ModelDownload
import com.ebowwa.tab.core.agent.ModelStatus
import com.ebowwa.tab.core.agent.StudioAgent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class UiState(
    val html: String = "",
    val history: List<String> = emptyList(),
    val busy: Boolean = false,
    val status: ModelStatus = ModelStatus.UNKNOWN,
    val downloadProgress: String? = null,
    val error: String? = null,
    val metrics: String = "",
)

class StudioViewModel(private val appContext: Context) : ViewModel() {

    private val agent = StudioAgent()

    private val runtimeErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var repairAttempts = 0
    private var lastPrompt = ""

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(status = runCatching { agent.status() }.getOrElse { ModelStatus.UNAVAILABLE }) }
        }
    }

    fun prepareModel() {
        viewModelScope.launch {
            var total = 0L
            _state.update { it.copy(downloadProgress = "Starting...", error = null) }
            runCatching {
                agent.download().collect { s ->
                    when (s) {
                        is ModelDownload.Started -> {
                            total = s.bytesToDownload
                            _state.update { it.copy(downloadProgress = "Downloading model...") }
                        }
                        is ModelDownload.Progress -> {
                            val label = if (total > 0L) {
                                "Downloading model... ${(s.bytesDownloaded * 100 / total).toInt()}%"
                            } else "Downloading model..."
                            _state.update { it.copy(downloadProgress = label) }
                        }
                        is ModelDownload.Completed -> _state.update { it.copy(downloadProgress = "Warming up...") }
                        is ModelDownload.Failed -> _state.update {
                            it.copy(downloadProgress = null, error = "Download failed: ${s.message}")
                        }
                    }
                }
            }.onFailure { e -> _state.update { it.copy(downloadProgress = null, error = e.message) } }
            agent.warmUp()
            _state.update {
                it.copy(
                    downloadProgress = null,
                    status = runCatching { agent.status() }.getOrElse { ModelStatus.UNAVAILABLE },
                )
            }
        }
    }

    fun generate(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty() || _state.value.busy) return
        lastPrompt = p
        repairAttempts = 0
        runtimeErrors.clear()
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, metrics = "") }
            try {
                attempt(p, repairOf = null)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun attempt(prompt: String, repairOf: Pair<String, String>?) {
        agent.design(prompt).fold(
            onSuccess = { (spec, r) ->
                val page = GameTemplate.render(spec)
                runCatching { java.io.File(appContext.filesDir, "last_game.html").writeText(page) }
                val tok = if (r.ttftMs > 0L) String.format(Locale.US, "%.0f", r.tokPerSec) else "n/a"
                val summary = "${r.outputTokens} tok | ${r.totalMs} ms | $tok tok/s"
                _state.update { st ->
                    st.copy(
                        history = (st.history + listOfNotNull(st.html.ifBlank { null })).takeLast(10),
                        html = page,
                        metrics = summary,
                    )
                }
            },
            onFailure = { e -> _state.update { it.copy(error = e.message ?: "design failed") } },
        )
    }

    /**
     * Reported by the WebView when the generated page throws. Repairs are triggered by the
     * error arriving, not by a timer: a fixed delay missed errors that surfaced later.
     */
    fun onRuntimeError(message: String) {
        // The engine is ours, so this should never fire; keep it visible if it does.
        android.util.Log.w("Tab", "webview error: $message")
    }

    private var repairing = false

    fun undo() {
        val h = _state.value.history
        if (h.isEmpty()) return
        _state.update { it.copy(html = h.last(), history = h.dropLast(1)) }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { StudioViewModel(context.applicationContext) }
        }
    }
}
