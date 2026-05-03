package com.vllm4android.server

import com.vllm4android.engine.ChatTemplate
import com.vllm4android.engine.GenerationParams
import com.vllm4android.engine.LlmEngineApi
import com.vllm4android.server.dto.ApiError
import com.vllm4android.server.dto.ApiErrorBody
import com.vllm4android.server.dto.ChatCompletionChoice
import com.vllm4android.server.dto.ChatCompletionChunk
import com.vllm4android.server.dto.ChatCompletionChunkChoice
import com.vllm4android.server.dto.ChatCompletionDelta
import com.vllm4android.server.dto.ChatCompletionRequest
import com.vllm4android.server.dto.ChatCompletionResponse
import com.vllm4android.server.dto.ChatMessage
import com.vllm4android.server.dto.ModelEntry
import com.vllm4android.server.dto.ModelList
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Writer
import java.util.UUID

private val defaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Installs the OpenAI-compatible routes on a Ktor [Application]. Lives as
 * a top-level extension (not a method on [OpenAiServer]) so unit tests can
 * call it from `testApplication { application { openAiModule(...) } }`
 * without spinning up a real CIO engine.
 */
fun Application.openAiModule(
    engine: LlmEngineApi,
    modelId: String,
    json: Json = defaultJson,
) {
    install(ContentNegotiation) { json(json) }
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(ApiErrorBody(message = cause.message ?: cause.toString(), type = "server_error")),
            )
        }
    }
    routing {
        get("/healthz") { call.respond(mapOf("status" to "ok")) }

        get("/v1/models") {
            call.respond(
                ModelList(
                    data = listOf(ModelEntry(id = modelId, created = nowSeconds())),
                ),
            )
        }

        post("/v1/chat/completions") {
            val req = call.receive<ChatCompletionRequest>()
            if (req.messages.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(ApiErrorBody(message = "messages must not be empty")),
                )
                return@post
            }

            val prompt = ChatTemplate.renderGemma(req.messages.toEngineMessages())
            val params = req.toParams()
            val id = newCompletionId()
            val created = nowSeconds()

            if (req.stream) {
                call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
                    val sse = SseChunkWriter(this, json, id, created, modelId)
                    sse.role("assistant")
                    engine.generateStream(prompt, params).collect { piece -> sse.content(piece) }
                    sse.stop()
                    sse.done()
                }
            } else {
                val text = buildString {
                    engine.generateStream(prompt, params).collect { append(it) }
                }
                call.respond(
                    ChatCompletionResponse(
                        id = id,
                        created = created,
                        model = modelId,
                        choices = listOf(
                            ChatCompletionChoice(
                                index = 0,
                                message = ChatMessage(role = "assistant", content = text),
                                finishReason = "stop",
                            ),
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * Writes OpenAI-style chat completion stream events onto an SSE [Writer].
 *
 * Each event is encoded as `data: {chunk}\n\n` and flushed immediately so
 * tokens reach the client in real time. The `[DONE]` sentinel is written
 * verbatim per the OpenAI streaming spec.
 */
private class SseChunkWriter(
    private val writer: Writer,
    private val json: Json,
    private val id: String,
    private val created: Long,
    private val model: String,
) {
    fun role(role: String) = emit(ChatCompletionDelta(role = role), finishReason = null)
    fun content(text: String) = emit(ChatCompletionDelta(content = text), finishReason = null)
    fun stop() = emit(ChatCompletionDelta(), finishReason = "stop")

    fun done() {
        writer.write("data: [DONE]\n\n")
        writer.flush()
    }

    private fun emit(delta: ChatCompletionDelta, finishReason: String?) {
        val chunk = ChatCompletionChunk(
            id = id,
            created = created,
            model = model,
            choices = listOf(
                ChatCompletionChunkChoice(index = 0, delta = delta, finishReason = finishReason),
            ),
        )
        writer.write("data: ")
        writer.write(json.encodeToString(chunk))
        writer.write("\n\n")
        writer.flush()
    }
}

private fun newCompletionId(): String =
    "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

private fun ChatCompletionRequest.toParams() = GenerationParams(
    temperature = temperature,
    topP = topP,
    topK = topK,
    maxTokens = maxTokens,
)

private fun List<ChatMessage>.toEngineMessages() =
    map { ChatTemplate.Message(it.role, it.content) }

/**
 * OpenAI-compatible HTTP server that owns a CIO [ApplicationEngine] and
 * delegates routing setup to [openAiModule].
 */
class OpenAiServer(
    private val engine: LlmEngineApi,
    private val modelId: String,
    private val host: String = "0.0.0.0",
    private val port: Int = 8080,
) {
    private var ktor: ApplicationEngine? = null

    fun start() {
        check(ktor == null) { "Server already running" }
        ktor = embeddedServer(CIO, host = host, port = port) {
            openAiModule(engine, modelId)
        }.also { it.start(wait = false) }
    }

    fun stop() {
        ktor?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        ktor = null
    }
}
