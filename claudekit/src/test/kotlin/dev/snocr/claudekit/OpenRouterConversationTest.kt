package dev.snocr.claudekit

import kotlinx.serialization.json.Json
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

class OpenRouterConversationTest {

    private lateinit var server: MockWebServer

    @BeforeEach fun setUp() { server = MockWebServer(); server.start() }
    @AfterEach fun tearDown() { server.shutdown() }

    private fun config(modelId: String = "google/gemini-2.0-flash-001") = AiConfig(
        provider = AiProvider.OPENROUTER,
        apiKey = "or-key",
        modelId = modelId,
        openRouterBaseUrl = server.url("/").toString(),
    )

    private fun conversation() = AiConversation.create(config())

    private fun streamResponse(body: String) = MockResponse()
        .setResponseCode(200).addHeader("content-type", "text/event-stream").setBody(body)

    private fun answer(text: String = "Hi there.", finish: String = "stop"): String {
        val head = """: OPENROUTER PROCESSING
data: {"model":"google/gemini-2.0-flash-001","choices":[{"delta":{"role":"assistant","content":""}}]}

data: {"choices":[{"delta":{"content":"${text.take(2)}"}}]}

data: {"choices":[{"delta":{"content":"${text.drop(2)}"}}]}

data: {"choices":[{"delta":{},"finish_reason":"$finish"}]}

data: [DONE]

"""
        return head
    }

    @Test
    fun `streams openai-style deltas and builds correct request`() {
        server.enqueue(streamResponse(answer()))
        val deltas = mutableListOf<String>()
        val result = conversation().ask(
            "What is on the page?",
            contextAttachments = listOf(Attachment.jpeg(byteArrayOf(1, 2))),
        ) { deltas.add(it) }

        assertEquals("Hi there.", result.text)
        assertEquals(listOf("Hi", " there."), deltas)
        assertEquals("end_turn", result.stopReason)
        assertEquals("google/gemini-2.0-flash-001", result.servedBy)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer or-key", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("google/gemini-2.0-flash-001", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val userParts = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("image_url", userParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            userParts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/jpeg;base64,")
        )
        assertEquals("text", userParts[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `follow-up echoes assistant text and omits context images`() {
        server.enqueue(streamResponse(answer()))
        server.enqueue(streamResponse(answer("Second.")))
        val convo = conversation()
        convo.ask("first", contextAttachments = listOf(Attachment.png(byteArrayOf(9))))
        convo.ask("second")
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        // system, user(1), assistant, user(2)
        assertEquals(4, messages.size)
        assertEquals("assistant", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hi there.", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
        val followUp = messages[3].jsonObject["content"]!!.jsonArray
        assertEquals(1, followUp.size) // text only, no images
        assertEquals("text", followUp[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `length finish maps to max_tokens`() {
        server.enqueue(streamResponse(answer(finish = "length")))
        val result = conversation().ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        assertEquals("max_tokens", result.stopReason)
    }

    @Test
    fun `content_filter maps to refusal and drops the turn`() {
        server.enqueue(streamResponse(answer(finish = "content_filter")))
        server.enqueue(streamResponse(answer()))
        val convo = conversation()
        val result = convo.ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        assertEquals("refusal", result.stopReason)
        convo.ask("q2", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        // system + one user message only (previous refused turn rolled back)
        assertEquals(2, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `in-stream error is raised`() {
        server.enqueue(streamResponse("data: {\"error\":{\"message\":\"boom\",\"code\":\"bad\"}}\n\n"))
        val error = assertFailsWith<AiApiException> {
            conversation().ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        }
        assertEquals("boom", error.message)
        assertEquals("bad", error.errorType)
    }

    @Test
    fun `http 402 gives a credits message`() {
        server.enqueue(
            MockResponse().setResponseCode(402)
                .setBody("""{"error":{"message":"Insufficient credits"}}""")
        )
        val error = assertFailsWith<AiApiException> {
            conversation().ask("q", contextAttachments = listOf(Attachment.png(byteArrayOf(1))))
        }
        assertTrue(error.message!!.contains("credits"))
    }

    @Test
    fun `testApiKey validates via the key endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"label":"k"}}"""))
        assertNull(AiConversation.testApiKey(config()))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals("Invalid API key", AiConversation.testApiKey(config()))
    }
}
