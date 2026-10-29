package com.ebowwa.tab.feature.ledger

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ebowwa.tab.core.agent.AgentOutcome
import com.ebowwa.tab.core.agent.GeminiRestEngine
import com.ebowwa.tab.core.agent.ModelDownload
import com.ebowwa.tab.core.agent.ModelStatus
import com.ebowwa.tab.core.agent.PlanEngine
import com.ebowwa.tab.core.agent.TabAgent
import com.ebowwa.tab.core.data.LedgerStore
import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Expense
import com.ebowwa.tab.core.model.Ledger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val ledger: Ledger = Ledger(),
    val pending: List<Change> = emptyList(),
    val pendingReply: String = "",
    val pendingNotes: List<String> = emptyList(),
    val busy: Boolean = false,
    val modelStatus: ModelStatus = ModelStatus.UNKNOWN,
    val nanoAvailable: Boolean = false,
    val engineLabel: String = "",
    val apiKeyPresent: Boolean = false,
    val downloadProgress: String? = null,
    val error: String? = null,
    val canUndo: Boolean = false,
    val lastRaw: String = "",
)

class TabViewModel(private val appContext: Context) : ViewModel() {

    private val store = LedgerStore.get(appContext)
    private val agent = TabAgent(appContext, store)
    private val prefs = appContext.getSharedPreferences("tab_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(apiKeyPresent = prefs.getString(KEY_API, "").orEmpty().isNotBlank()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.ledger.collect { ledger ->
                _state.update { it.copy(ledger = ledger, canUndo = store.canUndo) }
            }
        }
        // Staged changes can arrive from either surface: this agent, or the AppFunctions
        // service when the system Gemini proposes something.
        viewModelScope.launch {
            store.staged.collect { staged ->
                _state.update { it.copy(pending = staged) }
            }
        }
        viewModelScope.launch { refreshModel() }
    }

    // ---------------------------------------------------------------- model

    private fun apiKey(): String = prefs.getString(KEY_API, "").orEmpty()

    private fun modelName(): String =
        prefs.getString(KEY_MODEL, GeminiRestEngine.DEFAULT_MODEL).orEmpty()
            .ifBlank { GeminiRestEngine.DEFAULT_MODEL }

    private suspend fun refreshModel() {
        val status = runCatching { agent.status() }.getOrElse { ModelStatus.UNAVAILABLE }
        android.util.Log.i("Tab", "nano status = $status")
        android.util.Log.i("Tab", "nano diag: " + runCatching { agent.diagnose() }.getOrElse { "err" })

        val nanoOk = status == ModelStatus.AVAILABLE
        val hasKey = apiKey().isNotBlank()
        _state.update {
            it.copy(
                modelStatus = status,
                nanoAvailable = nanoOk,
                apiKeyPresent = hasKey,
                engineLabel = when {
                    nanoOk -> agent.nanoEngine.label
                    hasKey -> "${modelName()} · cloud"
                    else -> "no model"
                },
            )
        }
    }

    private fun engine(): PlanEngine? = when {
        _state.value.nanoAvailable -> agent.nanoEngine
        apiKey().isNotBlank() -> GeminiRestEngine(apiKey(), modelName())
        else -> null
    }

    fun saveApiKey(key: String, model: String) {
        prefs.edit()
            .putString(KEY_API, key.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { GeminiRestEngine.DEFAULT_MODEL })
            .apply()
        viewModelScope.launch {
            refreshModel()
            _state.update { it.copy(error = null) }
        }
    }

    fun prepareModel() {
        viewModelScope.launch {
            var total = 0L
            _state.update { it.copy(error = null, downloadProgress = "Starting download…") }
            runCatching {
                agent.download().collect { s ->
                    android.util.Log.i("Tab", "download status: $s")
                    when (s) {
                        is ModelDownload.Started -> {
                            total = s.bytesToDownload
                            _state.update { it.copy(downloadProgress = "Downloading Gemini Nano…") }
                        }
                        is ModelDownload.Progress -> {
                            val label = if (total > 0L) {
                                val pct = (s.bytesDownloaded * 100 / total).toInt().coerceIn(0, 100)
                                "Downloading Gemini Nano… $pct%"
                            } else {
                                val mb = s.bytesDownloaded / (1024.0 * 1024.0)
                                "Downloading Gemini Nano… ${"%.0f".format(mb)} MB"
                            }
                            _state.update { it.copy(downloadProgress = label) }
                        }
                        is ModelDownload.Completed ->
                            _state.update { it.copy(downloadProgress = "Warming up…") }
                        is ModelDownload.Failed ->
                            _state.update {
                                it.copy(
                                    downloadProgress = null,
                                    error = "Model download failed: ${s.message}",
                                )
                            }
                    }
                }
            }.onFailure { e ->
                _state.update { it.copy(downloadProgress = null, error = e.message) }
            }
            agent.warmUp()
            _state.update { it.copy(downloadProgress = null) }
            refreshModel()
        }
    }

    // ---------------------------------------------------------------- agent

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || _state.value.busy) return

        viewModelScope.launch {
            // The model status may not have resolved yet (cold start, or the intent-driven
            // auto-prompt). Re-check once rather than refusing a request we can serve.
            if (!_state.value.nanoAvailable && apiKey().isBlank()) {
                refreshModel()
            }
            val engine = engine()
            if (engine == null) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = "No model on this device. Add an API key below to run the agent in the cloud.",
                    )
                }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            android.util.Log.i("Tab", "ask [${engine.label}]: $question")
            val outcome: AgentOutcome = agent.plan(engine, question)
            android.util.Log.i(
                "Tab",
                "outcome reply=${outcome.reply} changes=${outcome.changes.size} " +
                    "notes=${outcome.notes} error=${outcome.error} raw=${outcome.raw}",
            )
            store.stage(outcome.changes)
            _state.update {
                it.copy(
                    busy = false,
                    pendingReply = outcome.reply,
                    pendingNotes = outcome.notes,
                    error = outcome.error,
                    lastRaw = outcome.raw,
                )
            }
        }
    }

    fun applyPending() {
        if (_state.value.pending.isEmpty()) return
        store.commitStaged()
        _state.update {
            it.copy(
                pending = emptyList(),
                pendingReply = "",
                pendingNotes = emptyList(),
                canUndo = store.canUndo,
                lastRaw = "",
            )
        }
    }

    fun discardPending() {
        store.clearStaged()
        _state.update {
            it.copy(pendingReply = "", pendingNotes = emptyList(), lastRaw = "")
        }
    }

    fun undo() {
        store.undo()
        _state.update { it.copy(canUndo = store.canUndo) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun seedDemoData() {
        store.reset(
            Ledger(
                expenses = listOf(
                    Expense("a1", "Coffee run", 1840L, "You", listOf("You", "Ana", "Ben")),
                    Expense("a2", "Taxi to Bay View", 4600L, "Ana", listOf("You", "Ana", "Ben", "Cy")),
                ),
            ),
        )
    }

    companion object {
        private const val KEY_API = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"

        fun factory(context: Context) = viewModelFactory {
            initializer { TabViewModel(context.applicationContext) }
        }
    }
}
