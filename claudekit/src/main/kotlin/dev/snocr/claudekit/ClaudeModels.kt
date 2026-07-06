package dev.snocr.claudekit

/** Claude models offered in the app's settings. */
enum class ClaudeModel(
    val id: String,
    val displayName: String,
    /** Adaptive thinking is supported on Claude 4.6+ generation models. */
    val supportsAdaptiveThinking: Boolean,
    /** Fable 5 requires opt-in server-side fallbacks for policy declines. */
    val needsRefusalFallback: Boolean,
) {
    OPUS_4_8("claude-opus-4-8", "Claude Opus 4.8 (recommended)", true, false),
    SONNET_5("claude-sonnet-5", "Claude Sonnet 5 (faster)", true, false),
    HAIKU_4_5("claude-haiku-4-5", "Claude Haiku 4.5 (cheapest)", false, false),
    FABLE_5("claude-fable-5", "Claude Fable 5 (most capable)", true, true);

    companion object {
        val DEFAULT = OPUS_4_8

        fun fromId(id: String?): ClaudeModel =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

class ClaudeConfig(
    val apiKey: String,
    val model: ClaudeModel = ClaudeModel.DEFAULT,
    val baseUrl: String = "https://api.anthropic.com",
    val maxTokens: Int = 32_000,
)

/** Outcome of one streamed assistant turn. */
class TurnResult(
    val text: String,
    val stopReason: String?,
    /** Explanation when the model declined the request (stop_reason=refusal). */
    val refusalExplanation: String?,
    /** Model that actually served the response (may be a fallback model). */
    val servedBy: String?,
)

class ClaudeApiException(
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
}
