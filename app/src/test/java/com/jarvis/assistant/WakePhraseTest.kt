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
}
