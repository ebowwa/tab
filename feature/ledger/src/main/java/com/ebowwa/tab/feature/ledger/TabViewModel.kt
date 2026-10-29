package com.ebowwa.tab.feature.ledger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ebowwa.tab.core.agent.GameAudit
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
    val phase: String = "",
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

    // ---------------------------------------------------------------- generate + iterate

    fun generate(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty() || _state.value.busy) return
        lastPrompt = p
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, metrics = "", elapsed = 0, phase = "writing") }
            val ticker = viewModelScope.launch {
                var seconds = 0
                while (true) {
                    delay(1000)
                    seconds++
                    _state.update { it.copy(elapsed = seconds) }
                }
            }
            try {
                iterate(p)
            } finally {
                ticker.cancel()
                _state.update { it.copy(busy = false, phase = "") }
            }
        }
    }

    /**
     * Generate, then check the model's own work and feed the defects back.
     *
     * Two independent checks: [GameAudit] is deterministic and cannot be talked out of a
     * verdict; [StudioAgent.critique] has the model read its game against a checklist. Both
     * produce problems, which drive the next rewrite. Stops as soon as a round comes back
     * clean, or after [MAX_ROUNDS].
     */
    private suspend fun iterate(prompt: String) {
        val eng = engine()

        _state.update { it.copy(phase = "writing") }
        val first = agent.generate(eng, prompt).getOrElse {
            _state.update { st -> st.copy(error = it.message ?: "generation failed") }
            return
        }
        var html = HtmlValidator.validate(first.text).html ?: run {
            _state.update { st -> st.copy(error = HtmlValidator.validate(first.text).reason, metrics = summary(first)) }
            return
        }
        _state.update { it.copy(metrics = summary(first)) }

        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            _state.update { it.copy(phase = "checking $round/$MAX_ROUNDS") }

            val mechanical = GameAudit.issues(html)
            val critique = agent.critique(eng, prompt, html).getOrNull()
            val problems = (mechanical + (critique?.problems ?: emptyList())).distinct()

            if (problems.isEmpty()) {
                accept(html, "checked ${round}x")
                return
            }

            _state.update { it.copy(phase = "fixing $round/$MAX_ROUNDS") }
            val fixed = agent.repair(eng, prompt, html, problems.joinToString("; ")).getOrNull()
                ?: break
            val validated = HtmlValidator.validate(fixed.text).html ?: continue
            html = validated
            _state.update { it.copy(metrics = summary(fixed)) }
        }

        // Ran out of rounds: keep the best document we have, but say it is unverified.
        accept(html, "best effort after $MAX_ROUNDS")
    }

    private fun accept(html: String, note: String) {
        runCatching { java.io.File(appContext.filesDir, "last_game.html").writeText(html) }
        _state.update { st ->
            st.copy(
                history = (st.history + listOfNotNull(st.html.ifBlank { null })).takeLast(10),
                html = html,
                metrics = if (st.metrics.isBlank()) note else "${st.metrics} | $note",
            )
        }
    }

    private fun summary(r: com.ebowwa.tab.core.agent.GenerationResult): String {
        val tok = if (r.ttftMs > 0L) String.format(Locale.US, "%.0f", r.tokPerSec) else "n/a"
        return "${r.outputTokens} tok | ${r.totalMs} ms | $tok tok/s"
    }

    /** A finished game handed over by the system agent: validate, then show it. */
    fun install(html: String) {
        val verdict = HtmlValidator.validate(html)
        val page = verdict.html
        if (page == null) {
            _state.update { it.copy(error = verdict.reason) }
            return
        }
        accept(page, "from Gemini")
    }

    /** Reported by the WebView when the generated page throws. */
    fun onRuntimeError(message: String) {
        android.util.Log.w("Tab", "webview error: $message")
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
        private const val MAX_ROUNDS = 3

        fun factory(context: Context) = viewModelFactory {
            initializer { StudioViewModel(context.applicationContext) }
        }
    }
}
