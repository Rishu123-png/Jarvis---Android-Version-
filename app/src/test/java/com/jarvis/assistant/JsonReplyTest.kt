package com.jarvis.assistant

import com.jarvis.assistant.ai.JsonReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Model replies are not clean JSON.
 *
 * Every provider occasionally adds a code fence, a sentence of politeness, or a trailing "let me
 * know". If extraction is strict, a perfectly good plan is thrown away and the operator sees
 * "I could not turn that into an action".
 */
class JsonReplyTest {

    @Test
    fun plainObjectParses() {
        val json = JsonReply.extractObject("""{"title":"OPEN APP","steps":[]}""")
        assertEquals("OPEN APP", json?.optString("title"))
    }

    @Test
    fun fencedObjectParses() {
        val reply = """
            ```json
            {"title":"CALL","steps":[{"action":"call_contact","param":"dad"}]}
            ```
        """.trimIndent()
        val json = JsonReply.extractObject(reply)
        assertNotNull(json)
        assertEquals("CALL", json?.optString("title"))
        assertEquals(1, json?.optJSONArray("steps")?.length())
    }

    @Test
    fun proseBeforeAndAfterParses() {
        val reply = """Sure, here is the plan: {"title":"TORCH ON"} Let me know if you need more."""
        assertEquals("TORCH ON", JsonReply.extractObject(reply)?.optString("title"))
    }

    @Test
    fun bracesInsideStringsSurvive() {
        val reply = """{"say":"the set {a, b} is fine","title":"X"}"""
        val json = JsonReply.extractObject(reply)
        assertEquals("X", json?.optString("title"))
        assertEquals("the set {a, b} is fine", json?.optString("say"))
    }

    @Test
    fun invalidObjectBeforeValidOneIsSkipped() {
        val reply = """{not json at all} but the real one is {"title":"SECOND"}"""
        assertEquals("SECOND", JsonReply.extractObject(reply)?.optString("title"))
    }

    @Test
    fun noObjectReturnsNull() {
        assertNull(JsonReply.extractObject("I cannot do that."))
        assertNull(JsonReply.extractObject(""))
        assertNull(JsonReply.extractObject("{\"unterminated\": true"))
    }
}
