package com.jarvis.assistant

import com.jarvis.assistant.ai.AiWire
import com.jarvis.assistant.ai.ToolCalls
import com.jarvis.assistant.ai.ToolSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a tool call off each of the three wires.
 *
 * These envelopes are copied from what the providers actually send, field names and nesting and all.
 * Getting one wrong is not a parse error to be caught later — it is a working assistant that answers
 * every request with "I could not plan that".
 */
class ToolCallsTest {

    private val arguments = JSONObject().put("name", "NIGHT").put("steps", 2)

    @Test
    fun openAiReadsTheFunctionCall() {
        val body = ToolCalls.envelopeForTest(AiWire.OPENAI, ToolSchema.ROUTINE, arguments)
        val call = ToolCalls.from(AiWire.OPENAI, body)
        assertNotNull(call)
        assertEquals(ToolSchema.ROUTINE, call!!.name)
        assertEquals("NIGHT", call.argumentsObject()!!.getString("name"))
    }

    @Test
    fun anthropicSkipsTheTextBlockToFindTheToolUse() {
        val body = ToolCalls.envelopeForTest(AiWire.ANTHROPIC, ToolSchema.ROUTINE, arguments)
        val call = ToolCalls.from(AiWire.ANTHROPIC, body)
        assertEquals(ToolSchema.ROUTINE, call!!.name)
        assertEquals(2, call.argumentsObject()!!.getInt("steps"))
    }

    @Test
    fun geminiReadsFunctionCallArgs() {
        val body = ToolCalls.envelopeForTest(AiWire.GEMINI, ToolSchema.ROUTINE, arguments)
        val call = ToolCalls.from(AiWire.GEMINI, body)
        assertEquals(ToolSchema.ROUTINE, call!!.name)
        assertEquals("NIGHT", call.argumentsObject()!!.getString("name"))
    }

    @Test
    fun aProseAnswerIsNotACall() {
        val prose = """{"choices":[{"message":{"content":"Sure! Here is a routine..."}}]}"""
        assertNull(ToolCalls.from(AiWire.OPENAI, prose))
        assertNull(ToolCalls.from(AiWire.ANTHROPIC, """{"content":[{"type":"text","text":"hi"}]}"""))
        assertNull(ToolCalls.from(AiWire.GEMINI, """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}"""))
    }

    @Test
    fun rubbishIsNotACrash() {
        assertNull(ToolCalls.from(AiWire.OPENAI, "not json at all"))
        assertNull(ToolCalls.from(AiWire.ANTHROPIC, "{}"))
        assertNull(ToolCalls.from(AiWire.GEMINI, """{"candidates":[]}"""))
        assertNull(ToolCalls.from(AiWire.OPENAI, """{"choices":[{"message":{"tool_calls":[]}}]}"""))
    }

    @Test
    fun emptyArgumentsBecomeAnEmptyObjectRatherThanNull() {
        val body = """{"choices":[{"message":{"tool_calls":[{"function":{"name":"x","arguments":""}}]}}]}"""
        val call = ToolCalls.from(AiWire.OPENAI, body)
        assertNotNull(call)
        assertNotNull(call!!.argumentsObject())
        assertEquals(0, call.argumentsObject()!!.length())
    }

    @Test
    fun unreadableArgumentsAreNullNotGarbage() {
        val body = """{"choices":[{"message":{"tool_calls":[{"function":{"name":"x","arguments":"{oops"}}]}}]}"""
        val call = ToolCalls.from(AiWire.OPENAI, body)
        assertNotNull(call)
        assertNull(call!!.argumentsObject())
    }

    @Test
    fun describeNamesTheFieldsAndNeverTheValues() {
        val call = ToolCalls.Call(ToolSchema.ROUTINE, JSONObject().put("name", "SECRET").put("steps", 1).toString())
        val line = ToolCalls.describe(call)
        assertTrue(line.contains("name"))
        assertTrue(line.contains("steps"))
        assertTrue("the log line must not leak a param body", !line.contains("SECRET"))
    }
}
