package com.ebowwa.tab.core.agent

import android.content.Context
import android.util.Log
import com.ebowwa.tab.core.agent.BuildConfig
import com.ebowwa.tab.core.data.LedgerStore
import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Ledger
import com.ebowwa.tab.core.model.balances
import com.ebowwa.tab.core.model.money
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

enum class ModelStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE, UNKNOWN }

/**
 * ML Kit's download states, remapped so no module above this one imports an ML Kit type.
 * This is the module boundary: the feature layer depends on the agent's vocabulary only.
 */
sealed interface ModelDownload {
    data class Started(val bytesToDownload: Long) : ModelDownload
    data class Progress(val bytesDownloaded: Long) : ModelDownload
    data object Completed : ModelDownload
    data class Failed(val message: String?) : ModelDownload
}

data class AgentOutcome(
    val reply: String,
    val changes: List<Change> = emptyList(),
    val notes: List<String> = emptyList(),
    val error: String? = null,
    val raw: String = "",
)

/**
 * The agent. It owns the instructions and the prompt, and delegates generation to a
 * [PlanEngine] so the same behaviour runs on-device (Gemini Nano) or in the cloud.
 *
 * The model never writes. It returns a plan; [com.ebowwa.tab.core.data.LedgerStore.commit]
 * only runs after the user approves it.
 */
