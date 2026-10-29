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

    companion object {
        internal val SYSTEM_INSTRUCTION = """
            You write tiny self-contained browser games.

            Output ONE complete HTML document and nothing else. No markdown, no code fences,
            no explanation before or after.

            Hard rules:
            - Single file. All CSS and JavaScript inline, inside the document.
            - No external resources: no <script src>, no <link href>, no CDN, no fonts, no images.
            - Use <canvas> for everything you draw. Size it to fill the window.
            - Keep the whole file under about 1200 characters. Short and working beats
              ambitious and broken.
            - Start with <!doctype html> and finish with </html>. Never stop mid-function.

            It must visibly animate and be playable, so:
            - Spawn a new object on a timer (for example every 40 frames) so the screen is
              NEVER empty. Do not rely on a single starting object.
            - Update positions and then redraw, and call requestAnimationFrame at the end of
              your draw function so the loop continues forever.
            - Handle touch: use pointerdown/pointermove/pointerup and also call
              preventDefault so dragging does not scroll the page.
            - Show a visible score as text drawn on the canvas.

            Style: dark background, high contrast, one clear mechanic, immediate feedback.
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
