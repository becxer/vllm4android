package com.vllm4android.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around LiteRT-LM's [Engine].
 *
 * One [LlmEngine] holds a single loaded model and serializes generation requests
 * with a mutex — LiteRT-LM Conversations are not safe to interleave from
 * multiple coroutines, and a phone has nowhere near the resources for true
 * continuous batching anyway.
 */
class LlmEngine(
    private val modelPath: String,
    private val backend: Backend = Backend.CPU(),
) : AutoCloseable {

    private var engine: Engine? = null
    private val generationLock = Mutex()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        check(engine == null) { "Engine already initialized" }
        val config = EngineConfig(modelPath = modelPath, backend = backend)
        engine = Engine(config).also { it.initialize() }
    }

    /**
     * Streams tokens for [prompt] as a cold Flow. Emissions are partial text
     * chunks (not full deltas — concatenating them yields the full response).
     */
    fun generateStream(
        prompt: String,
        sampler: SamplerConfig? = null,
    ): Flow<String> = flow {
        val e = requireNotNull(engine) { "Engine not initialized" }
        generationLock.withLock {
            e.createConversation().use { conversation ->
                conversation
                    .sendMessageAsync(prompt)
                    .collect { chunk -> emit(chunk.toString()) }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val e = requireNotNull(engine) { "Engine not initialized" }
        generationLock.withLock {
            e.createConversation().use { conversation ->
                conversation.sendMessage(prompt)
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
