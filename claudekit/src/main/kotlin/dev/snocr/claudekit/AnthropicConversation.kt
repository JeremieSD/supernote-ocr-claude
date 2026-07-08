package dev.snocr.claudekit

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64

/** Anthropic Messages API client: SSE streaming, vision, prompt caching. */
internal class AnthropicConversation(
    config: AiConfig,
    httpClient: OkHttpClient?,
    systemPrompt: String,
) : BaseConversation(config, httpClient, systemPrompt) {

    private val model = Models.anthropicModel(config.modelId)
    private val adaptiveThinking = model?.supportsAdaptiveThinking ?: false
    private val refusalFallback = model?.needsRefusalFallback ?: false

    override fun userMessage(
        questionText: String,
        questionImage: ByteArray?,
        contextAttachments: List<Attachment>,
        firstTurn: Boolean,
    ): JsonObject {
        val content = buildJsonArray {
            if (firstTurn) {
                contextAttachments.forEachIndexed { index, attachment ->
                    add(attachmentBlock(attachment, cache = index == contextAttachments.size - 1))
                }
            }
            // The handwritten question image is sent every turn, after the
            // cache breakpoint so it never invalidates the cached page prefix.
            if (questionImage != null) {
                add(attachmentBlock(Attachment.png(questionImage), cache = false))
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", questionText)
            })
        }
        return buildJsonObject {
            put("role", "user")
            put("content", content)
        }
    }

    private fun attachmentBlock(attachment: Attachment, cache: Boolean): JsonObject =
        buildJsonObject {
            put("type", if (attachment.isDocument) "document" else "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", attachment.mediaType)
                put("data", Base64.getEncoder().encodeToString(attachment.data))
            }
            if (cache) putJsonObject("cache_control") { put("type", "ephemeral") }
        }

    override fun buildRequest(messages: JsonArray): Request {
        val body = buildJsonObject {
            put("model", config.modelId)
            put("max_tokens", config.maxTokens)
            put("stream", true)
            put("system", systemPrompt)
            if (adaptiveThinking) putJsonObject("thinking") { put("type", "adaptive") }
            if (refusalFallback) {
                put("fallbacks", buildJsonArray {
                    add(buildJsonObject { put("model", "claude-opus-4-8") })
                })
            }
            put("messages", messages)
        }
        val builder = Request.Builder()
            .url(config.anthropicBaseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (refusalFallback) {
            builder.header("anthropic-beta", "server-side-fallback-2026-06-01")
        }
        return builder.build()
    }

    override fun parseStream(response: Response, onDelta: (String) -> Unit): StreamOutcome {
        val source = response.body?.source() ?: throw AiApiException("empty response body")
        val blocks = mutableListOf<BlockAccumulator>()
        var stopReason: String? = null
        var refusalExplanation: String? = null
        var servedBy: String? = null
        var complete = false

        readSse(source) { name, data ->
            val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject
                ?: return@readSse
            when (name ?: obj["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" ->
                    servedBy = obj["message"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                "content_block_start" -> {
                    val block = obj["content_block"]?.jsonObject ?: return@readSse
                    val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return@readSse
                    if (type == "fallback") {
                        block["to"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                            ?.let { servedBy = it }
                    }
                    blocks.add(BlockAccumulator(type, block))
                }
                "content_block_delta" -> {
                    val delta = obj["delta"]?.jsonObject ?: return@readSse
                    val block = blocks.lastOrNull() ?: return@readSse
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            block.text.append(text)
                            onDelta(text)
                        }
                        "thinking_delta" ->
                            block.thinking.append(delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "")
                        "signature_delta" ->
                            block.signature.append(delta["signature"]?.jsonPrimitive?.contentOrNull ?: "")
                    }
                }
                "message_delta" -> {
                    val deltaObj = obj["delta"]?.jsonObject
                    stopReason = deltaObj?.get("stop_reason")?.jsonPrimitive?.contentOrNull ?: stopReason
                    val stopDetails = deltaObj?.get("stop_details") as? JsonObject
                    refusalExplanation =
                        stopDetails?.get("explanation")?.jsonPrimitive?.contentOrNull ?: refusalExplanation
                }
                "message_stop" -> complete = true
                "error" -> {
                    val error = obj["error"]?.jsonObject
                    throw AiApiException(
                        error?.get("message")?.jsonPrimitive?.contentOrNull ?: "stream error",
                        errorType = error?.get("type")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        }

        if (!complete) {
            throw IOException("stream ended before the answer was complete")
        }

        val fullText = blocks.filter { it.type == "text" }.joinToString("") { it.text.toString() }
        val assistant = buildAssistantMessage(blocks, stopReason)
        return StreamOutcome(fullText, stopReason, refusalExplanation, servedBy, assistant)
    }

    /**
     * The assistant turn to echo back for follow-ups, or null when the turn
     * was refused/empty and cannot be continued. Thinking blocks are echoed
     * unchanged; when a mid-stream fallback occurred, blocks before the
     * boundary (and the marker itself) are dropped per the API's echo rules.
     */
    private fun buildAssistantMessage(blocks: List<BlockAccumulator>, stopReason: String?): JsonObject? {
        val lastFallbackIndex = blocks.indexOfLast { it.type == "fallback" }
        val echo = blocks.mapIndexedNotNull { index, block ->
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
                    if (lastFallbackIndex >= 0 && index < lastFallbackIndex) null else block.raw
                else -> null
            }
        }
        if (echo.isEmpty() || stopReason == "refusal") return null
        return buildJsonObject {
            put("role", "assistant")
            put("content", JsonArray(echo))
        }
    }

    private class BlockAccumulator(val type: String, val raw: JsonObject) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val signature = StringBuilder()
    }

    companion object {
        fun testApiKey(config: AiConfig, httpClient: OkHttpClient?): String? {
            val client = httpClient ?: AiConversation.defaultHttpClient()
            val request = Request.Builder()
                .url(config.anthropicBaseUrl.trimEnd('/') + "/v1/models?limit=1")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) null
                    else if (response.code == 401) "Invalid API key"
                    else "API error HTTP ${response.code}"
                }
            } catch (e: IOException) {
                "Network error: ${e.message}"
            }
        }
    }
}
