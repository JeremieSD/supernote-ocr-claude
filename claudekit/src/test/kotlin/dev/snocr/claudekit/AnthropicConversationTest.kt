package dev.snocr.claudekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

class AnthropicConversationTest {

    private lateinit var server: MockWebServer

    @BeforeEach fun setUp() { server = MockWebServer(); server.start() }
    @AfterEach fun tearDown() { server.shutdown() }

    private fun config(modelId: String = "claude-opus-4-8") = AiConfig(
        provider = AiProvider.ANTHROPIC,
        apiKey = "sk-test",
        modelId = modelId,
        anthropicBaseUrl = server.url("/").toString(),
    )

    private fun conversation(modelId: String = "claude-opus-4-8") =
        AiConversation.create(config(modelId))

    private fun sse(vararg events: Pair<String, String>): String =
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    private fun streamResponse(body: String) = MockResponse()
        .setResponseCode(200).addHeader("content-type", "text/event-stream").setBody(body)

    private fun answer(text: String = "The answer.") = sse(
        "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-opus-4-8"}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"${text.take(4)}"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"${text.drop(4)}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun `streams text and sends context image with cache breakpoint`() {
        server.enqueue(streamResponse(answer()))
        val deltas = mutableListOf<String>()
        val result = conversation().ask(
            "What does the note say?",
            contextAttachments = listOf(Attachment.png(byteArrayOf(1, 2, 3))),
        ) { deltas.add(it) }

        assertEquals("The answer.", result.text)
        assertEquals(listOf("The ", "answer."), deltas)
        assertEquals("claude-opus-4-8", result.servedBy)

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk-test", request.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "ephemeral",
            content[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handwritten question image is sent after the cache breakpoint every turn`() {
        server.enqueue(streamResponse(answer()))
        server.enqueue(streamResponse(answer("Second.")))
        val convo = conversation()
        convo.ask(
            Prompts.HANDWRITTEN_QUESTION,
            questionImage = byteArrayOf(7),
            contextAttachments = listOf(Attachment.png(byteArrayOf(1))),
        )
        // First turn: context image (cached) + handwriting image + text.
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val firstContent = first["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(3, firstContent.size)
        assertNotNull(firstContent[0].jsonObject["cache_control"]) // context image cached
        assertEquals("image", firstContent[1].jsonObject["type"]!!.jsonPrimitive.content) // handwriting
        assertNull(firstContent[1].jsonObject["cache_control"]) // handwriting not cached
        assertEquals("text", firstContent[2].jsonObject["type"]!!.jsonPrimitive.content)

        // Follow-up: only handwriting image + text, no context images re-sent.
        convo.ask(Prompts.HANDWRITTEN_QUESTION, questionImage = byteArrayOf(8))
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = second["messages"]!!.jsonArray
        assertEquals(3, messages.size) // user, assistant, user
        val followUp = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(2, followUp.size) // handwriting image + text only
        assertEquals("image", followUp[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `haiku omits thinking and fable requests fallbacks`() {
        server.enqueue(streamResponse(answer()))
        conversation("claude-haiku-4-5").ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        val haiku = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(haiku["thinking"])

        server.enqueue(streamResponse(answer()))
        conversation("claude-fable-5").ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        val request = server.takeRequest()
        assertEquals("server-side-fallback-2026-06-01", request.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            "claude-opus-4-8",
            body["fallbacks"]!!.jsonArray[0].jsonObject["model"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `truncated stream fails and rolls back`() {
        server.enqueue(streamResponse(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-opus-4-8"}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"partial"}}""",
        )))
        server.enqueue(streamResponse(answer()))
        val convo = conversation()
        assertFailsWith<java.io.IOException> {
            convo.ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        }
        convo.ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `http error surfaces friendly message and rolls back`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"bad key"}}""")
        )
        val convo = conversation()
        val error = assertFailsWith<AiApiException> {
            convo.ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        }
        assertTrue(error.message!!.contains("Invalid API key"))
        assertEquals(401, error.httpStatus)
    }

    @Test
    fun `refusal reported and turn dropped`() {
        server.enqueue(streamResponse(sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","model":"claude-fable-5"}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"explanation":"declined"}}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )))
        server.enqueue(streamResponse(answer()))
        val convo = conversation("claude-fable-5")
        val result = convo.ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        assertEquals("refusal", result.stopReason)
        assertEquals("declined", result.refusalExplanation)
        convo.ask("q2", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(1, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `testApiKey reports invalid key`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals("Invalid API key", AiConversation.testApiKey(config()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        assertNull(AiConversation.testApiKey(config()))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
}
