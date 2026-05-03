package com.vllm4android.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [LlmEngineApi] backed by LiteRT-LM.
 *
 * One [LlmEngine] holds a single loaded model and serializes generation
 * requests with a mutex — LiteRT-LM Conversations are not safe to interleave
 * from multiple coroutines, and a phone has nowhere near the resources for
 * true continuous batching anyway.
 */
class LlmEngine(
    private val modelPath: String,
    private val backend: Backend = Backend.CPU(),
) : LlmEngineApi, AutoCloseable {

    private var engine: Engine? = null
    private val generationLock = Mutex()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        check(engine == null) { "Engine already initialized" }
        val config = EngineConfig(modelPath = modelPath, backend = backend)
        engine = Engine(config).also { it.initialize() }
    }

    override fun generateStream(
        turns: List<ChatTurn>,
        params: GenerationParams,
    ): Flow<String> = flow {
        val e = requireNotNull(engine) { "Engine not initialized" }
        // TODO(sampler): map [params] to LiteRT-LM SamplerConfig once the
        // Conversation-level sampler override API is wired up. The values
        // currently flow through the API surface but are not yet honored
        // by the engine.
        generationLock.withLock {
            e.createConversation().use { conversation ->
                val hasImages = turns.any { it.parts.any { p -> p is ContentPart.Image } }
                if (!hasImages) {
                    val prompt = ChatTemplate.renderGemma(turns.toTextMessages())
                    conversation.sendMessageAsync(prompt)
                        .collect { chunk -> emit(chunk.toString()) }
                } else {
                    val contents = buildMultimodalContents(turns)
                    conversation.sendMessageAsync(contents)
                        .collect { chunk -> emit(chunk.toString()) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        engine?.close()
        engine = null
    }
}

/** v1 multimodal: only the last user turn may carry images. System
 *  messages are folded into a leading text part; everything else is
 *  rejected loudly so we don't silently drop history.
 */
private fun buildMultimodalContents(turns: List<ChatTurn>): Contents {
    val systemPrefix = turns.filter { it.role == "system" }
        .flatMap { it.parts }
        .filterIsInstance<ContentPart.Text>()
        .joinToString("\n\n") { it.text }

    val nonSystem = turns.filter { it.role != "system" }
    require(nonSystem.size == 1 && nonSystem[0].role == "user") {
        "multimodal requests currently support only a single user turn " +
            "(plus optional system messages); got roles=${nonSystem.map { it.role }}"
    }

    val parts = mutableListOf<Content>()
    if (systemPrefix.isNotBlank()) parts += Content.Text(systemPrefix)
    for (p in nonSystem[0].parts) {
        parts += when (p) {
            is ContentPart.Text -> Content.Text(p.text)
            is ContentPart.Image -> Content.ImageBytes(p.bytes)
        }
    }
    return Contents.of(*parts.toTypedArray())
}

private fun List<ChatTurn>.toTextMessages(): List<ChatTemplate.Message> = map { turn ->
    val text = turn.parts.joinToString("") { p ->
        when (p) {
            is ContentPart.Text -> p.text
            is ContentPart.Image -> error("text-only path received an image part")
        }
    }
    ChatTemplate.Message(turn.role, text)
}
