package com.jarvis.assistant

import com.jarvis.assistant.connect.WebRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of web reading that can go wrong quietly: finding a link in a sentence, refusing the
 * addresses that should never be fetched, and turning HTML into prose.
 */
class WebReadTest {

    // ---------------------------------------------------------------------------------------------
    // Finding a link
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aLinkIsLiftedOutOfASentence() {
        assertEquals(
            "https://example.com/story",
            WebRead.urlIn("jarvis, read this https://example.com/story"),
        )
        assertEquals(
            "https://example.com/a?b=1&c=2",
            WebRead.urlIn("padho https://example.com/a?b=1&c=2 please"),
        )
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheAddress() {
        assertEquals("https://example.com/x", WebRead.urlIn("yeh dekho https://example.com/x."))
        assertEquals("https://example.com/x", WebRead.urlIn("(https://example.com/x)"))
        assertEquals("https://example.com/x", WebRead.urlIn("https://example.com/x?"))
    }

    @Test
    fun aSentenceWithoutALinkHasNoLink() {
        assertNull(WebRead.urlIn("open whatsapp"))
        assertNull(WebRead.urlIn("example.com is a site"))
        assertNull(WebRead.urlIn(""))
    }

    // ---------------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------------

    @Test
    fun onlyHttpsIsAllowed() {
        assertTrue(WebRead.isAllowed("https://example.com"))
        assertFalse(WebRead.isAllowed("http://example.com"))
        assertFalse(WebRead.isAllowed("file:///etc/passwd"))
        assertFalse(WebRead.isAllowed("ftp://example.com"))
        assertFalse(WebRead.isAllowed("javascript:alert(1)"))
    }

    @Test
    fun theLocalNetworkIsNotReachableByASentence() {
        // The whole point: a spoken sentence must never become a probe of the operator's own network.
        assertFalse(WebRead.isAllowed("https://localhost"))
        assertFalse(WebRead.isAllowed("https://localhost:8080/admin"))
        assertFalse(WebRead.isAllowed("https://router.local"))
        assertFalse(WebRead.isAllowed("https://printer.home.arpa"))
        assertFalse(WebRead.isAllowed("https://127.0.0.1"))
        assertFalse(WebRead.isAllowed("https://10.0.0.5"))
        assertFalse(WebRead.isAllowed("https://192.168.1.1"))
        assertFalse(WebRead.isAllowed("https://172.16.4.4"))
        assertFalse(WebRead.isAllowed("https://169.254.1.1"))
        assertFalse(WebRead.isAllowed("https://[::1]/"))
        assertFalse(WebRead.isAllowed("not a url"))
    }

    @Test
    fun aPublicAddressWithAnAwkwardNumberIsStillFine() {
        assertTrue(WebRead.isAllowed("https://172.32.0.1"))
        assertTrue(WebRead.isAllowed("https://8.8.8.8"))
    }

    // ---------------------------------------------------------------------------------------------
    // Extraction
    // ---------------------------------------------------------------------------------------------

    @Test
    fun scriptsAndMenusDoNotBecomeProse() {
        val html = """
            <html><head><title>Monsoon in Delhi</title>
            <style>.nav{color:red}</style>
            <script>var x = "this is not prose and must never be summarised";</script>
            </head><body>
            <nav><a href="/">Home</a><a href="/about">About</a></nav>
            <h1>Monsoon in Delhi</h1>
            <p>Delhi received 42 millimetres of rain in the last twenty-four hours.</p>
            <p>Two flights were delayed and the airport road was waterlogged for an hour.</p>
            <footer>Copyright 2026</footer>
            </body></html>
        """.trimIndent()

        val text = WebRead.extractText(html)
        assertTrue(text.contains("42 millimetres"))
        assertTrue(text.contains("Two flights were delayed"))
        assertFalse("script content must not survive", text.contains("must never be summarised"))
        assertFalse("style content must not survive", text.contains("color:red"))
        assertFalse("footer must not survive", text.contains("Copyright"))
        assertFalse("tags must not survive", text.contains("<p>"))
    }

    @Test
    fun theTitleIsReadFromTitleOrHeading() {
        assertEquals("Monsoon in Delhi", WebRead.titleOf("<title>Monsoon in Delhi</title>"))
        assertEquals("A heading", WebRead.titleOf("<h1>A heading</h1>"))
        assertEquals("", WebRead.titleOf("<p>nothing here</p>"))
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("tea & coffee", WebRead.decode("tea &amp; coffee"))
        assertEquals("it's 5\u20136", WebRead.decode("it&#39;s 5&ndash;6"))
        assertEquals("na\u00efve", WebRead.decode("na&#239;ve"))
    }

    @Test
    fun menulinesAreDroppedButSentencesSurvive() {
        val html = "<p>Home</p><p>About us</p>" +
            "<p>This is a real sentence with enough words to be worth keeping.</p>"
        val text = WebRead.extractText(html)
        assertTrue(text.contains("enough words"))
        assertFalse(text.contains("About us"))
    }

    @Test
    fun anEmptyPageStaysEmptyRatherThanBecomingAGuess() {
        assertEquals("", WebRead.extractText(""))
        assertEquals("", WebRead.extractText("<html><body><script>app()</script></body></html>"))
        assertTrue(WebRead.Page(url = "https://x.test", title = "", text = "").isEmpty)
    }

    @Test
    fun theFallbackSummaryTakesTheFirstSentences() {
        val text = "Delhi received heavy rain today. Two flights were delayed as a result. " +
            "More rain is expected tomorrow afternoon."
        val summary = WebRead.firstSentences(text, sentences = 2)
        assertTrue(summary.contains("heavy rain"))
        assertTrue(summary.contains("Two flights"))
        assertFalse(summary.contains("More rain is expected"))
    }
}
