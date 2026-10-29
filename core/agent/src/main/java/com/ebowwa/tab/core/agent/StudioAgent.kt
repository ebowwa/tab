package com.ebowwa.tab.core.agent

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Generates a self-contained playable HTML document on-device.
 *
 * Same engine and same metrics as the ledger agent — only the instructions change. The
 * model output is treated as untrusted input: [HtmlValidator] decides whether it is safe
 * to hand to a WebView.
 */
class StudioAgent {

    private val model: GenerativeModel = Generation.getClient()
    val engine: PlanEngine = NanoEngine(model)

    suspend fun status(): ModelStatus = runCatching {
        when (model.checkStatus()) {
            0 -> ModelStatus.UNAVAILABLE
            1 -> ModelStatus.DOWNLOADABLE
            2 -> ModelStatus.DOWNLOADING
            3 -> ModelStatus.AVAILABLE
            else -> ModelStatus.UNKNOWN
        }
    }.getOrElse { ModelStatus.UNAVAILABLE }

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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The model designs the game; [GameTemplate] builds it. Output is a handful of fields
     * rather than a program, which is the only shape this model gets right consistently.
     */
    suspend fun design(userPrompt: String): Result<Pair<GameSpec, GenerationResult>> {
        android.util.Log.i("Tab", "design: \"${userPrompt.take(80)}\"")
        return engine.generate(DESIGN_INSTRUCTION, userPrompt, maxTokens = 256).map { r ->
            android.util.Log.i("Tab", "design raw: ${r.text.take(200)}")
            val start = r.text.indexOf('{')
            val end = r.text.lastIndexOf('}')
            val spec = if (start >= 0 && end > start) {
                runCatching { json.decodeFromString<GameSpec>(r.text.substring(start, end + 1)) }
                    .getOrDefault(GameSpec())
            } else {
                GameSpec()
            }
            GameTemplate.sanitise(spec) to r
        }
    }

    suspend fun generate(userPrompt: String): Result<GenerationResult> {
        // 512 was truncating games mid-function; nano-v4 has a 32k window.
        val result = engine.generate(SYSTEM_INSTRUCTION, userPrompt, maxTokens = 2048)
        result.onSuccess { r ->
            val tok = if (r.ttftMs > 0L) String.format(Locale.US, "%.1f", r.tokPerSec) else "n/a"
            Log.i(
                "Tab",
                "metrics prompt=\"${userPrompt.take(120)}\" engine=\"${engine.label}\" ok=true " +
                    "promptTokens=${r.promptTokens} outputTokens=${r.outputTokens} " +
                    "totalTokens=${r.totalTokens} ttftMs=${r.ttftMs} totalMs=${r.totalMs} tokPerSec=$tok",
            )
        }
        return result
    }

    /**
     * Second pass: hand the model its own broken code plus the runtime error and ask for a
     * complete rewrite. On-device generation is single-shot otherwise, and a model this size
     * gets state machines wrong without seeing the failure.
     */
    suspend fun repair(originalPrompt: String, brokenHtml: String, error: String): Result<GenerationResult> {
        val prompt = buildString {
            appendLine("The game you wrote for \"$originalPrompt\" threw an error when it ran.")
            appendLine("Error: ${error.take(400)}")
            appendLine("Rewrite the whole document so it runs. Output only the HTML.")
            appendLine("Your previous attempt:")
            appendLine(brokenHtml.take(4000))
        }
        return engine.generate(SYSTEM_INSTRUCTION, prompt, maxTokens = 2048)
    }

    companion object {
        internal val DESIGN_INSTRUCTION = """
            You design a tiny arcade game. Answer with ONE JSON object and nothing else.

            {"kind":"catcher|dodger|shooter|whack","title":"<max 3 words>",
             "objectName":"<what falls or is hit>","background":"#rrggbb","accent":"#rrggbb",
             "speed":<1-6>,"spawnRate":<20-120>,"playerWidth":<50-160>}

            Pick the kind that fits the request best:
            - catcher: move a paddle to CATCH falling things
            - dodger: move to AVOID falling things
            - shooter: tap to SHOOT descending things
            - whack: TAP things that appear and vanish

            Pick colours that suit the mood. Higher speed and lower spawnRate means harder.
            Match the theme in the objectName and colours.
        """.trimIndent()

        internal val SYSTEM_INSTRUCTION = """
            You write tiny self-contained browser games.

            Output ONE complete HTML document and nothing else. No markdown, no code fences,
            no explanation before or after.

            Hard rules:
            - Single file. All CSS and JavaScript inline, inside the document.
            - No external resources of any kind.
            - One <canvas> sized to fill the window.
            - Under about 700 characters total. Short and working beats ambitious and broken.
            - Start with <!doctype html> and finish with </html>. Never stop mid-function.

            One mechanic only. It must run and animate immediately:
            - Spawn new objects on a timer so the screen is never empty.
            - Update positions, then redraw, then call requestAnimationFrame(draw) as the
              last line of draw so the loop never stops.
            - Touch and mouse both: pointerdown/pointermove/pointerup, with preventDefault.
            - Draw a score on the canvas with fillText.
            - Define every function before you call it, and never reference a variable
              before it is declared.
        """.trimIndent()
    }
}

/**
 * The trust boundary for generated markup.
 *
 * The model is not trusted to emit something a WebView should run. Reject anything that
 * reaches outside the document, and normalise away code fences the model adds anyway.
 */
object HtmlValidator {

    data class Verdict(val html: String?, val reason: String?)

    private val forbidden = listOf(
        "<script src", "<link ", "http://", "https://", "import(", "eval(", "fetch(",
        "XMLHttpRequest", "document.cookie", "localStorage", "indexedDB",
    )

    fun validate(raw: String): Verdict {
        val cleaned = stripFences(raw)
        if (cleaned.isBlank()) return Verdict(null, "The model returned nothing.")
        if (!cleaned.contains("<html", ignoreCase = true) && !cleaned.contains("<!doctype", ignoreCase = true)) {
            return Verdict(null, "That is not an HTML document.")
        }
        val lower = cleaned.lowercase(Locale.US)
        val hit = forbidden.firstOrNull { lower.contains(it) }
        if (hit != null) {
            return Verdict(null, "Rejected: generated markup uses \"$hit\", which can reach outside the page.")
        }
        // The model sometimes closes <script> mid-code, leaving the rest of the game as
        // inert text. That passes every structural check yet never runs, so detect code
        // sitting outside a script block.
        val withoutCode = cleaned
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
        val strayCode = listOf("function ", "requestAnimationFrame", "ctx.", "addEventListener", "let ", "const ")
            .firstOrNull { withoutCode.contains(it) }
        if (strayCode != null) {
            return Verdict(
                null,
                "Rejected: generated code leaked outside a <script> tag (\"$strayCode\"), so it never runs.",
            )
        }

        if (!cleaned.contains("</html>", ignoreCase = true)) {
            return Verdict(null, "The document was cut off before it finished. Try again, or ask for something simpler.")
        }
        if (cleaned.length > 40_000) {
            return Verdict(null, "Rejected: document is too large (${cleaned.length} chars).")
        }
        return Verdict(cleaned, null)
    }

    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```html").removePrefix("```").trim()
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end).trim()
        }
        val start = s.indexOf("<!doctype", ignoreCase = true).takeIf { it >= 0 }
            ?: s.indexOf("<html", ignoreCase = true).takeIf { it >= 0 }
            ?: return s
        val endTag = s.lastIndexOf("</html>", ignoreCase = true)
        val end = if (endTag > start) endTag + "</html>".length else s.length
        return s.substring(start, end).trim()
    }
}
