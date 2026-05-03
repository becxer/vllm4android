package com.vllm4android.server

import com.vllm4android.engine.ChatTurn
import com.vllm4android.engine.ContentPart
import com.vllm4android.server.dto.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Errors thrown here are caught by the route's StatusPages handler and
 *  surfaced as 400 with an OpenAI-style error envelope. */
class InvalidContentException(message: String) : IllegalArgumentException(message)

/**
 * Decodes one OpenAI-style chat message into the engine's [ChatTurn]
 * representation. Supports both legacy string content and the multimodal
 * parts array (`{"type":"text",...}` / `{"type":"image_url",...}`).
 */
fun ChatMessage.toChatTurn(): ChatTurn = ChatTurn(role = role, parts = decodeContent(content))

private fun decodeContent(content: JsonElement): List<ContentPart> = when (content) {
    is JsonPrimitive -> {
        if (!content.isString) {
            throw InvalidContentException("content must be a string or an array of parts")
        }
        listOf(ContentPart.Text(content.content))
    }
    is JsonArray -> content.map(::decodeContentPart)
    else -> throw InvalidContentException(
        "content must be a string or an array of parts; got ${content::class.simpleName}",
    )
}

private fun decodeContentPart(elem: JsonElement): ContentPart {
    val obj = elem as? JsonObject
        ?: throw InvalidContentException("each content part must be an object")
    val type = obj["type"]?.jsonPrimitive?.contentOrNullStrict()
        ?: throw InvalidContentException("content part missing 'type'")
    return when (type) {
        "text" -> {
            val text = obj["text"]?.jsonPrimitive?.contentOrNullStrict()
                ?: throw InvalidContentException("text part missing 'text'")
            ContentPart.Text(text)
        }
        "image_url" -> {
            val imageUrl = obj["image_url"] as? JsonObject
                ?: throw InvalidContentException("image_url part missing 'image_url' object")
            val url = imageUrl["url"]?.jsonPrimitive?.contentOrNullStrict()
                ?: throw InvalidContentException("image_url.url missing")
            val (mime, bytes) = decodeImageDataUri(url)
            ContentPart.Image(bytes = bytes, mimeType = mime)
        }
        else -> throw InvalidContentException("unsupported content part type: $type")
    }
}

private fun decodeImageDataUri(url: String): Pair<String, ByteArray> {
    if (!url.startsWith("data:")) {
        throw InvalidContentException(
            "image_url.url must be a data URI (HTTP URLs are not fetched server-side); " +
                "got: ${url.take(40)}…",
        )
    }
    val comma = url.indexOf(',')
    if (comma <= "data:".length) throw InvalidContentException("malformed data URI")
    val header = url.substring("data:".length, comma)
    if (";base64" !in header) {
        throw InvalidContentException("only base64-encoded data URIs are supported")
    }
    val mime = header.substringBefore(';').ifBlank { "image/octet-stream" }
    val bytes = try {
        Base64.getDecoder().decode(url.substring(comma + 1))
    } catch (e: IllegalArgumentException) {
        throw InvalidContentException("invalid base64 in data URI: ${e.message}")
    }
    return mime to bytes
}

/** [JsonPrimitive.content] returns the literal text even for non-strings
 *  (`true`, `42`). We want to reject those — only proper JSON strings. */
private fun JsonPrimitive.contentOrNullStrict(): String? =
    if (isString) content else null
