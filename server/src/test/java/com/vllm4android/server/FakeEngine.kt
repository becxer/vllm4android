package com.vllm4android.server

import com.vllm4android.engine.GenerationParams
import com.vllm4android.engine.LlmEngineApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Test double that emits a fixed sequence of chunks and records what the
 * server passed in (prompt + sampling params). Use this to assert OpenAI
 * compatibility without loading a 2.5 GB model.
 */
class FakeEngine(
    private val chunks: List<String> = listOf("Hello", " ", "world", "!"),
    private val onError: Throwable? = null,
) : LlmEngineApi {

    @Volatile var lastPrompt: String? = null
        private set

    @Volatile var lastParams: GenerationParams? = null
        private set

    override fun generateStream(
        prompt: String,
        params: GenerationParams,
    ): Flow<String> = flow {
        lastPrompt = prompt
        lastParams = params
        if (onError != null) throw onError
        chunks.forEach { emit(it) }
    }
}
