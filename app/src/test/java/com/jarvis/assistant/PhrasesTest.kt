package com.jarvis.assistant

import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.data.PhraseIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forgiving ear.
 *
 * Every case here is something a person would actually say, including the misspelled and Hinglish
 * ones, because the whole point is that nobody has to memorise a command list.
 */
class PhrasesTest {

    private fun intentOf(phrase: String): PhraseIntent? = PhraseBook.match(phrase)?.intent

    @Test
    fun plainRequestsMatch() {
        assertEquals(PhraseIntent.GALLERY, intentOf("open gallery"))
        assertEquals(PhraseIntent.STEPS, intentOf("how many steps"))
        assertEquals(PhraseIntent.BATTERY, intentOf("battery"))
        assertEquals(PhraseIntent.IDENTITY, intentOf("who made you"))
        assertEquals(PhraseIntent.NOTIF_MISSED, intentOf("what did i miss"))
        assertEquals(PhraseIntent.TORCH, intentOf("flashlight"))
    }

    @Test
    fun hinglishMatchesTheSameIntent() {
        assertEquals(PhraseIntent.GALLERY, intentOf("gallery kholo"))
        assertEquals(PhraseIntent.STEPS, intentOf("kitne steps"))
        assertEquals(PhraseIntent.IDENTITY, intentOf("tumhe kisne banaya"))
        assertEquals(PhraseIntent.TORCH, intentOf("torch band"))
    }

    @Test
    fun misspellingsStillLand() {
        assertEquals(PhraseIntent.GALLERY, intentOf("open galery"))
        assertEquals(PhraseIntent.IDENTITY, intentOf("who build you"))
    }

    @Test
    fun unknownPhrasesAreNotGuessed() {
        // Nothing here is a command; guessing would be worse than asking.
        assertTrue(PhraseBook.match("the quick brown fox jumps") == null)
        assertTrue(PhraseBook.match("xyzzy plugh") == null)
    }

    @Test
    fun exactWordingBeatsAShorterPrefix() {
        // "open" alone is OPEN_APP; "open gallery" must not be swallowed by it.
        assertEquals(PhraseIntent.OPEN_APP, intentOf("open"))
        assertEquals(PhraseIntent.GALLERY, intentOf("open gallery"))
    }

    @Test
    fun similarityIsSymmetricAndBounded() {
        assertEquals(1.0, PhraseBook.jaroWinkler("gallery", "gallery"), 0.0001)
        assertEquals(0.0, PhraseBook.jaroWinkler("", "gallery"), 0.0001)
        val close = PhraseBook.jaroWinkler("galery", "gallery")
        val far = PhraseBook.jaroWinkler("galery", "battery")
        assertTrue(close > far)
        assertTrue(close > 0.9)
    }
}
