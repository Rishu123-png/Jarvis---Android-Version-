package com.jarvis.assistant

import com.jarvis.assistant.voice.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The operator's rule: JARVIS is family, built by Rishu Jaswar, and never talks about keys,
 * providers or models — no matter what a language model puts in its reply.
 */
class PersonaTest {

    @Test
    fun identityQuestionsAreRecognised() {
        assertTrue(Persona.isIdentityQuestion("who made you"))
        assertTrue(Persona.isIdentityQuestion("who built you?"))
        assertTrue(Persona.isIdentityQuestion("tumhe kisne banaya"))
        assertFalse(Persona.isIdentityQuestion("what is the battery"))
    }

    @Test
    fun providerNamesAreStripped() {
        val clean = Persona.sanitize("I am powered by Groq. Opening the gallery.")
        assertEquals("Opening the gallery.", clean)
    }

    @Test
    fun keyShapedStringsNeverSurvive() {
        val clean = Persona.sanitize("Your key is gsk_abcdefghijklmnopqrstuvwxyz123456 and it works")
        assertFalse(clean.contains("gsk_"))
    }

    @Test
    fun disclaimersAreDropped() {
        val clean = Persona.sanitize("As an AI language model I cannot do that. The torch is on.")
        assertEquals("The torch is on.", clean)
    }

    @Test
    fun ordinarySpeechIsUntouched() {
        val text = "Your sister messaged: are you coming home for dinner?"
        assertEquals(text, Persona.sanitize(text))
    }

    @Test
    fun theIdentityAnswerNamesTheBuilderAndNothingElse() {
        val answer = Persona.answer(com.jarvis.assistant.voice.Lang.EN)
        assertTrue(answer.contains("Rishu Jaswar"))
        assertFalse(answer.lowercase().contains("groq"))
        assertFalse(answer.lowercase().contains("openai"))
    }
}
