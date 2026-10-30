package com.jarvis.assistant

import com.jarvis.assistant.voice.AssistantRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assistant seat, as far as it can be tested without a phone.
 *
 * The role itself is the system's to grant, so nothing here can prove the gesture works. What can
 * be proved is that the panel never claims more than it knows, and that the instruction a person
 * reads on their own phone is the right instruction for that phone.
 */
class AssistantRoleTest {

    @Test
    fun heldIsTheOnlyStateThatReadsAsSettled() {
        assertEquals("HELD BY JARVIS", AssistantRole.label(AssistantRole.State.HELD))
        assertEquals("NOT SET", AssistantRole.label(AssistantRole.State.AVAILABLE))
        assertEquals(
            "NOT OFFERED BY THIS PHONE",
            AssistantRole.label(AssistantRole.State.UNSUPPORTED),
        )
    }

    @Test
    fun onlyTheHeldStateIsGreen() {
        assertTrue(AssistantRole.isGreen(AssistantRole.State.HELD))
        assertFalse(AssistantRole.isGreen(AssistantRole.State.AVAILABLE))
        assertFalse(AssistantRole.isGreen(AssistantRole.State.UNSUPPORTED))
    }

    @Test
    fun eachVendorGetsItsOwnInstructions() {
        val xiaomi = AssistantRole.gestureNote("Xiaomi")
        val oppo = AssistantRole.gestureNote("OPPO")
        val samsung = AssistantRole.gestureNote("samsung")
        val vivo = AssistantRole.gestureNote("vivo")
        val motorola = AssistantRole.gestureNote("motorola")

        assertTrue(xiaomi.contains("HyperOS") || xiaomi.contains("MIUI"))
        assertTrue(oppo.contains("ColorOS"))
        assertTrue(samsung.contains("One UI"))
        assertTrue(vivo.contains("Funtouch"))
        assertTrue(motorola.contains("Moto"))

        // Five manufacturers, five different answers — the whole point of the function.
        assertEquals(5, setOf(xiaomi, oppo, samsung, vivo, motorola).size)
    }

    @Test
    fun redmiAndPocoAreNotToldTheyAreASamsung() {
        assertTrue(AssistantRole.gestureNote("Redmi").contains("Home screen"))
        assertTrue(AssistantRole.gestureNote("POCO").contains("Home screen"))
    }

    @Test
    fun anUnknownPhoneGetsTheHonestGenericLine() {
        val note = AssistantRole.gestureNote(null)
        assertTrue(note.contains("Long-press Home"))
        assertTrue(note.contains("Digital assistant"))

        val unknown = AssistantRole.gestureNote("SomeBrand")
        assertEquals(note, unknown)
    }

    @Test
    fun theNoteNeverTellsAnyoneToSayAWakeWord() {
        // The gesture is the feature. If this line ever starts promising a hotword, it is lying.
        val makers = listOf("Xiaomi", "OPPO", "samsung", "vivo", "motorola", null, "")
        makers.forEach { maker ->
            val note = AssistantRole.gestureNote(maker).lowercase()
            assertFalse(note.contains("say"))
            assertFalse(note.contains("hey jarvis"))
        }
    }
}
