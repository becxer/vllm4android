package com.vllm4android.engine

import kotlinx.coroutines.flow.Flow

/**
 * Sampling parameters carried from the OpenAI request layer down to the
 * LiteRT-LM engine. Kept free of LiteRT types so test fakes can implement
 * [LlmEngineApi] without pulling Android dependencies in.
 */
data class GenerationParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
)

/**
 * Minimal surface the server module needs from a language model engine.
 * The production implementation is [LlmEngine]; tests use a fake.
 */
interface LlmEngineApi {
    fun generateStream(
        prompt: String,
        params: GenerationParams = GenerationParams(),
    ): Flow<String>
}