class TabAgent(
    context: Context,
    private val store: LedgerStore,
) {
    private val model: GenerativeModel = Generation.getClient()
    private val nano: PlanEngine = NanoEngine(model)

    val nanoEngine: PlanEngine get() = nano

    suspend fun status(): ModelStatus = runCatching {
        when (model.checkStatus()) {
            0 -> ModelStatus.UNAVAILABLE
            1 -> ModelStatus.DOWNLOADABLE
            2 -> ModelStatus.DOWNLOADING
            3 -> ModelStatus.AVAILABLE
            else -> ModelStatus.UNKNOWN
        }
    }.getOrElse { ModelStatus.UNAVAILABLE }

    suspend fun isNanoAvailable(): Boolean = status() == ModelStatus.AVAILABLE

    fun download(): Flow<ModelDownload> = model.download().map { status ->
        when (status) {
            is DownloadStatus.DownloadStarted -> ModelDownload.Started(status.bytesToDownload)
            is DownloadStatus.DownloadProgress -> ModelDownload.Progress(status.totalBytesDownloaded)
            is DownloadStatus.DownloadCompleted -> ModelDownload.Completed
            is DownloadStatus.DownloadFailed -> ModelDownload.Failed(status.e.message)
        }
    }

    suspend fun warmUp() {
        runCatching { model.warmup() }
    }

    /** Everything AICore will tell us about what this device actually supports. */
    suspend fun diagnose(): String = buildString {
        append("status=").append(runCatching { model.checkStatus() }.getOrElse { -1 })
        append(" features=").append(runCatching { model.getAvailableFeatures() }.getOrElse { "err" })
        append(" baseModel=").append(runCatching { model.getBaseModelName() }.getOrElse { "err" })
        append(" tokenLimit=").append(runCatching { model.getTokenLimit() }.getOrElse { "err" })
        append(" structured=").append(runCatching { model.isStructuredOutputFeatureAvailable() }.getOrElse { "err" })
        append(" systemPrompt=").append(runCatching { model.isSystemPromptAvailable() }.getOrElse { "err" })
        append(" thinking=").append(runCatching { model.isThinkingModeAvailable() }.getOrElse { "err" })
        append(" caching=").append(runCatching { model.isCachingFeatureAvailable() }.getOrElse { "err" })
    }

    suspend fun plan(engine: PlanEngine, userText: String): AgentOutcome {
        val ledger = store.ledger.value
        val prompt = buildPrompt(ledger, userText)

        val result = engine.generate(SYSTEM_INSTRUCTION, prompt).getOrElse { e ->
            logMetrics(userText, engine, null, ok = false)
            return AgentOutcome(reply = "", error = e.message ?: e::class.java.simpleName)
        }

        logMetrics(userText, engine, result, ok = result.text.isNotBlank())

        val raw = result.text.trim()
        if (raw.isEmpty()) {
            return AgentOutcome(reply = "", error = "The model returned an empty response.")
        }

        return PlanParser.parse(raw, ledger)
    }

    /**
     * The one line the benchmark harness parses. Keep the key=value order and quoting
     * stable: prompt and engine are quoted because both can contain spaces.
     */
    private fun logMetrics(
        userText: String,
        engine: PlanEngine,
        result: GenerationResult?,
        ok: Boolean,
    ) {
        // Release builds must not write the user's prompts or ledger contents to logcat.
        val safePrompt = if (BuildConfig.DEBUG) {
            userText.replace("\"", "'").replace("\n", " ")
        } else {
            "<redacted>"
        }
        // Decode rate is only meaningful when prefill was actually measured. Without a
        // streaming TTFT the honest answer is n/a, not output-tokens-over-wall-clock.
        val tokPerSec = result?.let { r ->
            if (r.ttftMs > 0L) String.format(Locale.US, "%.1f", r.tokPerSec) else "n/a"
        } ?: "n/a"
        Log.i(
            "Tab",
            "metrics prompt=\"$safePrompt\" engine=\"${engine.label}\" ok=$ok " +
                "promptTokens=${result?.promptTokens ?: -1} " +
                "outputTokens=${result?.outputTokens ?: -1} " +
                "totalTokens=${result?.totalTokens ?: -1} " +
                "ttftMs=${result?.ttftMs ?: -1} " +
                "totalMs=${result?.totalMs ?: -1} " +
                "tokPerSec=$tokPerSec",
        )
    }

    // ---------------------------------------------------------------- prompting

    private fun buildPrompt(ledger: Ledger, userText: String): String = buildString {
        appendLine("LEDGER")
        appendLine("members: ${ledger.members.joinToString(", ")}")
        appendLine("expenses:")
        if (ledger.expenses.isEmpty()) {
            appendLine("  (none yet)")
        } else {
            ledger.expenses.takeLast(30).forEach { e ->
                appendLine(
                    "  id=${e.id} | ${e.description} | ${e.amountCents} cents | " +
                        "paid by ${e.paidBy} | split between ${e.participants.joinToString(", ")}",
                )
            }
        }
        val balances = ledger.balances()
        appendLine("balances: " + balances.entries.joinToString(", ") { "${it.key} ${it.value.money()}" })
        appendLine()
        appendLine("REQUEST")
        appendLine(userText)
    }

    companion object {
        internal val SYSTEM_INSTRUCTION = """
            You are Tab, an expense-ledger assistant running fully on the user's phone.
            You do not talk much and you never invent expenses.

            You read the LEDGER block and the user's REQUEST, then answer with a single JSON
            object and nothing else. No markdown, no code fences, no commentary.

            The JSON shape is exactly:
            {"reply": "<one short sentence for the user>",
             "actions": [ <zero or more actions> ]}

            An action is one of:
            {"op":"add","description":"<short label>","amountCents":<integer cents>,"paidBy":"<member>","participants":["<member>",...]}
            {"op":"update","id":"<expense id>","description":"<label>","amountCents":<integer cents>,"paidBy":"<member>","participants":["<member>",...]}
            {"op":"delete","id":"<expense id>"}

            Rules:
            - Amounts are integer cents. 42 dollars 50 is 4250.
            - paidBy and every participant must be an exact member name from the LEDGER.
            - Split evenly between participants unless the user says otherwise.
            - If the user paid and shared the cost ("split it with Ana and Ben"), the payer
              is one of the participants: paidBy "You" means participants ["You","Ana","Ben"].
            - Only leave the payer out of participants when the user is paying for others
              without sharing the cost ("I covered Ana's ticket").
            - If the user only asks a question, return an empty actions list.
            - Never delete or update an id that is not in the LEDGER.
        """.trimIndent()
    }
}
