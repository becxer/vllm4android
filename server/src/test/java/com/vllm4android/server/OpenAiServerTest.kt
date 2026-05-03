package com.vllm4android.server

import com.vllm4android.server.dto.ChatCompletionChunk
import com.vllm4android.server.dto.ChatCompletionResponse
import com.vllm4android.server.dto.ModelList
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MODEL_ID = "gemma-4-E2B-it"

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun parseChunk(payload: String): ChatCompletionChunk =
    json.decodeFromString(ChatCompletionChunk.serializer(), payload)

/**
 * Splits an SSE response body into the JSON payloads that follow `data: `,
 * preserving the [DONE] sentinel as a literal string. Empty events are
 * skipped.
 */
private fun parseSseEvents(body: String): List<String> =
    body.split("\n\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.removePrefix("data: ") }

class OpenAiServerTest {

    @Test
    fun `healthz returns ok`() = testApplication {
        application { openAiModule(FakeEngine(), MODEL_ID) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `models endpoint advertises the configured model`() = testApplication {
        application { openAiModule(FakeEngine(), MODEL_ID) }
        val client = createClient { install(ClientContentNegotiation) { json(json) } }
        val response = client.get("/v1/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val list: ModelList = json.decodeFromString(ModelList.serializer(), response.bodyAsText())
        assertEquals(1, list.data.size)
        assertEquals(MODEL_ID, list.data[0].id)
        assertEquals("model", list.data[0].obj)
        assertEquals("list", list.obj)
    }

    @Test
    fun `non-stream chat completion concatenates engine chunks`() = testApplication {
        val fake = FakeEngine(chunks = listOf("Hello", ", ", "world", "!"))
        application { openAiModule(fake, MODEL_ID) }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"$MODEL_ID","messages":[{"role":"user","content":"hi"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body: ChatCompletionResponse =
            json.decodeFromString(ChatCompletionResponse.serializer(), response.bodyAsText())
        assertTrue(body.id.startsWith("chatcmpl-"), "id should be chatcmpl-...; got ${body.id}")
        assertEquals("chat.completion", body.obj)
        assertEquals(MODEL_ID, body.model)
        assertEquals(1, body.choices.size)
        val choice = body.choices[0]
        assertEquals(0, choice.index)
        assertEquals("assistant", choice.message.role)
        assertEquals("Hello, world!", choice.message.content)
        assertEquals("stop", choice.finishReason)

        // Prompt that reached the engine must be the rendered Gemma template,
        // not the raw OpenAI-style messages list.
        val sentPrompt = assertNotNull(fake.lastPrompt)
        assertTrue("<start_of_turn>user" in sentPrompt, sentPrompt)
        assertTrue(sentPrompt.endsWith("<start_of_turn>model\n"), sentPrompt)
    }

    @Test
    fun `streaming chat completion emits role then content deltas then DONE`() = testApplication {
        val fake = FakeEngine(chunks = listOf("foo", "bar"))
        application { openAiModule(fake, MODEL_ID) }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"$MODEL_ID","messages":[{"role":"user","content":"hi"}],"stream":true}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val events = parseSseEvents(body)

        // Last event must be the DONE sentinel.
        assertEquals("[DONE]", events.last())

        val chunks = events.dropLast(1).map(::parseChunk)
        // First chunk: role=assistant, no content yet.
        assertEquals("assistant", chunks.first().choices[0].delta.role)
        assertNull(chunks.first().choices[0].delta.content)

        // Middle chunks: content deltas matching FakeEngine output exactly.
        val contentChunks = chunks.drop(1).dropLast(1)
        assertEquals(listOf("foo", "bar"), contentChunks.map { it.choices[0].delta.content })

        // Final chunk: empty delta + finish_reason=stop.
        val tail = chunks.last()
        assertEquals("stop", tail.choices[0].finishReason)
        assertNull(tail.choices[0].delta.content)
        assertNull(tail.choices[0].delta.role)

        // All chunks share the same id and use object="chat.completion.chunk".
        val ids = chunks.map { it.id }.toSet()
        assertEquals(1, ids.size, "all chunks should share an id; got $ids")
        assertTrue(chunks.all { it.obj == "chat.completion.chunk" })
        assertTrue(chunks.all { it.model == MODEL_ID })
    }

    @Test
    fun `empty messages returns 400 with OpenAI-style error envelope`() = testApplication {
        application { openAiModule(FakeEngine(), MODEL_ID) }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody("""{"model":"$MODEL_ID","messages":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val obj: JsonObject = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = obj["error"]?.jsonObject ?: error("missing error envelope: $obj")
        assertTrue(
            "messages" in error["message"]!!.jsonPrimitive.content,
            "error.message should mention messages; got $error",
        )
    }

    @Test
    fun `sampler params from request reach the engine`() = testApplication {
        val fake = FakeEngine(chunks = listOf("ok"))
        application { openAiModule(fake, MODEL_ID) }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"model":"$MODEL_ID",
                 "messages":[{"role":"user","content":"hi"}],
                 "temperature":0.7,
                 "top_p":0.95,
                 "top_k":40,
                 "max_tokens":128}
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val params = assertNotNull(fake.lastParams)
        assertEquals(0.7f, params.temperature)
        assertEquals(0.95f, params.topP)
        assertEquals(40, params.topK)
        assertEquals(128, params.maxTokens)
    }

    @Test
    fun `system message is rendered into the prompt`() = testApplication {
        val fake = FakeEngine(chunks = listOf("ok"))
        application { openAiModule(fake, MODEL_ID) }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"model":"$MODEL_ID","messages":[
                  {"role":"system","content":"Be terse."},
                  {"role":"user","content":"Hi"}
                ]}
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val sent = assertNotNull(fake.lastPrompt)
        // System message must be folded into the first user turn,
        // not emitted as a separate <start_of_turn>system block.
        assertTrue("<start_of_turn>system" !in sent, sent)
        assertTrue("Be terse.\n\nHi" in sent, sent)
    }
}
