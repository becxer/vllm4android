package com.vllm4android.engine

/**
 * One part of a chat message. A turn's `parts` list represents the
 * decoded form of the OpenAI `content` field — either a single text
 * piece (legacy `content: "..."`) or a mix of text and image entries
 * (multimodal `content: [{type:"text",...},{type:"image_url",...}]`).
 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    /**
     * Raw image bytes. [mimeType] is captured for diagnostics; LiteRT-LM
     * accepts the bytes directly via `Content.ImageBytes(...)`.
     */
    class Image(val bytes: ByteArray, val mimeType: String) : ContentPart {
        override fun equals(other: Any?): Boolean =
            other is Image && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            31 * bytes.contentHashCode() + mimeType.hashCode()

        override fun toString(): String =
            "Image(mimeType=$mimeType, bytes=<${bytes.size} bytes>)"
    }
}

/**
 * One conversational turn. [role] is "system", "user", or "assistant"
 * (OpenAI vocabulary — the engine maps assistant→model for Gemma).
 */
data class ChatTurn(val role: String, val parts: List<ContentPart>)
