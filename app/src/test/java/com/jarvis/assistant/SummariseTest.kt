package com.jarvis.assistant

import com.jarvis.assistant.data.Summarise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The summariser that runs without a model.
 *
 * Its job is narrow and worth testing precisely: given a paragraph from an encyclopedia and the
 * question that produced it, return the sentence that answers the question, short enough to speak.
 * When it cannot, it must return something true and short rather than nothing.
 */
class SummariseTest {

    private val blackHole = "A black hole is a region of spacetime where gravity is so strong that " +
        "nothing can escape it. The theory of general relativity predicts that any sufficiently " +
        "compact mass can form a black hole. Objects whose gravitational fields are too strong for " +
        "light to escape were first considered in the 18th century."

    @Test
    fun theSentenceThatAnswersTheQuestionWins() {
        val answer = Summarise.best("how strong is the gravity of a black hole", blackHole)
        assertTrue(answer.contains("gravity is so strong"))
        assertTrue(answer.length <= 280)
    }

    @Test
    fun aQuestionAboutHistoryFindsTheHistorySentence() {
        val answer = Summarise.best("when were black holes first considered", blackHole)
        assertTrue(answer.contains("18th century"))
    }

    @Test
    fun nothingInCommonFallsBackToTheOpeningSentence() {
        val answer = Summarise.best("quantum chromodynamics", blackHole)
        assertTrue(answer.startsWith("A black hole is a region"))
    }

    @Test
    fun hindiTextIsStillSummarisedByPosition() {
        val hindi = "प्रकाश संश्लेषण वह प्रक्रिया है जिसमें पौधे सूर्य के प्रकाश से भोजन बनाते हैं। " +
            "इसमें कार्बन डाइऑक्साइड और पानी की आवश्यकता होती है।"
        val answer = Summarise.best("प्रकाश संश्लेषण", hindi, maxSentences = 1)
        assertTrue(answer.startsWith("प्रकाश संश्लेषण वह प्रक्रिया"))
        assertFalse(answer.contains("कार्बन डाइऑक्साइड और पानी की आवश्यकता होती है।"))
    }

    @Test
    fun trimNeverCutsMidWord() {
        val long = "word ".repeat(80).trim()
        val cut = Summarise.trim(long, 30)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 31)
        // Every word that survived is whole: the text before the ellipsis is "word word ...".
        val body = cut.removeSuffix("…").trim()
        assertTrue(body.split(' ').all { it == "word" })
    }

    @Test
    fun shortTextComesBackUntouched() {
        assertEquals("Delhi is a city.", Summarise.trim("Delhi is a city.", 200))
    }

    @Test
    fun footnotesAreStripped() {
        assertEquals("Tesla was an inventor.", Summarise.tidy("Tesla was an inventor.[12]"))
    }

    @Test
    fun sentencesSplitOnFullStopsAndDanda() {
        val parts = Summarise.sentences(
            "The first sentence is here and it is long. दूसरा वाक्य यहाँ है और यह काफी लंबा है।",
        )
        assertEquals(2, parts.size)
    }

    @Test
    fun keywordsIgnoreFillerWords() {
        val words = Summarise.keywords("What is the gravity of a black hole")
        assertTrue(words.contains("gravity"))
        assertTrue(words.contains("black"))
        assertFalse(words.contains("the"))
        assertFalse(words.contains("what"))
    }

    @Test
    fun emptyInputIsNotAnError() {
        assertEquals("", Summarise.best("anything", ""))
        assertEquals("", Summarise.trim("", 40))
        assertEquals("", Summarise.best("anything", "tiny.", maxSentences = 0))
    }

    @Test
    fun aListSentenceWithAColonIsNotPreferred() {
        val text = "Photosynthesis is the process plants use to make food. It has two stages: " +
            "the light reactions and the Calvin cycle. The process happens in the chloroplast."
        val answer = Summarise.best("photosynthesis process", text)
        assertFalse(answer.startsWith("It has two stages:"))
    }
}
