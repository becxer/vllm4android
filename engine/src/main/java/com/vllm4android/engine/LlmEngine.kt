package com.vllm4android.engine

import com.google.ai.edge.litertlm.Backend
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
        prompt: String,
        params: GenerationParams,
    ): Flow<String> = flow {
        val e = requireNotNull(engine) { "Engine not initialized" }
        // TODO(sampler): map [params] to LiteRT-LM SamplerConfig once the
        // Conversation-level sampler override API is wired up. The values
        // currently flow through the API surface but are not yet honored
        // by the engine.
        generationLock.withLock {
            e.createConversation().use { conversation ->
                conversation
                    .sendMessageAsync(prompt)
                    .collect { chunk -> emit(chunk.toString()) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        engine?.close()
        engine = null
    }
}
