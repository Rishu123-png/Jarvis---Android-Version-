package com.jarvis.assistant

import com.jarvis.assistant.wake.WakePhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Hey Jarvis" must fire on the phrase, and on nothing else. A wake word that fires on ordinary
 * conversation is worse than one that misses, because it opens the app in someone's face.
 */
class WakePhraseTest {

    @Test
    fun vosksJsonPartialAndFinalBothCount() {
        assertTrue(WakePhrase.heard("{\"partial\" : \"hey jarvis\"}"))
        assertTrue(WakePhrase.heard("{\"text\" : \"hey jarvis\"}"))
    }

    @Test
    fun punctuationCaseAndSpacingDoNotMatter() {
        assertTrue(WakePhrase.heard("{\"text\": \"Hey, Jarvis!\"}"))
        assertTrue(WakePhrase.heard("{\"text\": \"  HEY   JARVIS  \"}"))
    }

    @Test
    fun plainTextWithoutJsonIsAccepted() {
        assertTrue(WakePhrase.heard("hey jarvis"))
    }

    @Test
    fun theNameAloneDoesNotWake() {
        assertFalse(WakePhrase.heard("{\"text\": \"jarvis\"}"))
        assertFalse(WakePhrase.heard("{\"partial\": \"jarvis what time is it\"}"))
    }

    @Test
    fun ordinarySpeechDoesNotWake() {
        assertFalse(WakePhrase.heard("{\"text\": \"hey there how are you\"}"))
        assertFalse(WakePhrase.heard("{\"partial\": \"\"}"))
        assertFalse(WakePhrase.heard("{\"text\": \"\"}"))
        assertFalse(WakePhrase.heard(null))
        assertFalse(WakePhrase.heard("   "))
    }

    @Test
    fun grammarOffersOnlyThePhraseAndTheUnknownToken() {
        assertEquals("[\"hey jarvis\", \"[unk]\"]", WakePhrase.GRAMMAR)
    }

    @Test
    fun thePhraseIsCutOffTheFrontOfTheCommand() {
        assertEquals("open whatsapp", WakePhrase.strip("hey jarvis open whatsapp"))
        // The rest of the sentence keeps its own case and spelling: only the phrase is removed.
        assertEquals("Open WhatsApp", WakePhrase.strip("Hey, Jarvis! Open WhatsApp"))
        assertEquals("open whatsapp", WakePhrase.strip("hey jarvis, open whatsapp"))
        assertEquals("what time is it", WakePhrase.strip("jarvis what time is it"))
        assertEquals("open whatsapp", WakePhrase.strip("  HEY   JARVIS   open whatsapp  "))
    }

    @Test
    fun thePhraseIsOnlyCutWhenItIsAtTheFront() {
        // Nothing is removed from a command that only mentions him.
        assertEquals("open the jarvis notes", WakePhrase.strip("open the jarvis notes"))
        assertEquals("tell jarvis to stop", WakePhrase.strip("tell jarvis to stop"))
    }

    @Test
    fun anEmptyOrPhraseOnlyTranscriptHasNoCommand() {
        assertEquals("", WakePhrase.strip(null))
        assertEquals("", WakePhrase.strip("   "))
        assertEquals("", WakePhrase.strip("hey jarvis"))
        assertEquals("", WakePhrase.strip("Hey Jarvis."))
    }
}
