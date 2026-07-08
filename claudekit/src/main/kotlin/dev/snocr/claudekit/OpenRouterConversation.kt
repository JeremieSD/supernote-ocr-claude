package dev.snocr.claudekit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64

/**
 * OpenRouter client using the OpenAI-compatible chat/completions API, so any
 * multimodal model on OpenRouter can read the notebook page images. Cheaper
 * than the Anthropic models for OCR-style work.
 */
internal class OpenRouterConversation(
    config: AiConfig,
    httpClient: OkHttpClient?,
    systemPrompt: String,
) : BaseConversation(config, httpClient, systemPrompt) {

    override fun userMessage(
        questionText: String,
        questionImage: ByteArray?,
        contextAttachments: List<Attachment>,
        firstTurn: Boolean,
    ): JsonObject {
        val parts = buildJsonArray {
            if (firstTurn) {
                contextAttachments.forEach { attachment ->
                    if (attachment.isImage) add(imagePart(attachment.mediaType, attachment.data))
                    // Non-image context (PDF) is rasterized to images by the app
                    // before it reaches this provider.
                }
            }
            if (questionImage != null) add(imagePart("image/png", questionImage))
            addJsonObject {
                put("type", "text")
                put("text", questionText)
            }
        }
        return buildJsonObject {
            put("role", "user")
            put("content", parts)
        }
    }

    private fun imagePart(mediaType: String, data: ByteArray): JsonObject = buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") {
            put("url", "data:$mediaType;base64," + Base64.getEncoder().encodeToString(data))
        }
    }

    override fun buildRequest(messages: JsonArray): Request {
        val body = buildJsonObject {
            put("model", config.modelId)
            put("max_tokens", config.maxTokens)
            put("stream", true)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
                messages.forEach { add(it) }
            }
        }
        return Request.Builder()
            .url(config.openRouterBaseUrl.trimEnd('/') + "/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Title", "Claude Notes")
            .header("HTTP-Referer", "https://github.com/JeremieSD/supernote-ocr-claude")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun parseStream(response: Response, onDelta: (String) -> Unit): StreamOutcome {
        val source = response.body?.source() ?: throw AiApiException("empty response body")
        val text = StringBuilder()
        var finishReason: String? = null
        var servedBy: String? = null
        var complete = false

        readSse(source) { _, data ->
            if (data == "[DONE]") {
                complete = true
                return@readSse
            }
            val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject
                ?: return@readSse
            (obj["error"] as? JsonObject)?.let { error ->
                throw AiApiException(
                    error["message"]?.jsonPrimitive?.contentOrNull ?: "stream error",
                    errorType = error["code"]?.jsonPrimitive?.contentOrNull,
                )
            }
            servedBy = obj["model"]?.jsonPrimitive?.contentOrNull ?: servedBy
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@readSse
            choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.let { chunk ->
                if (chunk.isNotEmpty()) {
                    text.append(chunk)
                    onDelta(chunk)
                }
            }
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
                finishReason = it
                complete = true
            }
        }

        if (!complete) {
            throw IOException("stream ended before the answer was complete")
        }

        val stopReason = when (finishReason) {
            "length" -> "max_tokens"
            "content_filter" -> "refusal"
            "stop" -> "end_turn"
            else -> finishReason
        }
        val refusalExplanation =
            if (stopReason == "refusal") "the provider filtered this content" else null
        val assistant =
            if (text.isEmpty() || stopReason == "refusal") null
            else buildJsonObject {
                put("role", "assistant")
                put("content", text.toString())
            }
        return StreamOutcome(text.toString(), stopReason, refusalExplanation, servedBy, assistant)
    }

    companion object {
        fun testApiKey(config: AiConfig, httpClient: OkHttpClient?): String? {
            val client = httpClient ?: AiConversation.defaultHttpClient()
            val request = Request.Builder()
                .url(config.openRouterBaseUrl.trimEnd('/') + "/v1/key")
                .header("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> null
                        response.code == 401 || response.code == 403 -> "Invalid API key"
                        else -> "Couldn't verify key (HTTP ${response.code}); it may still work."
                    }
                }
            } catch (e: IOException) {
                "Network error: ${e.message}"
            }
        }
    }
}
