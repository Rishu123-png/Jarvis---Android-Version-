package com.jarvis.assistant

import com.jarvis.assistant.data.Vault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's format.
 *
 * Notes are the one thing in the app that exists nowhere else — no server copy, no export unless the
 * operator asks. A codec that silently drops a note loses the only copy, so it is pinned here.
 */
class VaultTest {

    private fun note(id: String, text: String, at: Long = 1_700_000_000_000L) =
        Vault.Note(id = id, at = at, text = text)

    @Test
    fun notesSurviveTheRoundTrip() {
        val original = listOf(
            note("a", "Khushi's allergy: peanuts"),
            note("b", "Locker number 14, second floor"),
        )
        val back = Vault.decode(Vault.encode(original))
        assertEquals(2, back.size)
        assertEquals(original[0].text, back[0].text)
        assertEquals(original[1].id, back[1].id)
        assertEquals(original[0].at, back[0].at)
    }

    @Test
    fun anEmptyDrawerIsValid() {
        assertEquals(0, Vault.decode(Vault.encode(emptyList())).size)
        assertEquals(0, Vault.decode(null).size)
        assertEquals(0, Vault.decode("").size)
    }

    @Test
    fun rubbishReadsAsEmptyRatherThanCrashing() {
        assertEquals(0, Vault.decode("{not json").size)
        assertEquals(0, Vault.decode("42").size)
    }

    @Test
    fun aNoteWithoutTextIsSkipped() {
        val json = """[{"id":"a","at":1,"text":"  "},{"id":"b","at":2,"text":"real"}]"""
        val back = Vault.decode(json)
        assertEquals(1, back.size)
        assertEquals("real", back[0].text)
    }

    @Test
    fun anOverlongNoteIsCutNotDropped() {
        val huge = "x".repeat(5_000)
        val json = """[{"id":"a","at":1,"text":"$huge"}]"""
        val back = Vault.decode(json)
        assertEquals(1, back.size)
        assertEquals(Vault.MAX_CHARS, back[0].text.length)
    }

    @Test
    fun theCapsAreSane() {
        // A drawer, not a database: bounded so the screen and the file stay small.
        assertTrue(Vault.MAX_NOTES in 50..500)
        assertTrue(Vault.MAX_CHARS in 200..5_000)
    }

    @Test
    fun jsonEscapesInNotesAreKept() {
        val tricky = "line one\nline two \"quoted\" & <tag>"
        val back = Vault.decode(Vault.encode(listOf(note("a", tricky))))
        assertEquals(tricky, back[0].text)
    }
}
