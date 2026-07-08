package dev.snocr.claudekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Which backend serves the requests. */
enum class AiProvider(val displayName: String) {
    ANTHROPIC("Anthropic (Claude)"),
    OPENROUTER("OpenRouter (cheaper models)"),
}

/** A selectable model for the settings UI. */
class ModelOption(
    val id: String,
    val displayName: String,
    val provider: AiProvider,
    /** Anthropic only: send `thinking: {type: adaptive}`. Ignored elsewhere. */
    val supportsAdaptiveThinking: Boolean = false,
    /** Anthropic only: Fable 5 needs opt-in server-side refusal fallbacks. */
    val needsRefusalFallback: Boolean = false,
)

object Models {
    val ANTHROPIC = listOf(
        ModelOption("claude-opus-4-8", "Claude Opus 4.8 (recommended)", AiProvider.ANTHROPIC, true, false),
        ModelOption("claude-sonnet-5", "Claude Sonnet 5 (faster)", AiProvider.ANTHROPIC, true, false),
        ModelOption("claude-haiku-4-5", "Claude Haiku 4.5 (cheapest)", AiProvider.ANTHROPIC, false, false),
        ModelOption("claude-fable-5", "Claude Fable 5 (most capable)", AiProvider.ANTHROPIC, true, true),
    )

    // OpenRouter model ids change over time and new ones appear, so the app
    // also lets the user type a custom id. These are well-known multimodal
    // (vision) models suitable for reading handwriting, cheapest first.
    val OPENROUTER = listOf(
        ModelOption("google/gemini-2.0-flash-001", "Gemini 2.0 Flash — cheapest, vision", AiProvider.OPENROUTER),
        ModelOption("google/gemini-2.5-flash", "Gemini 2.5 Flash — vision", AiProvider.OPENROUTER),
        ModelOption("openai/gpt-4o-mini", "GPT-4o mini — vision", AiProvider.OPENROUTER),
        ModelOption("anthropic/claude-3.5-sonnet", "Claude 3.5 Sonnet (via OpenRouter)", AiProvider.OPENROUTER),
        ModelOption("qwen/qwen2.5-vl-72b-instruct", "Qwen2.5-VL 72B — vision", AiProvider.OPENROUTER),
    )

    fun forProvider(provider: AiProvider): List<ModelOption> =
        if (provider == AiProvider.ANTHROPIC) ANTHROPIC else OPENROUTER

    val DEFAULT_ANTHROPIC = ANTHROPIC.first()
    val DEFAULT_OPENROUTER = OPENROUTER.first()

    fun anthropicModel(id: String?): ModelOption? = ANTHROPIC.firstOrNull { it.id == id }
}

class AiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val modelId: String,
    val maxTokens: Int = 32_000,
    val anthropicBaseUrl: String = "https://api.anthropic.com",
    val openRouterBaseUrl: String = "https://openrouter.ai/api",
)

/** A page image or document supplied as conversation context. */
class Attachment private constructor(val mediaType: String, val data: ByteArray) {
    val isDocument: Boolean get() = mediaType == "application/pdf"
    val isImage: Boolean get() = mediaType.startsWith("image/")

    companion object {
        fun png(data: ByteArray) = Attachment("image/png", data)
        fun jpeg(data: ByteArray) = Attachment("image/jpeg", data)
        fun webp(data: ByteArray) = Attachment("image/webp", data)
        fun pdf(data: ByteArray) = Attachment("application/pdf", data)
    }
}

/** Outcome of one streamed assistant turn. */
class TurnResult(
    val text: String,
    val stopReason: String?,
    /** Explanation when the model declined the request. */
    val refusalExplanation: String?,
    /** Model that actually served the response (may differ from the request). */
    val servedBy: String?,
)

class AiApiException(
    message: String,
    val errorType: String? = null,
    val httpStatus: Int? = null,
) : Exception(message)

object Prompts {
    val SYSTEM = """
        You are an assistant running on a Supernote Manta e-ink tablet. The user sends you
        images of their handwritten notebook pages together with a question. Read the
        handwriting carefully - it may be messy, use abbreviations, diagrams, arrows,
        tables, or mixed languages - and answer the question based on what is actually
        written on the pages.

        Guidelines:
        - Ground every answer in the page content. When you quote the notes, transcribe
          the handwriting faithfully.
        - If a word is illegible, say so with your best guess in brackets, like [unclear:
          possibly "meeting"].
        - If the answer is not in the notes, say that plainly instead of inventing one.
        - Pages are numbered in the order given. Reference them like (p.2) when it helps.
        - Answer in the language the question was asked in.
        - Keep answers concise and directly useful; this is read on an e-ink screen.
    """.trimIndent()

    val TRANSCRIBE = """
        Transcribe these handwritten pages to clean, readable text. Preserve the structure
        (headings, lists, tables) using Markdown. Mark anything illegible as [unclear].
        Start each page with '## Page N'. Output only the transcription.
    """.trimIndent()

    /** Sent as the question text when the user's question is handwritten. */
    val HANDWRITTEN_QUESTION =
        "My question is handwritten in the last image. Read that handwriting and answer it, " +
            "based on the notebook pages."

    /** The user typed text and also handwrote something in the last image. */
    fun typedPlusHandwritten(typed: String) =
        "$typed\n\n(There may be additional context in my handwriting in the last image.)"
}

