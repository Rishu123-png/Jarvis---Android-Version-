package com.jarvis.assistant

import com.jarvis.assistant.data.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf, as a pure codec.
 *
 * The store is one string in one preference, so the format is the risk: a summary with a comma, a
 * quote or a newline in it must not turn into two cards. These tests are the fence around that.
 */
class KnowledgeTest {

    @Test
    fun aTopicIsComparedWithoutItsArticleOrPunctuation() {
        assertEquals("black hole", Knowledge.key("The Black Hole"))
        assertEquals("black hole", Knowledge.key("  black   hole? "))
        assertEquals("प्रकाश संश्लेषण", Knowledge.key("प्रकाश संश्लेषण"))
    }

    @Test
    fun aCardSurvivesTheRoundTrip() {
        val card = Knowledge.Card(
            topic = "Black hole",
            summary = "A black hole is a region of spacetime. Nothing can escape it.",
            source = "WIKIPEDIA",
            url = "https://en.wikipedia.org/wiki/Black_hole",
            at = 1_700_000_000_000L,
        )
        val decoded = Knowledge.decode(Knowledge.encode(listOf(card)))
        assertEquals(1, decoded.size)
        assertEquals(card.topic, decoded[0].topic)
        assertEquals(card.summary, decoded[0].summary)
        assertEquals(card.source, decoded[0].source)
        assertEquals(card.url, decoded[0].url)
        assertEquals(card.at, decoded[0].at)
    }

    @Test
    fun aNewlineInASummaryDoesNotBecomeASecondCard() {
        val card = Knowledge.Card(
            topic = "Photosynthesis",
            summary = "Plants make food.\nThey use light.\r\nChlorophyll helps.",
            source = "WIKIPEDIA",
        )
        val decoded = Knowledge.decode(Knowledge.encode(listOf(card)))
        assertEquals(1, decoded.size)
        assertFalse(decoded[0].summary.contains('\n'))
        assertTrue(decoded[0].summary.contains("Plants make food."))
    }

    @Test
    fun severalCardsKeepTheirOrder() {
        val cards = listOf(
            Knowledge.Card("One", "first", "WIKIPEDIA"),
            Knowledge.Card("Two", "second", "DICTIONARY"),
            Knowledge.Card("Three", "third", "WIKIPEDIA"),
        )
        val decoded = Knowledge.decode(Knowledge.encode(cards))
        assertEquals(listOf("One", "Two", "Three"), decoded.map { it.topic })
    }

    @Test
    fun rubbishIsIgnoredRatherThanCrashing() {
        assertTrue(Knowledge.decode(null).isEmpty())
        assertTrue(Knowledge.decode("").isEmpty())
        assertTrue(Knowledge.decode("\u001E\u001E\u001F\u001E").isEmpty())
        assertTrue(Knowledge.decode("no separators at all").isEmpty())
    }

    @Test
    fun aMissingTimestampIsNotAnError() {
        val decoded = Knowledge.decode("Topic\u001FSummary\u001FWIKIPEDIA\u001F")
        assertEquals(1, decoded.size)
        assertEquals(0L, decoded[0].at)
        assertEquals("", decoded[0].url)
    }

    @Test
    fun theShelfHasAUpperBound() {
        // A year of daily questions must not grow without limit.
        assertEquals(200, Knowledge.MAX_CARDS)
    }
}
