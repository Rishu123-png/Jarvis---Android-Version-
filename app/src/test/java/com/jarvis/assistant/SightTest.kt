package com.jarvis.assistant

import com.jarvis.assistant.device.Vision
import com.jarvis.assistant.voice.Lang
import com.jarvis.assistant.voice.Sight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eyes, decided before anything is photographed.
 *
 * The expensive mistakes are both here: photographing the ceiling because "what is this" was
 * mistaken for a lookup, and answering "read this" with a Wikipedia article. The other subject is
 * the downsample maths, because a 4000×3000 frame that is not shrunk is a slow upload and, on most
 * providers, a rejected request.
 */
class SightTest {

    @Test
    fun lookSentencesAreRecognised() {
        assertTrue(Sight.looksLikeLookAt("look at this"))
        assertTrue(Sight.looksLikeLookAt("read this for me"))
        assertTrue(Sight.looksLikeLookAt("what is this"))
        assertTrue(Sight.looksLikeLookAt("what do you see"))
        assertTrue(Sight.looksLikeLookAt("translate this"))
        assertTrue(Sight.looksLikeLookAt("look"))
    }

    @Test
    fun hindiAndHinglishWork() {
        assertTrue(Sight.looksLikeLookAt("yeh kya hai"))
        assertTrue(Sight.looksLikeLookAt("yeh dekho"))
        assertTrue(Sight.looksLikeLookAt("यह क्या है"))
        assertTrue(Sight.looksLikeLookAt("इसे पढ़ो"))
    }

    @Test
    fun ordinarySentencesAreNotLookRequests() {
        assertFalse(Sight.looksLikeLookAt("open gallery"))
        assertFalse(Sight.looksLikeLookAt("what is a black hole"))
        assertFalse(Sight.looksLikeLookAt("how many steps"))
        assertFalse(Sight.looksLikeLookAt(""))
    }

    @Test
    fun theOperatorsOwnWordsBecomeTheQuestion() {
        val question = Sight.question("is this healthy", Lang.EN)
        assertEquals("is this healthy", question)
    }

    @Test
    fun aBareLookBecomesADescriptionRequest() {
        val english = Sight.question("look", Lang.EN)
        assertTrue(english.contains("looking at"))
        val hinglish = Sight.question("dekho", Lang.HINGLISH)
        assertTrue(hinglish.contains("tasveer"))
        val hindi = Sight.question("देखो", Lang.HI)
        assertTrue(hindi.contains("तस्वीर"))
    }

    @Test
    fun thePromptForbidsInventingWhatIsNotThere() {
        val prompt = Sight.prompt(Lang.EN)
        assertTrue(prompt.contains("Never invent"))
        assertTrue(prompt.contains("Rishu Jaswar"))
        assertTrue(prompt.contains("Never mention a model"))
        assertTrue(Sight.prompt(Lang.HI).contains("hi-IN"))
    }

    @Test
    fun aPhoneCameraFrameIsSubsampledToSomethingUploadable() {
        // 4000x3000 -> factor 4 gives 1000x750, the first step at or below 1024.
        assertEquals(4, Vision.sampleSizeFor(4000, 3000))
        assertEquals(8, Vision.sampleSizeFor(8000, 6000))
        assertEquals(1, Vision.sampleSizeFor(800, 600))
        assertEquals(1, Vision.sampleSizeFor(1024, 768))
        assertEquals(2, Vision.sampleSizeFor(2048, 1536))
        assertEquals(1, Vision.sampleSizeFor(0, 0))
    }
}
