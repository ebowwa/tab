package com.ebowwa.tab.core.agent

import android.os.SystemClock
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * One inference: the text plus what it cost.
 *
 * [ttftMs] is time-to-first-token (prefill). [tokPerSec] is decode throughput — output
 * tokens over the time actually spent decoding, i.e. total minus prefill. It is NOT
 * output-tokens-over-wall-clock, which would understate throughput on long prompts.
 */
data class GenerationResult(
    val text: String,
    val promptTokens: Int = -1,
    val outputTokens: Int = -1,
    val totalTokens: Int = -1,
    val ttftMs: Long = -1L,
    val totalMs: Long = -1L,
) {
    val decodeMs: Long get() = if (ttftMs > 0L) (totalMs - ttftMs).coerceAtLeast(0L) else totalMs

    val tokPerSec: Double
        get() {
            if (outputTokens <= 0 || decodeMs <= 0L) return 0.0
            return outputTokens * 1000.0 / decodeMs
        }
}

/**
 * One prompt in, one raw model answer out. The planning contract, the validation and the
 * staging all live above this, so the same agent runs on-device or in the cloud unchanged.
 */
interface PlanEngine {
    val label: String
    val isOnDevice: Boolean

    suspend fun generate(
        systemInstruction: String,
        prompt: String,
        maxTokens: Int = 512,
    ): Result<GenerationResult>
}

/** Gemini Nano through ML Kit GenAI + AICore. Nothing leaves the phone. */
class NanoEngine(private val model: GenerativeModel) : PlanEngine {
    override val label: String = "Gemini Nano · on-device"
    override val isOnDevice: Boolean = true

    override suspend fun generate(
        systemInstruction: String,
        prompt: String,
        maxTokens: Int,
    ): Result<GenerationResult> = runCatching {
        // ML Kit keeps an implicit session and cache across calls, so a new prompt was being
        // answered in the context of the previous game (ask for hamburgers, get fish). Give
        // every request its own session and drop the implicit cache.
        runCatching { model.clearImplicitCaches() }

        val request = generateContentRequest(
            SystemInstruction(systemInstruction),
            TextPart(prompt),
        ) {
            temperature = 0.0f
            maxOutputTokens = maxTokens
            sessionId = UUID.randomUUID().toString()
        }

        val promptTokens = runCatching { model.countTokens(request).totalTokens }.getOrDefault(-1)

        val startedAt = SystemClock.elapsedRealtime()
        // The callback fires on another thread; plain vars here were a data race.
        val firstTokenAt = AtomicLong(-1L)
        val streamed = StringBuilder()

        // Stream so we can separate prefill from decode: the first callback is TTFT.
        val callback = object : StreamingCallback {
            override fun onNewText(text: String) {
                firstTokenAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
                synchronized(streamed) { streamed.append(text) }
            }
        }

        val response = model.generateContent(request, callback)
        val totalMs = SystemClock.elapsedRealtime() - startedAt

        val text = synchronized(streamed) { streamed.toString() }.ifEmpty {
            response.candidates.firstOrNull()?.text.orEmpty()
        }

        val outputTokens = if (text.isEmpty()) {
            0
        } else {
            runCatching {
                model.countTokens(generateContentRequest(TextPart(text)) { }).totalTokens
            }.getOrDefault(-1)
        }

        GenerationResult(
            text = text,
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            totalTokens = if (promptTokens >= 0 && outputTokens >= 0) promptTokens + outputTokens else -1,
            ttftMs = firstTokenAt.get().takeIf { it >= 0L }?.minus(startedAt) ?: -1L,
            totalMs = totalMs,
        )
    }
}

/**
 * Gemini Developer API over plain REST. Used when the device has no AICore model —
 * same instructions, same JSON contract, so the agent behaviour does not change.
 */
class GeminiRestEngine(
    private val apiKey: String,
    private val modelName: String = DEFAULT_MODEL,
) : PlanEngine {
    override val label: String = "$modelName · cloud"
    override val isOnDevice: Boolean = false

    override suspend fun generate(
        systemInstruction: String,
        prompt: String,
        maxTokens: Int,
    ): Result<GenerationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$ENDPOINT/$modelName:generateContent")
            val body = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0)
                        .put("maxOutputTokens", maxTokens)
                        .put("responseMimeType", "application/json"),
                )
            }

            val startedAt = SystemClock.elapsedRealtime()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 20_000
                readTimeout = 40_000
                doOutput = true
            }

            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val totalMs = SystemClock.elapsedRealtime() - startedAt

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${text.take(300)}")
            }

            val json = JSONObject(text)
            val parts = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")

            val answer = buildString {
                for (i in 0 until (parts?.length() ?: 0)) {
                    append(parts?.optJSONObject(i)?.optString("text").orEmpty())
                }
            }

            // The REST API reports real token counts, so no local counting is needed.
            val usage = json.optJSONObject("usageMetadata")
            val promptTokens = usage?.optInt("promptTokenCount", -1) ?: -1
            val outputTokens = usage?.optInt("candidatesTokenCount", -1) ?: -1
            val totalTokens = usage?.optInt("totalTokenCount", -1) ?: -1

            GenerationResult(
                text = answer,
                promptTokens = promptTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
                // No streaming here, so prefill is not separable from decode.
                ttftMs = -1L,
                totalMs = totalMs,
            )
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}
