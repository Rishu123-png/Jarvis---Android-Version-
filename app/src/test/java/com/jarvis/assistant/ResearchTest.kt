package com.jarvis.assistant

import com.jarvis.assistant.device.Research
import com.jarvis.assistant.voice.Lang
import com.jarvis.assistant.voice.ResearchTalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The research path, without a network.
 *
 * Two things are worth proving here: that a question is read correctly in three languages (a
 * look-up on the wrong words wastes the operator's data and their patience), and that the canned
 * replies from the two free sources are parsed into the right shape.
 */
class ResearchTest {

    // ---------------------------------------------------------------------------------------------
    // Reading the request
    // ---------------------------------------------------------------------------------------------

    @Test
    fun explicitRequestsAreReadExactly() {
        assertEquals("nikola tesla", ResearchTalk.explicitTopicOf("look up nikola tesla"))
        assertEquals("black holes", ResearchTalk.explicitTopicOf("search for black holes"))
        assertEquals("photosynthesis", ResearchTalk.explicitTopicOf("tell me about photosynthesis"))
        assertEquals("the turing machine", ResearchTalk.explicitTopicOf("please google the turing machine"))
        assertEquals("quantum computing", ResearchTalk.explicitTopicOf("jarvis find out about quantum computing"))
    }

    @Test
    fun hinglishExplicitRequestsWork() {
        assertEquals("black hole", ResearchTalk.explicitTopicOf("black hole ke baare mein batao"))
        assertEquals("tesla", ResearchTalk.explicitTopicOf("tesla search karo"))
    }

    @Test
    fun questionsAreUnderstoodInEnglish() {
        assertEquals("black hole", ResearchTalk.topicOf("what is a black hole"))
        assertEquals("nikola tesla", ResearchTalk.topicOf("who was nikola tesla"))
        assertEquals("gravity", ResearchTalk.topicOf("do you know about gravity"))
        assertEquals("serendipity", ResearchTalk.topicOf("define serendipity"))
    }

    @Test
    fun questionsAreUnderstoodInHindiAndHinglish() {
        assertEquals("black hole", ResearchTalk.topicOf("black hole kya hai"))
        assertEquals("प्रकाश संश्लेषण", ResearchTalk.topicOf("प्रकाश संश्लेषण क्या है"))
        assertEquals("delhi", ResearchTalk.explicitTopicOf("delhi ke baare mein batao"))
    }

    @Test
    fun personalQuestionsNeverLeaveThePhone() {
        assertNull(ResearchTalk.topicOf("what is my name"))
        assertNull(ResearchTalk.topicOf("meri battery kya hai"))
        assertNull(ResearchTalk.explicitTopicOf("look up my messages"))
    }

    @Test
    fun deviceQuestionsAreNotLookups() {
        assertNull(ResearchTalk.topicOf("what is this"))
        assertNull(ResearchTalk.topicOf("how old am i"))
        assertNull(ResearchTalk.topicOf("what are you doing"))
        assertNull(ResearchTalk.topicOf("what is the time"))
        assertNull(ResearchTalk.explicitTopicOf("open the gallery"))
        assertNull(ResearchTalk.topicOf(""))
    }

    @Test
    fun yesAndNoAreBothUnderstood() {
        assertTrue(ResearchTalk.isYes("yes"))
        assertTrue(ResearchTalk.isYes("haan kar do"))
        assertTrue(ResearchTalk.isYes("go ahead"))
        assertTrue(ResearchTalk.isYes("हाँ"))
        assertFalse(ResearchTalk.isYes("no"))
        assertFalse(ResearchTalk.isYes("nahi"))

        assertTrue(ResearchTalk.isNo("no"))
        assertTrue(ResearchTalk.isNo("nahi"))
        assertTrue(ResearchTalk.isNo("leave it"))
        assertTrue(ResearchTalk.isNo("not now"))
        assertFalse(ResearchTalk.isNo("haan"))
        assertFalse(ResearchTalk.isNo("yes"))
    }

    @Test
    fun heCanShowWhatHeLearned() {
        assertTrue(ResearchTalk.asksWhatHeKnows("what have you learned"))
        assertTrue(ResearchTalk.asksWhatHeKnows("kya seekha"))
        assertFalse(ResearchTalk.asksWhatHeKnows("open gallery"))
    }

    @Test
    fun theAnswerIsAttributedInTheRightLanguage() {
        assertTrue(ResearchTalk.answered(Lang.EN, "text", "WIKIPEDIA").startsWith("Wikipedia says:"))
        assertTrue(ResearchTalk.answered(Lang.HI, "text", "WIKIPEDIA").contains("विकिपीडिया"))
        assertTrue(ResearchTalk.answered(Lang.HINGLISH, "text", "DICTIONARY").contains("Dictionary"))
        assertEquals("hi", ResearchTalk.wikiOf(Lang.HI))
        assertEquals("en", ResearchTalk.wikiOf(Lang.EN))
        assertTrue(ResearchTalk.ask(Lang.EN, "black holes").contains("black holes"))
    }

    // ---------------------------------------------------------------------------------------------
    // The free sources
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theUrlsAreCorrectAndEncoded() {
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/black%20hole",
            Research.summaryUrl("en", "black hole"),
        )
        assertTrue(Research.searchUrl("en", "black hole").contains("srsearch=black%20hole"))
        assertEquals("https://hi.wikipedia.org", "https://" + Research.host("HI"))
        assertTrue(Research.dictionaryUrl("serendipity").endsWith("/serendipity"))
    }

    @Test
    fun aSearchResultIsReadFromTheWikipediaShape() {
        val body = """
            {"batchcomplete":"","query":{"search":[{"ns":0,"title":"Black hole","size":12345}]}}
        """.trimIndent()
        assertEquals("Black hole", Research.parseSearch(body))
        assertNull(Research.parseSearch("""{"query":{"search":[]}}"""))
        assertNull(Research.parseSearch("not json at all"))
    }

    @Test
    fun aSummaryKeepsTheOpeningSentencesAndTheSource() {
        val body = """
            {"type":"standard","title":"Black hole",
             "extract":"A black hole is a region of spacetime where gravity is so strong that nothing can escape it. General relativity predicts that a sufficiently compact mass can form a black hole.",
             "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Black_hole"}}}
        """.trimIndent()
        val finding = Research.parseSummary(body)
        assertNotNull(finding)
        assertEquals("Black hole", finding?.topic)
        assertEquals("WIKIPEDIA", finding?.source)
        assertEquals("https://en.wikipedia.org/wiki/Black_hole", finding?.url)
        assertTrue(finding?.summary.orEmpty().contains("region of spacetime"))
    }

    @Test
    fun aPageWithoutTextIsNotAnAnswer() {
        assertNull(Research.parseSummary("""{"title":"Empty","extract":""}"""))
        assertNull(Research.parseSummary("garbage"))
    }

    @Test
    fun aDictionaryEntryBecomesAnAnswer() {
        val body = """
            [{"word":"serendipity","meanings":[{"partOfSpeech":"noun",
              "definitions":[{"definition":"The occurrence of events by chance in a happy way."}]}]}]
        """.trimIndent()
        val finding = Research.parseDefinition(body)
        assertNotNull(finding)
        assertEquals("serendipity", finding?.topic)
        assertEquals("DICTIONARY", finding?.source)
        assertTrue(finding?.summary.orEmpty().startsWith("(noun)"))
        assertNull(Research.parseDefinition("[]"))
    }

    @Test
    fun anEmptyTopicIsRejectedBeforeAnyRequest() {
        assertNull(ResearchTalk.topicOf("   "))
        assertNull(ResearchTalk.explicitTopicOf("look up"))
    }
}
