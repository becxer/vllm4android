package com.vllm4android.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTemplateTest {

    @Test
    fun `single user message renders user turn and opens model turn`() {
        val out = ChatTemplate.renderGemma(
            listOf(ChatTemplate.Message("user", "Hello")),
        )
        assertEquals(
            """
            <start_of_turn>user
            Hello<end_of_turn>
            <start_of_turn>model

            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `system message folds into the first user turn`() {
        val out = ChatTemplate.renderGemma(
            listOf(
                ChatTemplate.Message("system", "You are concise."),
                ChatTemplate.Message("user", "Hi"),
            ),
        )
        assertTrue(
            "user\nYou are concise.\n\nHi<end_of_turn>" in out,
            "system content should precede user content with a blank line; got:\n$out",
        )
        // System message must not appear as its own turn.
        assertTrue(
            "<start_of_turn>system" !in out,
            "Gemma has no system role; got:\n$out",
        )
    }

    @Test
    fun `assistant role is rewritten to model`() {
        val out = ChatTemplate.renderGemma(
            listOf(
                ChatTemplate.Message("user", "Q1"),
                ChatTemplate.Message("assistant", "A1"),
                ChatTemplate.Message("user", "Q2"),
            ),
        )
        assertTrue("<start_of_turn>model\nA1<end_of_turn>" in out)
        assertTrue("<start_of_turn>user\nQ2<end_of_turn>" in out)
        assertTrue("<start_of_turn>assistant" !in out)
        // Final model turn is opened so the engine can complete it.
        assertTrue(out.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `multiple system messages join with blank line`() {
        val out = ChatTemplate.renderGemma(
            listOf(
                ChatTemplate.Message("system", "Be helpful."),
                ChatTemplate.Message("system", "Be brief."),
                ChatTemplate.Message("user", "Hi"),
            ),
        )
        assertTrue("Be helpful.\n\nBe brief.\n\nHi" in out, out)
    }

    @Test
    fun `system applies only to the first user turn`() {
        val out = ChatTemplate.renderGemma(
            listOf(
                ChatTemplate.Message("system", "S"),
                ChatTemplate.Message("user", "U1"),
                ChatTemplate.Message("assistant", "A1"),
                ChatTemplate.Message("user", "U2"),
            ),
        )
        assertTrue("S\n\nU1" in out)
        // U2 must not be prefixed with the system text again.
        assertTrue("S\n\nU2" !in out, out)
    }
}
