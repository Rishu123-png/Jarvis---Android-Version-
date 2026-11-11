package com.jarvis.assistant

import com.jarvis.assistant.data.MemorySearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the one memory that matters.
 *
 * The old prompt sent the first eight people and the first ten facts. Order was insertion order, so
 * the fact mentioned last month still lost to the one written on the first day. This ranks by what was
 * actually asked, and falls back to the old order when nothing was.
 */
class MemorySearchTest {

    private fun entry(text: String, kind: String = "fact") = MemorySearch.Entry(kind, text)

    @Test
    fun theMatchingMemoryComesFirst() {
        val entries = listOf(
            entry("sister Divik"),
            entry("office address Sector 62"),
            entry("favourite drink chai"),
        )
        val ranked = MemorySearch.rank("where is the office", entries, 2)
        assertEquals("office address Sector 62", ranked.first().text)
    }

    @Test
    fun nothingMatchesNothingIsReturned() {
        val entries = listOf(entry("favourite drink chai"))
        assertTrue(MemorySearch.rank("quantum chromodynamics", entries, 5).isEmpty())
    }

    @Test
    fun anEmptyQueryReturnsNoOrderRatherThanAGuess() {
        assertTrue(MemorySearch.rank("   ", listOf(entry("anything")), 5).isEmpty())
    }

    @Test
    fun aLongEntryHasToEarnItsPlace() {
        val short = entry("Divik birthday 12 March")
        val long = entry("had a long talk about nothing in particular, and Divik came up once, briefly")
        val ranked = MemorySearch.rank("Divik birthday", listOf(long, short), 1)
        assertEquals(short.text, ranked.first().text)
    }

    @Test
    fun hinglishAndDevanagariAreSplitIntoLettersNotSpaces() {
        assertTrue(MemorySearch.tokens("chai peene chalte hain").contains("chai"))
        val tokens = MemorySearch.tokens("दिविक का जन्मदिन")
        assertTrue(tokens.isNotEmpty())
        val ranked = MemorySearch.rank("जन्मदिन", listOf(entry("दिविक का जन्मदिन 12 मार्च")), 1)
        assertEquals(1, ranked.size)
    }

    @Test
    fun aPhraseHitBeatsTwoSeparateHits() {
        val phrase = entry("no calls after ten")
        val scattered = entry("ten calls, no answer, after lunch")
        val ranked = MemorySearch.rank("no calls after ten", listOf(scattered, phrase), 1)
        assertEquals(phrase.text, ranked.first().text)
    }

    @Test
    fun theBlockIsLabelledAndEmptyWhenThereIsNothing() {
        assertEquals("", MemorySearch.render(emptyList()))
        val text = MemorySearch.render(listOf(entry("chai", "fact"), entry("Divik", "person")))
        assertTrue(text.contains("(fact) chai"))
        assertTrue(text.contains("(person) Divik"))
    }

    @Test
    fun theLimitIsRespected() {
        val entries = (1..20).map { entry("note $it") }
        assertEquals(3, MemorySearch.rank("note", entries, 3).size)
    }
}
