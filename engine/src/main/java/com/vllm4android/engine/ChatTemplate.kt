package com.vllm4android.engine

/**
 * Renders an OpenAI-style message list into a single prompt string for Gemma.
 *
 * Gemma chat format:
 *   <start_of_turn>user
 *   {content}<end_of_turn>
 *   <start_of_turn>model
 *   {content}<end_of_turn>
 *
 * System messages are folded into the first user turn — Gemma's official
 * template has no dedicated system role.
 */
object ChatTemplate {

    data class Message(val role: String, val content: String)

    fun renderGemma(messages: List<Message>): String {
        val builder = StringBuilder()
        val systemPrefix = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }

        var systemConsumed = false
        for (msg in messages.filter { it.role != "system" }) {
            val role = if (msg.role == "assistant") "model" else "user"
            val content = if (role == "user" && !systemConsumed && systemPrefix != null) {
                systemConsumed = true
                "$systemPrefix\n\n${msg.content}"
            } else {
                msg.content
            }
            builder.append("<start_of_turn>").append(role).append('\n')
            builder.append(content).append("<end_of_turn>\n")
        }
        builder.append("<start_of_turn>model\n")
        return builder.toString()
    }
}
