package com.jarvis.assistant

import com.jarvis.assistant.ai.StreamingReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When to start speaking a reply that is still being written.
 *
 * The rule being pinned down: prose is read out as its sentences finish, and a routine is never
 * spoken at all — because half a JSON object is not something anyone wants read aloud, and the
 * object has to be whole before it can be run.
 */
class StreamingReplyTest {

    private fun feedAll(reply: StreamingReply, text: String, chunk: Int = 3): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val end = minOf(index + chunk, text.length)
            out.addAll(reply.feed(text.substring(index, end)))
            index = end
        }
        out.addAll(reply.finish())
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Which kind of reply is it
    // ---------------------------------------------------------------------------------------------

    @Test
    fun proseIsProse() {
        val reply = StreamingReply()
        reply.feed("Good morning")
        assertEquals(StreamingReply.Mode.PROSE, reply.mode)
        assertTrue(reply.isProse)
    }

    @Test
    fun aLeadingBraceMeansARoutine() {
        val reply = StreamingReply()
        reply.feed("  {")
        assertEquals(StreamingReply.Mode.PLAN, reply.mode)
        assertFalse(reply.isProse)
    }

    @Test
    fun aCodeFenceIsNeverSpoken() {
        val reply = StreamingReply()
        reply.feed("```json")
        assertEquals(StreamingReply.Mode.PLAN, reply.mode)
    }

    @Test
    fun whitespaceAloneDecidesNothing() {
        val reply = StreamingReply()
        assertEquals(emptyList<String>(), reply.feed("   "))
        assertEquals(StreamingReply.Mode.UNKNOWN, reply.mode)
    }

    // ---------------------------------------------------------------------------------------------
    // Sentences
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aSentenceIsSpeakableTheMomentItEnds() {
        val reply = StreamingReply()
        val out = mutableListOf<String>()
        out += reply.feed("Taking the")
        assertTrue("nothing complete yet", out.isEmpty())
        out += reply.feed(" torch ")
        assertTrue(out.isEmpty())
        out += reply.feed("off.")
        assertEquals(listOf("Taking the torch off."), out)
    }

    @Test
    fun aReplyWithoutAFullStopIsStillSaid() {
        val reply = StreamingReply()
        reply.feed("On it")
        assertEquals(listOf("On it"), reply.finish())
        // And finishing twice does not say it twice.
        assertEquals(emptyList<String>(), reply.finish())
    }

    @Test
    fun decimalsDoNotSplitASentence() {
        val reply = StreamingReply()
        assertTrue(reply.feed("It costs 3.50").isEmpty())
        assertEquals(listOf("It costs 3.50 total."), reply.feed(" total."))
    }

    @Test
    fun abbreviationsDoNotSplitASentence() {
        val reply = StreamingReply()
        assertTrue(reply.feed("Mr. Sharma called").isEmpty())
        assertEquals(listOf("Mr. Sharma called again."), reply.feed(" again."))
    }

    @Test
    fun devanagariEndsASentenceAtTheDanda() {
        val reply = StreamingReply()
        assertEquals(listOf("ठीक है।"), reply.feed("ठीक है।"))
    }

    @Test
    fun aQuestionIsASentence() {
        val reply = StreamingReply()
        assertEquals(listOf("Ready?"), reply.feed("Ready?"))
    }

    @Test
    fun aVeryLongSentenceIsCutSoSpeechCanStart() {
        val reply = StreamingReply(maxSentence = 40)
        val first = reply.feed("This is a deliberately long sentence that keeps going and going")
        assertEquals(1, first.size)
        assertTrue(first.first().length <= 40)
        assertFalse(first.first().endsWith(" "))
    }

    @Test
    fun theWholeReplyIsKeptEvenThoughItWasSpoken() {
        val reply = StreamingReply()
        feedAll(reply, "One. Two. Three.")
        assertEquals("One. Two. Three.", reply.text)
    }

    @Test
    fun aRoutineIsCollectedAndNeverSpoken() {
        val reply = StreamingReply()
        val out = feedAll(reply, """{"title":"NIGHT","steps":[]}""")
        assertTrue(out.isEmpty())
        assertEquals("""{"title":"NIGHT","steps":[]}""", reply.text)
    }

    @Test
    fun everyWordOfAProseReplyIsSpokenExactlyOnce() {
        val reply = StreamingReply()
        val text = "I have turned the torch off. The volume is at twenty. Anything else?"
        val said = feedAll(reply, text, chunk = 7)
        assertEquals(
            listOf("I have turned the torch off.", "The volume is at twenty.", "Anything else?"),
            said,
        )
    }
}
