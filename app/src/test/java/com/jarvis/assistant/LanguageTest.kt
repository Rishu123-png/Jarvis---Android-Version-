package com.jarvis.assistant

import com.jarvis.assistant.voice.Lang
import com.jarvis.assistant.voice.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which language the operator is speaking, and whether JARVIS can answer in it offline. */
class LanguageTest {

    @Test
    fun devanagariIsHindi() {
        assertEquals(Lang.HI, Language.detect("गैलरी खोलो"))
        assertEquals(Lang.BENGALI, Language.scriptOf("গ্যালারি খোলো"))
        assertEquals(Lang.TAMIL, Language.scriptOf("திற"))
    }

    @Test
    fun hinglishNeedsAVerbOrQuestionWord() {
        assertEquals(Lang.HINGLISH, Language.detect("gallery kholo"))
        assertEquals(Lang.HINGLISH, Language.detect("kitne steps"))
        // A single filler does not change the language of an English sentence.
        assertEquals(Lang.EN, Language.detect("open the gallery yaar"))
    }

    @Test
    fun plainEnglishStaysEnglish() {
        assertEquals(Lang.EN, Language.detect("what did i miss"))
    }

    @Test
    fun repliesExistInEveryTemplatedLanguage() {
        val english = Language.say(Lang.EN, "steps", 4200)
        val hindi = Language.say(Lang.HI, "steps", 4200)
        val hinglish = Language.say(Lang.HINGLISH, "steps", 4200)
        assertTrue(english.contains("4200"))
        assertTrue(hindi.contains("4200"))
        assertTrue(hinglish.contains("4200"))
        assertTrue(english != hindi)
    }

    @Test
    fun unhousedLanguagesFallBackToASpokenLanguage() {
        // No Hindi or English templates for Japanese, so the reply uses the language that has them.
        assertEquals(Lang.EN, Lang.JAPANESE.replyLang)
        assertEquals(Lang.HI, Lang.MARATHI.replyLang)
        // ...but the TTS voice is still the right one.
        assertEquals("ja", Lang.JAPANESE.tag)
    }
}
