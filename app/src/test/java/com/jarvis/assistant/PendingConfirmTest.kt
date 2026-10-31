package com.jarvis.assistant

import com.jarvis.assistant.data.PendingConfirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question that waits between two sentences.
 *
 * It has to survive being written and read back exactly — a plan that comes back mangled would send
 * the wrong message — and it has to expire, so a "yes" tomorrow cannot fire today's question.
 */
class PendingConfirmTest {

    private fun pending(at: Long = System.currentTimeMillis()) = PendingConfirm.Pending(
        question = "Send \"on my way\" to Divik? Say yes to send.",
        title = "SEND MESSAGE",
        subtitle = "AI · 1 STEP",
        say = "Sending.",
        steps = listOf(PendingConfirm.Step("message", "whatsapp|Divik|on my way", "SEND")),
        heard = "send Divik on my way",
        at = at,
    )

    @Test
    fun aQuestionSurvivesTheRoundTrip() {
        val original = pending()
        val back = PendingConfirm.decode(PendingConfirm.encode(original))!!
        assertEquals(original.question, back.question)
        assertEquals(original.title, back.title)
        assertEquals(original.say, back.say)
        assertEquals(1, back.steps.size)
        assertEquals("message", back.steps[0].action)
        assertEquals("whatsapp|Divik|on my way", back.steps[0].param)
        assertEquals("SEND", back.steps[0].label)
        assertEquals("send Divik on my way", back.heard)
        assertNull(back.screenGoal)
        assertTrue(back.isFresh)
    }

    @Test
    fun aScreenGoalIsKeptToo() {
        val back = PendingConfirm.decode(PendingConfirm.encode(pending().copy(screenGoal = "send a photo")))!!
        assertEquals("send a photo", back.screenGoal)
    }

    @Test
    fun anOldQuestionIsNotFresh() {
        val stale = pending(at = System.currentTimeMillis() - PendingConfirm.WINDOW_MS - 1_000L)
        assertFalse(stale.isFresh)
        assertTrue(pending().isFresh)
    }

    @Test
    fun rubbishReadsAsNothingRatherThanCrashing() {
        assertNull(PendingConfirm.decode(null))
        assertNull(PendingConfirm.decode(""))
        assertNull(PendingConfirm.decode("{not json"))
        assertNull(PendingConfirm.decode("{}"))
    }

    @Test
    fun aStepWithNoActionIsDropped() {
        val encoded = PendingConfirm.encode(pending()).replace("\"message\"", "\"\"")
        // With the only step unusable there is nothing to run, so the whole question is worthless.
        assertNull(PendingConfirm.decode(encoded))
    }
}
