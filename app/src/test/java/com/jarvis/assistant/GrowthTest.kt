package com.jarvis.assistant

import com.jarvis.assistant.voice.Growth
import com.jarvis.assistant.voice.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that keep "learning" from becoming "doing things on its own": he only offers to be
 * taught when asked about a gap, and only a real instruction counts as teaching material.
 */
class GrowthTest {

    private fun gap(heard: String, count: Int = 1) = Growth.Gap(heard = heard, reason = "test", count = count)

    // ---------------------------------------------------------------------------------------------
    // Asking about his own limits
    // ---------------------------------------------------------------------------------------------

    @Test
    fun askingWhatHeCannotDoIsRecognised() {
        assertTrue(Growth.wantsCatalogue("what can't you do"))
        assertTrue(Growth.wantsCatalogue("what cant you do"))
        assertTrue(Growth.wantsCatalogue("kya nahi kar sakte"))
        assertTrue(Growth.wantsCatalogue("kya nahi kar sakta tu"))
    }

    @Test
    fun ordinarySentencesAreNotCatalogueRequests() {
        assertFalse(Growth.wantsCatalogue("open whatsapp"))
        assertFalse(Growth.wantsCatalogue("kya kar rahe ho"))
        assertFalse(Growth.wantsCatalogue(""))
    }

    @Test
    fun theOfferToLearnIsExplicit() {
        assertTrue(Growth.offersToLearn("seekho"))
        assertTrue(Growth.offersToLearn("batao kaise karna hai"))
        assertTrue(Growth.offersToLearn("teach you something"))
        assertFalse(Growth.offersToLearn("open gallery"))
        assertFalse(Growth.offersToLearn(""))
    }

    // ---------------------------------------------------------------------------------------------
    // What counts as teaching material
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aRealInstructionLooksLikeAnInstruction() {
        assertTrue(Growth.looksLikeInstruction("jab main office jaun to dnd on kar do"))
        assertTrue(Growth.looksLikeInstruction("every night at 11 turn the torch off"))
        assertTrue(Growth.looksLikeInstruction("har roz 7 baje alarm laga do"))
    }

    @Test
    fun aPlainRequestIsNotAnInstruction() {
        // The gate that stops "open gallery" turning into a saved routine.
        assertFalse(Growth.looksLikeInstruction("open gallery"))
        assertFalse(Growth.looksLikeInstruction("kya haal hai"))
        assertFalse(Growth.looksLikeInstruction("jab")) // a marker alone is not enough
    }

    @Test
    fun forgettingTheFailureListIsRecognised() {
        assertTrue(Growth.wantsForget("failures bhool jao"))
        assertTrue(Growth.wantsForget("clear the failures"))
        assertFalse(Growth.wantsForget("open gallery"))
    }

    // ---------------------------------------------------------------------------------------------
    // Wording
    // ---------------------------------------------------------------------------------------------

    @Test
    fun anEmptyListSaysSoInsteadOfBlamingTheOperator() {
        val line = Growth.summaryLine(Lang.EN, emptyList(), 0)
        assertTrue(line.contains("Nothing"))
        assertTrue(line.contains("failed"))
    }

    @Test
    fun theListIsNumberedCountedAndEndsWithAPermissionQuestion() {
        val line = Growth.summaryLine(
            Lang.HINGLISH,
            listOf(gap("kitne din honge", count = 3), gap("mera bike kitna chala")),
            total = 7,
        )
        assertTrue(line.contains("1."))
        assertTrue(line.contains("2."))
        assertTrue("a repeat is counted", line.contains("\u00D73"))
        assertTrue(line.contains("total 7"))
        assertTrue("the choice stays with the operator", line.contains("on karna aapka kaam"))
    }

    @Test
    fun theDraftIsAlwaysAnnouncedAsSwitchedOff() {
        for (lang in listOf(Lang.EN, Lang.HI, Lang.HINGLISH)) {
            val line = Growth.draftedLine(lang, "OFFICE MODE", 3)
            assertTrue(line.contains("OFFICE MODE"))
            assertTrue(line.contains("3"))
        }
        assertTrue(Growth.draftedLine(Lang.EN, "OFFICE MODE", 3).contains("switched off"))
        assertTrue(Growth.draftedLine(Lang.HINGLISH, "OFFICE MODE", 3).contains("band"))
    }

    @Test
    fun theSameFailureTwiceHasTheSameIdentity() {
        assertEquals(Growth.normalise("Kitne din honge?"), Growth.normalise("kitne   din honge"))
        assertEquals("", Growth.normalise("   "))
    }
}
