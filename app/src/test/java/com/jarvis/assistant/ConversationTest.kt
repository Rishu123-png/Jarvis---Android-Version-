package com.jarvis.assistant

import com.jarvis.assistant.voice.Conversation
import com.jarvis.assistant.voice.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar of a conversation: when it ends, what it asks, and when a captured window is worth
 * acting on.
 *
 * All of this is pure, which is the point — the microphone loop around it is impossible to unit
 * test, so everything that decides anything was pulled out of it.
 */
class ConversationTest {

    // ---------------------------------------------------------------------------------------------
    // Ending it
    // ---------------------------------------------------------------------------------------------

    @Test
    fun plainBasEndsIt() {
        assertTrue(Conversation.isExit("bas"))
        assertTrue(Conversation.isExit("Bas."))
        assertTrue(Conversation.isExit("theek hai"))
        assertTrue(Conversation.isExit("thank you"))
        assertTrue(Conversation.isExit("stop karo"))
        assertTrue(Conversation.isExit("kuch nahi"))
        assertTrue(Conversation.isExit("so jao"))
    }

    @Test
    fun punctuationAndCaseDoNotMatter() {
        assertTrue(Conversation.isExit("  BAS!! "))
        assertTrue(Conversation.isExit("Thank You, Jarvis."))
    }

    @Test
    fun aCorrectionIsNotAGoodbye() {
        // "nahi" alone is a no; "nahi, whatsapp kholo" is the operator changing their mind.
        assertFalse(Conversation.isExit("nahi whatsapp kholo"))
        assertFalse(Conversation.isExit("nahi ye nahi"))
        assertFalse(Conversation.isExit("open gallery"))
        assertFalse(Conversation.isExit("message divik hi on my way"))
        assertFalse(Conversation.isExit(""))
        assertFalse(Conversation.isExit(null))
    }

    @Test
    fun basAsATailEndsIt() {
        assertTrue(Conversation.isExit("aur nahi bas"))
        assertTrue(Conversation.isExit("itna hi bas"))
    }

    @Test
    fun stopEndsTheWindowRatherThanReachingTheRouter() {
        // Inside a conversation window "stop" closes the session. Outside one, the router sees it
        // normally — the exit table is only consulted after a turn has finished.
        assertTrue(Conversation.isExit("stop"))
        assertTrue(Conversation.isExit("stop karo"))
    }

    @Test
    fun aRealSentenceIsNeverAnExit() {
        val sentences = listOf(
            "whatsapp kholo",
            "divik ko message bhej do",
            "kal 5 baje ka alarm laga do",
            "kitne steps hai",
            "open gallery",
        )
        for (sentence in sentences) {
            assertFalse(sentence, Conversation.isExit(sentence))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // What it asks
    // ---------------------------------------------------------------------------------------------

    @Test
    fun afterOpeningAChatAppHeAsksWhoToMessage() {
        val line = Conversation.prompt(Lang.HINGLISH, "OPEN · WHATSAPP")
        assertTrue(line.contains("Boss", ignoreCase = true))
        assertTrue(line.lowercase().contains("message"))
    }

    @Test
    fun afterACallHeAsksWhoToCall() {
        assertTrue(Conversation.prompt(Lang.HINGLISH, "CALL · DIVIK").lowercase().contains("call"))
    }

    @Test
    fun afterAnAlarmHeAsksAboutMemory() {
        assertTrue(Conversation.prompt(Lang.HINGLISH, "ALARM · 07:00").lowercase().contains("yaad"))
    }

    @Test
    fun thePromptFollowsTheLanguage() {
        val hinglish = Conversation.prompt(Lang.HINGLISH, "")
        val english = Conversation.prompt(Lang.EN, "")
        assertTrue(hinglish.contains("Boss"))
        assertTrue(english.contains("boss"))
        assertFalse(english.contains("kya"))
    }

    @Test
    fun devanagariSpeakersGetADevanagariPrompt() {
        val hindi = Conversation.prompt(Lang.HI, "")
        assertTrue(hindi.any { it.code in 0x0900..0x097F })
    }

    @Test
    fun goodbyeExistsInEveryLanguageHeAnswers() {
        for (lang in listOf(Lang.EN, Lang.HI, Lang.HINGLISH)) {
            assertTrue(Conversation.goodbye(lang).isNotBlank())
            assertTrue(Conversation.slept(lang).isNotBlank())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the audio
    // ---------------------------------------------------------------------------------------------

    private fun pcm(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = (sample.toInt() and 0xFF).toByte()
            out[index * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun roomTone(frames: Int): ByteArray = ByteArray(frames * 2) { 0 }

    /** A tone loud enough to be speech: alternating full-scale samples, like a voiced vowel. */
    private fun loudTone(samples: Int): ByteArray =
        pcm(*ShortArray(samples) { if (it % 2 == 0) 9_000.toShort() else (-9_000).toShort() })

    @Test
    fun silenceIsNotAQuestion() {
        assertFalse(Conversation.hasSpeech(ByteArray(0)))
        assertFalse(Conversation.hasSpeech(roomTone(8_000)))
    }

    @Test
    fun speechIsHeard() {
        assertTrue(Conversation.hasSpeech(loudTone(8_000)))
    }

    @Test
    fun trailingSilenceIsMeasured() {
        // 500 ms of speech followed by 500 ms of room tone.
        val buffer = loudTone(8_000) + roomTone(8_000)

        val silent = Conversation.silenceAtEndMs(buffer)
        // 8000 samples at 16 kHz is 500 ms of quiet at the end.
        assertTrue(silent in 400L..600L)
    }

    @Test
    fun noTrailingSilenceWhenTheLastFrameIsLoud() {
        assertEquals(0L, Conversation.silenceAtEndMs(loudTone(8_000)))
    }

    @Test
    fun normalizationKeepsWordsAndDropsPunctuation() {
        assertEquals("open whatsapp", Conversation.normalize("Open WhatsApp!!"))
        assertEquals("kisko message karna hai", Conversation.normalize("Kisko message karna hai?"))
    }
}
