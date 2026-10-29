package com.ebowwa.tab.feature.ledger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ebowwa.tab.core.agent.GeminiRestEngine
import com.ebowwa.tab.core.agent.HtmlValidator
import com.ebowwa.tab.core.agent.ModelDownload
import com.ebowwa.tab.core.agent.ModelStatus
import com.ebowwa.tab.core.agent.PlanEngine
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
    val apiKeyPresent: Boolean = false,
    val elapsed: Int = 0,
)

class StudioViewModel(private val appContext: Context) : ViewModel() {

    private val agent = StudioAgent()
    private val prefs = appContext.getSharedPreferences("studio_prefs", Context.MODE_PRIVATE)

    private var lastPrompt = ""
    private var repairAttempts = 0
    private var repairing = false

    private val _state = MutableStateFlow(
        UiState(apiKeyPresent = prefs.getString(KEY_API, "").orEmpty().isNotBlank()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(status = runCatching { agent.status() }.getOrElse { ModelStatus.UNAVAILABLE })
            }
        }
    }

    // ---------------------------------------------------------------- engines

    private fun apiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    private fun modelName(): String =
        prefs.getString(KEY_MODEL, GeminiRestEngine.DEFAULT_MODEL).orEmpty()
            .ifBlank { GeminiRestEngine.DEFAULT_MODEL }

    /** Cloud when a key is configured, otherwise the on-device model. */
    private fun engine(): PlanEngine =
        if (apiKey().isNotBlank()) agent.cloudEngine(apiKey(), modelName()) else agent.engine

    fun saveApiKey(key: String, model: String) {
        prefs.edit()
            .putString(KEY_API, key.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { GeminiRestEngine.DEFAULT_MODEL })
            .apply()
        _state.update { it.copy(apiKeyPresent = key.isNotBlank()) }
    }

    // ---------------------------------------------------------------- model download

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
                            } else {
                                "Downloading model..."
                            }
                            _state.update { it.copy(downloadProgress = label) }
                        }
                        is ModelDownload.Completed ->
                            _state.update { it.copy(downloadProgress = "Warming up...") }
                        is ModelDownload.Failed ->
                            _state.update { it.copy(downloadProgress = null, error = "Download failed: ${s.message}") }
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

    // ---------------------------------------------------------------- generation

    fun generate(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty() || _state.value.busy) return
        lastPrompt = p
        repairAttempts = 0
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, metrics = "", elapsed = 0) }
            val ticker = viewModelScope.launch {
                var seconds = 0
                while (true) {
                    delay(1000)
                    seconds++
                    _state.update { it.copy(elapsed = seconds) }
                }
            }
            try {
                attempt(p, repairOf = null)
            } finally {
                ticker.cancel()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun attempt(prompt: String, repairOf: Pair<String, String>?) {
        val eng = engine()
        val result = if (repairOf == null) {
            agent.generate(eng, prompt)
        } else {
            agent.repair(eng, prompt, repairOf.first, repairOf.second)
        }

        result.fold(
            onSuccess = { r ->
                val verdict = HtmlValidator.validate(r.text)
                val page = verdict.html
                val tok = if (r.ttftMs > 0L) String.format(Locale.US, "%.0f", r.tokPerSec) else "n/a"
                val suffix = if (repairOf != null) " | repaired" else ""
                val summary = "${r.outputTokens} tok | ${r.totalMs} ms | $tok tok/s$suffix"

                if (page == null) {
                    _state.update { it.copy(error = verdict.reason, metrics = summary) }
                } else {
                    runCatching { java.io.File(appContext.filesDir, "last_game.html").writeText(page) }
                    _state.update { st ->
                        st.copy(
                            history = (st.history + listOfNotNull(st.html.ifBlank { null })).takeLast(10),
                            html = page,
                            metrics = summary,
                        )
                    }
                }
            },
            onFailure = { e -> _state.update { it.copy(error = e.message ?: "generation failed") } },
        )
    }

    /** Reported by the WebView when the generated page throws. */
    fun onRuntimeError(message: String) {
        android.util.Log.w("Tab", "webview error: $message")
        if (repairing || _state.value.busy || repairAttempts >= 2) return
        val prompt = lastPrompt
        if (prompt.isBlank() || _state.value.html.isBlank()) return
        repairing = true
        viewModelScope.launch {
            delay(400)
            val broken = _state.value.html
            repairAttempts++
            _state.update { it.copy(busy = true, error = null) }
            try {
                attempt(prompt, broken to message)
            } finally {
                _state.update { it.copy(busy = false) }
                repairing = false
            }
        }
    }

    // ---------------------------------------------------------------- ui

    fun undo() {
        val h = _state.value.history
        if (h.isEmpty()) return
        _state.update { it.copy(html = h.last(), history = h.dropLast(1)) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val KEY_API = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"

        fun factory(context: Context) = viewModelFactory {
            initializer { StudioViewModel(context.applicationContext) }
        }
    }
}
