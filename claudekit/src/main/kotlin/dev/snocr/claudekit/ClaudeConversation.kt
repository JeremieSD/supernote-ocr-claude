package dev.snocr.claudekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A page image or document attached to the first question of a conversation. */
class Attachment private constructor(val mediaType: String, val data: ByteArray) {
    val isDocument: Boolean get() = mediaType == "application/pdf"

    companion object {
        fun png(data: ByteArray) = Attachment("image/png", data)
        fun jpeg(data: ByteArray) = Attachment("image/jpeg", data)
        fun webp(data: ByteArray) = Attachment("image/webp", data)
        fun pdf(data: ByteArray) = Attachment("application/pdf", data)
    }
}

/**
 * A multi-turn conversation about a set of notebook page images (or PDFs),
 * streamed over the Anthropic Messages API.
 *
 * Attachments are sent once with the first question (with a prompt-cache
 * breakpoint after the last one, so follow-up questions reuse the cached
 * image tokens); follow-ups send text only.
 *
 * ask() is blocking and must not be called concurrently; cancel() may be
 * called from any thread.
 */
class ClaudeConversation(
    private val config: ClaudeConfig,
    httpClient: OkHttpClient? = null,
    private val systemPrompt: String = Prompts.SYSTEM,
) {
    private val client: OkHttpClient = httpClient ?: defaultHttpClient()
    private val history = mutableListOf<JsonObject>()
    private val historyLock = Any()
    private val activeCall = AtomicReference<Call?>(null)
    private val json = Json { ignoreUnknownKeys = true }

    val turnCount: Int get() = synchronized(historyLock) { history.size / 2 }

    /**
     * Sends [question] (with [attachments] on the first turn only) and
     * streams the answer. Blocking; call from a background thread.
     *
     * On any failure (HTTP error, stream error, truncated stream, refusal)
     * the question is rolled back so the conversation stays consistent and
     * the next ask() retries cleanly.
     *
     * @param attachments page images or PDFs; only used when the
     *   conversation is empty.
     * @param onDelta invoked with each streamed text fragment.
     */
    @Throws(ClaudeApiException::class, IOException::class)
    fun ask(
        question: String,
        attachments: List<Attachment> = emptyList(),
        onDelta: (String) -> Unit = {},
    ): TurnResult {
        val userTurn = buildUserTurn(question, attachments)
        val messages = synchronized(historyLock) {
            history.add(userTurn)
            JsonArray(history.toList())
        }

        val outcome = try {
            executeStream(messages, onDelta)
        } catch (e: Exception) {
            synchronized(historyLock) { history.remove(userTurn) }
            throw e
        }

        // Commit or roll back the turn. A refused (or block-less) turn cannot
        // be continued, so it is dropped and the conversation stays usable.
        val echoBlocks = buildEchoBlocks(outcome)
        synchronized(historyLock) {
            if (echoBlocks.isNotEmpty() && outcome.stopReason != "refusal") {
                history.add(buildJsonObject {
                    put("role", "assistant")
                    put("content", JsonArray(echoBlocks))
                })
            } else {
                history.remove(userTurn)
            }
        }

        val fullText = outcome.blocks.filter { it.type == "text" }
            .joinToString("") { it.text.toString() }
        return TurnResult(fullText, outcome.stopReason, outcome.refusalExplanation, outcome.servedBy)
    }

    /** Cancels any in-flight request. */
    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private fun buildUserTurn(question: String, attachments: List<Attachment>): JsonObject {
        val firstTurn = synchronized(historyLock) { history.isEmpty() }
        val userContent = buildJsonArray {
            if (firstTurn) {
                attachments.forEachIndexed { index, attachment ->
                    add(buildJsonObject {
                        put("type", if (attachment.isDocument) "document" else "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", attachment.mediaType)
                            put("data", Base64.getEncoder().encodeToString(attachment.data))
                        }
                        if (index == attachments.size - 1) {
                            // Cache breakpoint: follow-up questions reuse the
                            // (expensive) image prefix at ~10% of the cost.
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        }
                    })
                }
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", question)
            })
        }
        return buildJsonObject {
            put("role", "user")
            put("content", userContent)
        }
    }

    private fun executeStream(messages: JsonArray, onDelta: (String) -> Unit): StreamOutcome {
        val body = buildJsonObject {
            put("model", config.model.id)
            put("max_tokens", config.maxTokens)
            put("stream", true)
            put("system", systemPrompt)
            if (config.model.supportsAdaptiveThinking) {
                putJsonObject("thinking") { put("type", "adaptive") }
            }
            if (config.model.needsRefusalFallback) {
                put("fallbacks", buildJsonArray {
                    add(buildJsonObject { put("model", ClaudeModel.OPUS_4_8.id) })
                })
            }
            put("messages", messages)
        }

        val requestBuilder = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (config.model.needsRefusalFallback) {
            requestBuilder.header("anthropic-beta", "server-side-fallback-2026-06-01")
        }

        val call = client.newCall(requestBuilder.build())
        activeCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw apiError(response.code, response.body?.string())
                }
                return consumeStream(response, onDelta)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun consumeStream(response: okhttp3.Response, onDelta: (String) -> Unit): StreamOutcome {
        val source = response.body?.source() ?: throw ClaudeApiException("empty response body")
        val outcome = StreamOutcome()

        var eventName: String? = null
        val dataLines = StringBuilder()

        fun handleEvent(name: String?, data: String) {
            if (data.isBlank()) return
            val element = try {
                json.parseToJsonElement(data)
            } catch (e: Exception) {
                return
            }
            val obj = element as? JsonObject ?: return
            when (name ?: obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    outcome.servedBy = obj["message"]?.jsonObject?.get("model")
                        ?.jsonPrimitive?.contentOrNull
                }
                "content_block_start" -> {
                    val block = obj["content_block"]?.jsonObject ?: return
                    val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return
                    if (type == "fallback") {
                        // A server-side fallback took over mid-turn; the rest
                        // of the response is served by the fallback model.
                        block["to"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                            ?.let { outcome.servedBy = it }
                    }
                    outcome.blocks.add(BlockAccumulator(type, block))
                }
                "content_block_delta" -> {
                    val delta = obj["delta"]?.jsonObject ?: return
                    val block = outcome.blocks.lastOrNull() ?: return
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            block.text.append(text)
                            onDelta(text)
                        }
                        "thinking_delta" -> {
                            block.thinking.append(
                                delta["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                            )
                        }
                        "signature_delta" -> {
                            block.signature.append(
                                delta["signature"]?.jsonPrimitive?.contentOrNull ?: ""
                            )
                        }
                    }
                }
                "message_delta" -> {
                    val deltaObj = obj["delta"]?.jsonObject
                    outcome.stopReason = deltaObj?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                        ?: outcome.stopReason
                    val stopDetails = deltaObj?.get("stop_details") as? JsonObject
                    outcome.refusalExplanation = stopDetails?.get("explanation")
                        ?.jsonPrimitive?.contentOrNull ?: outcome.refusalExplanation
                }
                "message_stop" -> outcome.complete = true
                "error" -> {
                    val error = obj["error"]?.jsonObject
                    throw ClaudeApiException(
                        error?.get("message")?.jsonPrimitive?.contentOrNull ?: "stream error",
                        errorType = error?.get("type")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    handleEvent(eventName, dataLines.toString())
                    eventName = null
                    dataLines.setLength(0)
                }
                line.startsWith(":") -> Unit // SSE comment / keep-alive
                line.startsWith("event:") -> eventName = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (dataLines.isNotEmpty()) dataLines.append('\n')
                    dataLines.append(line.substring(5).trim())
                }
            }
        }
        if (dataLines.isNotEmpty()) handleEvent(eventName, dataLines.toString())

        if (!outcome.complete) {
            // The connection ended before message_stop: the answer is
            // incomplete and must not be committed to the conversation.
            throw IOException("stream ended before the answer was complete")
        }
        return outcome
    }

    /**
     * Blocks to echo back as the assistant turn for follow-up questions.
     * Thinking blocks are passed back unchanged; when a server-side fallback
     * occurred mid-turn, thinking blocks before the boundary are dropped
     * (per the API's fallback echo rules), as are the marker blocks.
     */
    private fun buildEchoBlocks(outcome: StreamOutcome): List<JsonObject> {
        val lastFallbackIndex = outcome.blocks.indexOfLast { it.type == "fallback" }
        return outcome.blocks.mapIndexedNotNull { index, block ->
            when (block.type) {
                "text" -> buildJsonObject {
                    put("type", "text")
                    put("text", block.text.toString())
                }
                "thinking" ->
                    if (lastFallbackIndex >= 0 && index < lastFallbackIndex) null
                    else buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking.toString())
                        put("signature", block.signature.toString())
                    }
                "redacted_thinking" ->
                    if (lastFallbackIndex >= 0 && index < lastFallbackIndex) null
                    else block.raw
                else -> null // fallback markers and unknown block types
            }
        }
    }

    private fun apiError(code: Int, body: String?): ClaudeApiException {
        val parsed = try {
            body?.let { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject }
        } catch (e: Exception) {
            null
        }
        val message = parsed?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "HTTP $code from Claude API"
        val type = parsed?.get("type")?.jsonPrimitive?.contentOrNull
        return ClaudeApiException(friendlyMessage(code, message), errorType = type, httpStatus = code)
    }

    private fun friendlyMessage(code: Int, message: String): String = when (code) {
        401 -> "Invalid API key. Check Settings. ($message)"
        413 -> "Request too large - select fewer pages. ($message)"
        429 -> "Rate limited by the API. Wait a moment and retry. ($message)"
        529 -> "Claude API is overloaded. Retry shortly. ($message)"
        else -> message
    }

    private class StreamOutcome {
        val blocks = mutableListOf<BlockAccumulator>()
        var stopReason: String? = null
        var refusalExplanation: String? = null
        var servedBy: String? = null
        var complete = false
    }

    private class BlockAccumulator(val type: String, val raw: JsonObject) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val signature = StringBuilder()
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Long read timeout: with thinking enabled the stream can go
            // quiet for a while before output begins.
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()

        /**
         * Cheap API-key validation: lists one model. Returns null on success
         * or a human-readable error.
         */
        fun testApiKey(config: ClaudeConfig, httpClient: OkHttpClient? = null): String? {
            val client = httpClient ?: defaultHttpClient()
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/v1/models?limit=1")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) null
                    else when (response.code) {
                        401 -> "Invalid API key"
                        else -> "API error HTTP ${response.code}"
                    }
                }
            } catch (e: IOException) {
                "Network error: ${e.message}"
            }
        }
    }
}
