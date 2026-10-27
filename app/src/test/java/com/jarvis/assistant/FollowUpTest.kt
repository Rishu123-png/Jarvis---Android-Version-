package com.jarvis.assistant

import com.jarvis.assistant.voice.FollowUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Multi-turn grammar: what a follow-up means next to the previous sentence.
 *
 * Only unambiguous patterns are rewritten. Anything else stays as it is, because inventing a subject
 * is worse than asking again.
 */
class FollowUpTest {

    private fun turn(heard: String, title: String) = FollowUp.Turn(heard = heard, title = title, said = "")

    @Test
    fun doThatForSomeoneReusesTheMessage() {
        val last = turn("message amit saying I will be late", "MESSAGE · AMIT")
        assertEquals("message khushi I will be late", FollowUp.rewritten(last, "for Khushi"))
    }

    @Test
    fun hinglishFollowUpWorksToo() {
        val last = turn("message amit hi bolo", "MESSAGE · AMIT")
        assertEquals("message khushi hi", FollowUp.rewritten(last, "khushi ko bhi"))
    }

    @Test
    fun andTomorrowShiftsTheSameRequest() {
        val last = turn("add meeting with amit today at 4 pm", "ADD · MEETING WITH AMIT")
        val rewritten = FollowUp.rewritten(last, "and tomorrow")
        assertEquals(true, rewritten?.contains("tomorrow"))
    }

    @Test
    fun aNewTimeReplacesTheOldOne() {
        val last = turn("set alarm 7 am", "ALARM 07:00")
        assertEquals("set alarm 8:30", FollowUp.rewritten(last, "make it 8:30"))
    }

    @Test
    fun aCompleteSentenceIsLeftAlone() {
        assertNull(FollowUp.rewritten(turn("open gallery", "GALLERY"), "what is my battery"))
        assertNull(FollowUp.rewritten(null, "for Amit"))
    }

    @Test
    fun thePersonInAMessageIsRecovered() {
        assertEquals("amit", FollowUp.personOf("message amit saying hello"))
        assertEquals("khushi", FollowUp.personOf("whatsapp khushi that I am coming"))
    }
}
