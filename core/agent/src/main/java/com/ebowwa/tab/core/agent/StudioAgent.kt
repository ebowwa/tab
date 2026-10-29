package com.ebowwa.tab.core.agent

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Generates a self-contained playable HTML document on-device.
 *
 * The model writes the game. Nothing about the mechanics is hardcoded here: the only thing
 * this class injects is a canvas sizer, because this WebView reports a CSS layout viewport
 * 0px tall, so `100vh` and `%` heights collapse and the canvas is clipped away. That fix is
 * mechanical and applies to whatever document the model produced.
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

    /** Cloud engine when a key is configured, otherwise Nano on-device. */
    fun cloudEngine(apiKey: String, modelName: String): PlanEngine =
        GeminiRestEngine(apiKey, modelName)

    suspend fun generate(
        engine: PlanEngine,
        userPrompt: String,
    ): Result<GenerationResult> {
        val result = engine.generate(SYSTEM_INSTRUCTION, userPrompt, maxTokens = 1600)
        result.onSuccess { r -> logMetrics(userPrompt, r, "write", engine) }
        return result
    }

    /** Second pass: hand the model its own broken code plus the runtime error. */
    suspend fun repair(
        engine: PlanEngine,
        originalPrompt: String,
        brokenHtml: String,
        error: String,
    ): Result<GenerationResult> {
        val prompt = buildString {
            appendLine("The game you wrote for \"$originalPrompt\" threw an error when it ran.")
            appendLine("Error: ${error.take(400)}")
            appendLine("Rewrite the whole document so it runs. Output only the HTML.")
            appendLine("Your previous attempt:")
            appendLine(brokenHtml.take(5000))
        }
        val result = engine.generate(SYSTEM_INSTRUCTION, prompt, maxTokens = 1600)
        result.onSuccess { r -> logMetrics("repair", r, "repair", engine) }
        return result
    }

    private fun logMetrics(prompt: String, r: GenerationResult, tag: String, engine: PlanEngine) {
        val tok = if (r.ttftMs > 0L) String.format(Locale.US, "%.0f", r.tokPerSec) else "n/a"
        Log.i(
            "Tab",
            "metrics prompt=\"${prompt.take(100)}\" engine=\"${engine.label}\" tag=$tag ok=true " +
                "promptTokens=${r.promptTokens} outputTokens=${r.outputTokens} " +
                "totalTokens=${r.totalTokens} ttftMs=${r.ttftMs} totalMs=${r.totalMs} tokPerSec=$tok",
        )
    }

    companion object {
        internal val SYSTEM_INSTRUCTION = """
            You write small but real browser games. A game is not circles drifting across a
            screen - it has a goal, a threat, and a reason to keep playing.

            Output ONE complete HTML document and nothing else. No markdown, no code fences.
            Start with <!doctype html>, finish with </html>. Never stop mid-function.

            Hard rules:
            - Single file. All CSS and JavaScript inline. No external resources at all.
            - One <canvas>. Do not size it in CSS; the host sizes it to the window.
            - Up to about 1800 characters.

            The game MUST have all of these, or it is not finished:
            1. A player entity the user controls.
            2. A threat or obstacle that can end the run.
            3. A scoring rule that rewards skill, not just waiting.
            4. A difficulty ramp: something gets faster, denser or harder as score rises.
            5. Visual feedback on every hit: flash, particles, shake or score pop.
            6. A clear game-over state with the final score and a tap-to-restart.
            7. At least two distinct kinds of drawn object (not one circle shape everywhere).

            Mechanics and feel:
            - Update, then draw, then call requestAnimationFrame(draw) as the LAST line of
              draw so the loop never stops.
            - Delta-time or a frame counter for motion, not assumptions about frame rate.
            - Touch and mouse: pointerdown/pointermove/pointerup with preventDefault.
            - Declare every function and variable before you use it.
            - Draw the score and lives on the canvas with fillText.

            Design the mechanic that fits the request. Do not default to catching falling
            objects unless that is genuinely what was asked for.
        """.trimIndent()
    }
}

/**
 * The trust boundary for generated markup, plus the one mechanical fix this WebView needs.
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
            return Verdict(null, "Rejected: generated markup uses \"$hit\", which reaches outside the page.")
        }

        // Detect the premature-</script> failure precisely: strip script/style blocks AND
        // tags, then look for real code constructs in what is left. Matching the bare word
        // "function " rejected valid games whose text happened to contain it.
        val withoutCode = cleaned
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("<[^>]*>"), " ")
        val leaked = Regex("""function\s+[A-Za-z_$][\w$]*\s*\(""").containsMatchIn(withoutCode) ||
            withoutCode.contains("requestAnimationFrame(") ||
            withoutCode.contains("addEventListener(") ||
            Regex("""\bctx\.[a-zA-Z]+\(""").containsMatchIn(withoutCode)
        if (leaked) {
            return Verdict(
                null,
                "Rejected: code leaked outside a <script> tag, so it never runs.",
            )
        }

        if (!cleaned.contains("</html>", ignoreCase = true)) {
            return Verdict(null, "The document was cut off before it finished. Try again, or ask for something simpler.")
        }
        if (cleaned.length > 60_000) {
            return Verdict(null, "Rejected: document is too large (${cleaned.length} chars).")
        }
        return Verdict(harden(cleaned), null)
    }

    /**
     * This WebView reports a 0px-tall CSS layout viewport, so vh/% heights collapse and the
     * canvas is clipped away. Force the canvas to fill the window and stay sized. Purely
     * mechanical: it does not touch the game logic the model wrote.
     */
    private fun harden(html: String): String {
        val css = "<style>canvas{position:fixed!important;top:0!important;left:0!important;" +
            "display:block!important;margin:0!important}</style>"
        val js = "<script>(function(){function f(){var cs=document.getElementsByTagName('canvas');" +
            "for(var i=0;i<cs.length;i++){var c=cs[i];if(c.width!==innerWidth||c.height!==innerHeight){" +
            "c.width=innerWidth;c.height=innerHeight;c.style.width=innerWidth+'px';" +
            "c.style.height=innerHeight+'px';}}requestAnimationFrame(f);}requestAnimationFrame(f);})();</script>"

        var out = html
        out = if (out.contains("</head>", ignoreCase = true)) {
            out.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), css + "</head>")
        } else {
            css + out
        }
        out = if (out.contains("</body>", ignoreCase = true)) {
            out.replaceFirst(Regex("</body>", RegexOption.IGNORE_CASE), js + "</body>")
        } else {
            out.replaceFirst(Regex("</html>", RegexOption.IGNORE_CASE), js + "</html>")
        }
        return out
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
