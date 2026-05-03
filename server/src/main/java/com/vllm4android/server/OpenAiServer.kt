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
                    data = listOf(
                        ModelEntry(id = modelId, created = System.currentTimeMillis() / 1000),
                    ),
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
            val prompt = ChatTemplate.renderGemma(
                req.messages.map { ChatTemplate.Message(it.role, it.content) },
            )
            val params = GenerationParams(
                temperature = req.temperature,
                topP = req.topP,
                topK = req.topK,
                maxTokens = req.maxTokens,
            )
            val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
            val created = System.currentTimeMillis() / 1000

            if (req.stream) {
                call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
                    writeSse(
                        json.encodeToString(
                            ChatCompletionChunk(
                                id = id,
                                created = created,
                                model = modelId,
                                choices = listOf(
                                    ChatCompletionChunkChoice(
                                        index = 0,
                                        delta = ChatCompletionDelta(role = "assistant"),
                                    ),
                                ),
                            ),
                        ),
                    )
                    engine.generateStream(prompt, params).collect { piece ->
                        writeSse(
                            json.encodeToString(
                                ChatCompletionChunk(
                                    id = id,
                                    created = created,
                                    model = modelId,
                                    choices = listOf(
                                        ChatCompletionChunkChoice(
                                            index = 0,
                                            delta = ChatCompletionDelta(content = piece),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
                    writeSse(
                        json.encodeToString(
                            ChatCompletionChunk(
                                id = id,
                                created = created,
                                model = modelId,
                                choices = listOf(
                                    ChatCompletionChunkChoice(
                                        index = 0,
                                        delta = ChatCompletionDelta(),
                                        finishReason = "stop",
                                    ),
                                ),
                            ),
                        ),
                    )
                    write("data: [DONE]\n\n")
                    flush()
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

private fun java.io.Writer.writeSse(payload: String) {
    write("data: ")
    write(payload)
    write("\n\n")
    flush()
}

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
