package com.jarvis.assistant

import com.jarvis.assistant.ai.AiWire
import com.jarvis.assistant.ai.Sse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading three providers' streams, using frames copied from what they actually send.
 *
 * The cost of a mistake here is an assistant that goes silent mid-sentence, or one that reads a
 * token count out loud. Both are easy to avoid with a test rather than a phone.
 */
class SseTest {

    @Test
    fun onlyDataLinesCarryPayloads() {
        assertNull(Sse.payload("event: content_block_delta"))
        assertNull(Sse.payload(": ping"))
        assertNull(Sse.payload(""))
        assertNull(Sse.payload("data:   "))
        assertEquals("{}", Sse.payload("data: {}"))
        assertEquals("{}", Sse.payload("data:{}"))
    }

    @Test
    fun openAiSendsContentInTheDelta() {
        assertEquals("Hello", Sse.delta(AiWire.OPENAI, """{"choices":[{"delta":{"content":"Hello"}}]}"""))
        // The first frame only announces the role: nothing to say yet.
        assertNull(Sse.delta(AiWire.OPENAI, """{"choices":[{"delta":{"role":"assistant"}}]}"""))
        // The final frame carries the stop reason and no words.
        assertNull(Sse.delta(AiWire.OPENAI, """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
    }

    @Test
    fun anthropicOnlySpeaksTextDeltas() {
        val text = """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""
        assertEquals("Hi", Sse.delta(AiWire.ANTHROPIC, text))
        assertNull(Sse.delta(AiWire.ANTHROPIC, """{"type":"message_start","message":{}}"""))
        assertNull(Sse.delta(AiWire.ANTHROPIC, """{"type":"message_delta","usage":{"output_tokens":7}}"""))
        assertNull(Sse.delta(AiWire.ANTHROPIC, """{"type":"message_stop"}"""))
    }

    @Test
    fun geminiJoinsThePartsOfOneFrame() {
        val frame = """{"candidates":[{"content":{"parts":[{"text":"Hel"},{"text":"lo"}]}}]}"""
        assertEquals("Hello", Sse.delta(AiWire.GEMINI, frame))
        assertNull(Sse.delta(AiWire.GEMINI, """{"candidates":[{"finishReason":"STOP"}]}"""))
    }

    @Test
    fun rubbishIsSilenceNotACrash() {
        assertNull(Sse.delta(AiWire.OPENAI, "not json"))
        assertNull(Sse.delta(AiWire.ANTHROPIC, """{"type":"content_block_delta"}"""))
        assertNull(Sse.delta(AiWire.GEMINI, "{}"))
    }

    @Test
    fun theEndMarkerIsRecognised() {
        assertTrue(Sse.isEnd("[DONE]"))
        assertFalse(Sse.isEnd("""{"choices":[]}"""))
    }

    @Test
    fun aWholeBodyReadsAsOneSentence() {
        val body = """
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"content":"Good "}}]}

            data: {"choices":[{"delta":{"content":"morning."}}]}

            data: [DONE]

        """.trimIndent()
        assertEquals("Good morning.", Sse.deltas(AiWire.OPENAI, body))
    }
}
