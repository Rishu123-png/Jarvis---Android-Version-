package com.jarvis.assistant

import com.jarvis.assistant.data.Archive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory file.
 *
 * A backup that silently loses a value, or that quietly carries the AI key, is worse than no
 * backup: one case loses who your sister is, the other leaks a credential into a forwarded chat.
 * Both are tested here, along with the format refusing anything it did not write.
 */
class ArchiveTest {

    private val snapshot = mapOf(
        "jarvis_brain" to mapOf<String, Any?>(
            "people" to "[{\"name\":\"Khushi\",\"relation\":\"sister\"}]",
            "thread" to "[]",
        ),
        "jarvis_automation" to mapOf<String, Any?>(
            "workflows" to "[]",
            "armed" to true,
            "count" to 4,
            "lastRun" to 1_700_000_000_000L,
            "ratio" to 0.5f,
        ),
        "jarvis_ai" to mapOf<String, Any?>("key" to "sk-should-never-appear"),
    )

    @Test
    fun everyTypeSurvivesTheRoundTrip() {
        val decoded = Archive.decode(Archive.encode(snapshot))
        assertNotNull(decoded)
        val automation = decoded?.get("jarvis_automation")
        assertEquals("[]", automation?.get("workflows"))
        assertEquals(true, automation?.get("armed"))
        assertEquals(4, automation?.get("count"))
        assertEquals(1_700_000_000_000L, automation?.get("lastRun"))
        assertEquals(0.5f, automation?.get("ratio"))
    }

    @Test
    fun theAiKeyNeverLeavesThePhone() {
        val text = Archive.encode(snapshot)
        assertFalse(text.contains("sk-should-never-appear"))
        assertFalse(text.contains("jarvis_ai"))
        assertNull(Archive.decode(text)?.get("jarvis_ai"))
    }

    @Test
    fun theHeaderNamesTheFormatAndVersion() {
        val text = Archive.encode(snapshot, at = 1234L)
        assertTrue(text.contains("\"format\":\"jarvis-archive\""))
        assertTrue(text.contains("\"version\":" + Archive.VERSION))
        assertTrue(text.contains("\"at\":1234"))
    }

    @Test
    fun somethingThatIsNotAnArchiveIsRefused() {
        assertNull(Archive.decode("{}"))
        assertNull(Archive.decode("not json"))
        assertNull(Archive.decode("""{"format":"other","version":1,"buckets":{}}"""))
    }

    @Test
    fun aNewerFormatIsRefusedRatherThanMisread() {
        val future = """{"format":"jarvis-archive","version":${Archive.VERSION + 1},"buckets":{}}"""
        assertNull(Archive.decode(future))
    }

    @Test
    fun emptyBucketsDoNotBloatTheFile() {
        val text = Archive.encode(
            mapOf(
                "jarvis_empty" to emptyMap<String, Any?>(),
                "jarvis_brain" to mapOf<String, Any?>("people" to "[]"),
            ),
        )
        assertFalse(text.contains("jarvis_empty"))
        assertEquals(1, Archive.decode(text)?.size)
    }

    @Test
    fun theSummaryCountsWhatWillBeWritten() {
        val summary = Archive.summarise(snapshot)
        assertEquals(2, summary.buckets)
        assertEquals(7, summary.keys)
        assertEquals(1, summary.skipped)
    }

    @Test
    fun aSetIsFlattenedRatherThanDropped() {
        val text = Archive.encode(mapOf("jarvis_x" to mapOf<String, Any?>("tags" to setOf("a", "b"))))
        assertEquals("a\nb", Archive.decode(text)?.get("jarvis_x")?.get("tags"))
    }
}
