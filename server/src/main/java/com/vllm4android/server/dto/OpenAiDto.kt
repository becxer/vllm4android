package com.vllm4android.server.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI chat message. [content] is intentionally a [JsonElement] because
 * the OpenAI spec allows two shapes:
 *   - String:  `"content": "Hello"`
 *   - Array of parts: `"content": [{"type":"text",...},{"type":"image_url",...}]`
 *
 * Decoding into typed parts is the route handler's job (see
 * `chatMessageToChatTurn`). Responses always emit the string form by
 * wrapping with `JsonPrimitive`.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
)

@Serializable
data class ChatCompletionChoice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoice>,
    val usage: Usage = Usage(),
)

@Serializable
data class ChatCompletionDelta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ChatCompletionChunkChoice(
    val index: Int,
    val delta: ChatCompletionDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val obj: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChunkChoice>,
)

@Serializable
data class ModelEntry(
    val id: String,
    @SerialName("object") val obj: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "vllm4android",
)

@Serializable
data class ModelList(
    @SerialName("object") val obj: String = "list",
    val data: List<ModelEntry>,
)

@Serializable
data class ApiError(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val message: String,
    val type: String = "invalid_request_error",
    val code: String? = null,
)
