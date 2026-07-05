package dev.snocr.claudekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeConversationTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun config(model: ClaudeModel = ClaudeModel.OPUS_4_8) = ClaudeConfig(
        apiKey = "sk-test",
        model = model,
        baseUrl = server.url("/").toString(),
    )

    private fun sse(vararg events: Pair<String, String>): String =
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    private fun streamResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("content-type", "text/event-stream")
        .setBody(body)

    private fun simpleAnswer(text: String = "The answer.") = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-4-8"}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"${text.take(4)}"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"${text.drop(4)}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun `streams text deltas and returns full answer`() {
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config())
        val deltas = mutableListOf<String>()
        val result = conversation.ask(
            "What does the note say?",
            listOf(Attachment.png(byteArrayOf(1, 2, 3))),
        ) { deltas.add(it) }

        assertEquals("The answer.", result.text)
        assertEquals(listOf("The ", "answer."), deltas)
        assertEquals("end_turn", result.stopReason)
        assertEquals("claude-opus-4-8", result.servedBy)

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk-test", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertNull(request.getHeader("anthropic-beta"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-opus-4-8", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["fallbacks"])
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        // cache breakpoint on the last (only) image
        assertEquals(
            "ephemeral",
            content[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `follow-up echoes assistant turn with thinking block and no new images`() {
        server.enqueue(streamResponse(simpleAnswer()))
        server.enqueue(streamResponse(simpleAnswer("Follow-up answer.")))
        val conversation = ClaudeConversation(config())
        conversation.ask("first?", listOf(Attachment.png(byteArrayOf(9))))
        val result = conversation.ask("second?")
        assertEquals("Follow-up answer.", result.text)

        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = second["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val blocks = assistant["content"]!!.jsonArray
        assertEquals("thinking", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig123", blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("text", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        // follow-up user message is text-only
        val followUp = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(1, followUp.size)
        assertEquals("text", followUp[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `haiku omits thinking parameter`() {
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config(ClaudeModel.HAIKU_4_5))
        conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(body["thinking"])
    }

    @Test
    fun `fable requests server-side fallbacks`() {
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config(ClaudeModel.FABLE_5))
        conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        val request = server.takeRequest()
        assertEquals("server-side-fallback-2026-06-01", request.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            "claude-opus-4-8",
            body["fallbacks"]!!.jsonArray[0].jsonObject["model"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `http error surfaces friendly message and rolls back history`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"bad key"}}""")
        )
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config())
        val error = assertFailsWith<ClaudeApiException> {
            conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        }
        assertTrue(error.message!!.contains("Invalid API key"))
        assertEquals(401, error.httpStatus)

        // History was rolled back: retry starts a fresh single-message request.
        conversation.ask("q2", listOf(Attachment.png(byteArrayOf(1))))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `refusal is reported and conversation stays usable`() {
        server.enqueue(streamResponse(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-fable-5"}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"declined"}}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )))
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config(ClaudeModel.FABLE_5))
        val result = conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        assertEquals("refusal", result.stopReason)
        assertEquals("declined", result.refusalExplanation)
        assertEquals("", result.text)

        // Refused turn was dropped; a new ask re-sends a single user message.
        conversation.ask("different question", listOf(Attachment.png(byteArrayOf(1))))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `mid-stream error event raises and rolls back`() {
        server.enqueue(streamResponse(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-opus-4-8"}}""",
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )))
        val conversation = ClaudeConversation(config())
        val error = assertFailsWith<ClaudeApiException> {
            conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        }
        assertEquals("overloaded_error", error.errorType)
    }

    @Test
    fun `multiple attachments put cache breakpoint only on the last`() {
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config())
        conversation.ask("q", listOf(Attachment.png(byteArrayOf(1)), Attachment.jpeg(byteArrayOf(2)), Attachment.pdf(byteArrayOf(3))))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(4, content.size) // 3 attachments + question
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("document", content[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "application/pdf",
            content[2].jsonObject["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content,
        )
        assertNull(content[0].jsonObject["cache_control"])
        assertNull(content[1].jsonObject["cache_control"])
        assertEquals(
            "ephemeral",
            content[2].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `truncated stream fails instead of committing a partial answer`() {
        // Stream ends cleanly but without message_stop.
        server.enqueue(streamResponse(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-opus-4-8"}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial answ"}}""",
        )))
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config())
        assertFailsWith<java.io.IOException> {
            conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        }
        // Rolled back: the retry is a fresh single-message request.
        conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `mid-stream fallback updates servedBy and drops pre-boundary thinking from echo`() {
        server.enqueue(streamResponse(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-fable-5"}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sigA"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"fallback","from":{"model":"claude-fable-5"},"to":{"model":"claude-opus-4-8"}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"rescued"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":2}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )))
        server.enqueue(streamResponse(simpleAnswer()))
        val conversation = ClaudeConversation(config(ClaudeModel.FABLE_5))
        val result = conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        assertEquals("claude-opus-4-8", result.servedBy)
        assertEquals("rescued", result.text)

        conversation.ask("follow-up")
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val assistant = second["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        // Only the text block survives: pre-fallback thinking + marker dropped.
        assertEquals(1, assistant.size)
        assertEquals("text", assistant[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sse comment lines are ignored`() {
        server.enqueue(streamResponse(": keep-alive\n\n" + simpleAnswer()))
        val conversation = ClaudeConversation(config())
        val result = conversation.ask("q", listOf(Attachment.png(byteArrayOf(1))))
        assertEquals("The answer.", result.text)
    }

    @Test
    fun `testApiKey reports invalid key`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals("Invalid API key", ClaudeConversation.testApiKey(config()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        assertNull(ClaudeConversation.testApiKey(config()))
    }
}