/**
 * A multi-turn conversation about a set of notebook pages, streamed from an
 * LLM provider. Context attachments (the pages) are sent once on the first
 * turn; each turn's question is a text instruction plus an optional
 * handwritten-question image.
 *
 * ask() is blocking and must not be called concurrently; cancel() may be
 * called from any thread.
 */
interface AiConversation {
    val turnCount: Int

    @Throws(AiApiException::class, IOException::class)
    fun ask(
        questionText: String,
        questionImage: ByteArray? = null,
        contextAttachments: List<Attachment> = emptyList(),
        onDelta: (String) -> Unit = {},
    ): TurnResult

    fun cancel()

    companion object {
        fun create(
            config: AiConfig,
            httpClient: OkHttpClient? = null,
            systemPrompt: String = Prompts.SYSTEM,
        ): AiConversation = when (config.provider) {
            AiProvider.ANTHROPIC -> AnthropicConversation(config, httpClient, systemPrompt)
            AiProvider.OPENROUTER -> OpenRouterConversation(config, httpClient, systemPrompt)
        }

        /** Validates the key. Returns null on success or a human-readable error. */
        fun testApiKey(config: AiConfig, httpClient: OkHttpClient? = null): String? =
            when (config.provider) {
                AiProvider.ANTHROPIC -> AnthropicConversation.testApiKey(config, httpClient)
                AiProvider.OPENROUTER -> OpenRouterConversation.testApiKey(config, httpClient)
            }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Long read timeout: with thinking enabled the stream can go quiet
            // for a while before output begins.
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
    }
}

/** Shared history/cancel/rollback machinery for the provider conversations. */
internal abstract class BaseConversation(
    protected val config: AiConfig,
    httpClient: OkHttpClient?,
    protected val systemPrompt: String,
) : AiConversation {

    protected val client: OkHttpClient = httpClient ?: AiConversation.defaultHttpClient()
    protected val json = Json { ignoreUnknownKeys = true }
    private val history = mutableListOf<JsonObject>()
    private val historyLock = Any()
    private val activeCall = AtomicReference<Call?>(null)

    override val turnCount: Int get() = synchronized(historyLock) { history.size / 2 }

    /** Provider-specific user message for this turn. */
    protected abstract fun userMessage(
        questionText: String,
        questionImage: ByteArray?,
        contextAttachments: List<Attachment>,
        firstTurn: Boolean,
    ): JsonObject

    /** Provider-specific request for the given message history. */
    protected abstract fun buildRequest(messages: JsonArray): Request

    /** Provider-specific stream parse. Returns the turn outcome. */
    protected abstract fun parseStream(response: Response, onDelta: (String) -> Unit): StreamOutcome

    final override fun ask(
        questionText: String,
        questionImage: ByteArray?,
        contextAttachments: List<Attachment>,
        onDelta: (String) -> Unit,
    ): TurnResult {
        val firstTurn = synchronized(historyLock) { history.isEmpty() }
        val userTurn = userMessage(questionText, questionImage, contextAttachments, firstTurn)
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

        synchronized(historyLock) {
            val assistant = outcome.assistantMessage
            if (assistant != null) history.add(assistant) else history.remove(userTurn)
        }
        return TurnResult(outcome.text, outcome.stopReason, outcome.refusalExplanation, outcome.servedBy)
    }

    private fun executeStream(messages: JsonArray, onDelta: (String) -> Unit): StreamOutcome {
        val call = client.newCall(buildRequest(messages))
        activeCall.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw apiError(response.code, response.body?.string())
                }
                return parseStream(response, onDelta)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    final override fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    protected fun apiError(code: Int, body: String?): AiApiException {
        val error = try {
            body?.let { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject }
        } catch (e: Exception) {
            null
        }
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $code"
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull
        return AiApiException(friendlyMessage(code, message), errorType = type, httpStatus = code)
    }

    protected fun friendlyMessage(code: Int, message: String): String = when (code) {
        401 -> "Invalid API key. Check Settings. ($message)"
        402 -> "Out of credits with this provider. ($message)"
        413 -> "Request too large - select fewer pages. ($message)"
        429 -> "Rate limited. Wait a moment and retry. ($message)"
        529 -> "The provider is overloaded. Retry shortly. ($message)"
        else -> message
    }

    /** The parsed result of a streamed turn. */
    protected class StreamOutcome(
        val text: String,
        val stopReason: String?,
        val refusalExplanation: String?,
        val servedBy: String?,
        /** Assistant message to append to history, or null to roll the turn back. */
        val assistantMessage: JsonObject?,
    )
}

/**
 * Reads a Server-Sent Events stream, invoking [onEvent] once per event with
 * the (optional) event name and concatenated data. Comment/keep-alive lines
 * (starting with ':') are ignored.
 */
internal fun readSse(source: BufferedSource, onEvent: (eventName: String?, data: String) -> Unit) {
    var eventName: String? = null
    val dataLines = StringBuilder()
    fun flush() {
        if (dataLines.isNotEmpty()) onEvent(eventName, dataLines.toString())
        eventName = null
        dataLines.setLength(0)
    }
    while (true) {
        val line = source.readUtf8Line() ?: break
        when {
            line.isEmpty() -> flush()
            line.startsWith(":") -> Unit // comment / keep-alive
            line.startsWith("event:") -> eventName = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (dataLines.isNotEmpty()) dataLines.append('\n')
                dataLines.append(line.substring(5).trim())
            }
        }
    }
    flush()
}
