package com.jarvis.assistant

import com.jarvis.assistant.voice.Brain
import com.jarvis.assistant.voice.Lang
import com.jarvis.assistant.voice.MemoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory rules and the conversation block.
 *
 * A wrong memory is repeated back with total confidence, so the rules are deliberately narrow —
 * these tests exist mostly to prove what is *not* learned.
 */
class BrainTest {

    @Test
    fun aSisterIsRememberedAsASister() {
        val learned = MemoryRules.from("my sister is Khushi")
        assertNotNull(learned?.person)
        assertEquals("Khushi", learned?.person?.name)
        assertEquals("sister", learned?.person?.relation)
    }

    @Test
    fun rememberThatAlsoWorks() {
        val learned = MemoryRules.from("remember that Amit is my boss")
        assertEquals("Amit", learned?.person?.name)
        assertEquals("boss", learned?.person?.relation)
    }

    @Test
    fun hinglishWordOrderWorksBothWays() {
        val first = MemoryRules.from("khushi meri behen hai")
        assertEquals("Khushi", first?.person?.name)
        assertEquals("behen", first?.person?.relation)

        val second = MemoryRules.from("meri behen ka naam khushi hai")
        assertEquals("Khushi", second?.person?.name)
        assertEquals("behen", second?.person?.relation)
    }

    @Test
    fun aNameIsAFactAboutTheOperator() {
        val learned = MemoryRules.from("my name is rishu")
        assertEquals("name", learned?.fact?.key)
        assertEquals("Rishu", learned?.fact?.value)
    }

    @Test
    fun aBirthdayBelongsToItsPerson() {
        val learned = MemoryRules.from("khushi's birthday is 12 may")
        assertEquals("12 may", learned?.fact?.value)
        assertTrue(learned?.fact?.key?.startsWith("birthday:") == true)
    }

    @Test
    fun preferencesAreKeptAsFacts() {
        assertEquals("tea", MemoryRules.from("i like tea")?.fact?.value)
        assertEquals("loud music", MemoryRules.from("i hate loud music")?.fact?.value)
        assertEquals("blue", MemoryRules.from("my favourite colour is blue")?.fact?.value)
    }

    @Test
    fun ordinarySentencesTeachNothing() {
        // The important half of the rule: silence is better than a confident wrong memory.
        assertNull(MemoryRules.from("open the gallery"))
        assertNull(MemoryRules.from("what is my battery"))
        assertNull(MemoryRules.from("hello jarvis"))
        assertNull(MemoryRules.from("call dad"))
    }

    @Test
    fun contextBlockCarriesWhoHeIsAndWhatMatters() {
        val block = Brain.contextBlock(
            world = "Time: Tuesday 9:00 AM\nBattery: 40%",
            people = listOf(Brain.Person("Khushi", "sister")),
            facts = listOf(Brain.Fact("city", "Delhi")),
            thread = listOf(Brain.Turn("hi", "Evening.", Brain.Kind.CHAT)),
            language = Lang.HINGLISH,
        )
        assertTrue(block.contains("Rishu Jaswar"))
        assertTrue(block.contains("Khushi (sister)"))
        assertTrue(block.contains("city: Delhi"))
        assertTrue(block.contains("Tuesday 9:00 AM"))
        // The rule that keeps keys and providers out of conversation.
        assertTrue(block.contains("Never mention a model"))
        assertTrue(block.contains("HINGLISH"))
    }

    @Test
    fun smallTalkWorksWithoutAKeyAndInEveryTemplatedLanguage() {
        val english = Brain.smallTalk("how are you", Lang.EN)
        val hindi = Brain.smallTalk("how are you", Lang.HI)
        val hinglish = Brain.smallTalk("kaise ho", Lang.HINGLISH)
        assertNotNull(english)
        assertNotNull(hindi)
        assertNotNull(hinglish)
        assertTrue(hindi!!.any { it.code > 0x0900 })
        assertFalse(english == hindi)
    }

    @Test
    fun aCommandIsNotMistakenForChat() {
        assertNull(Brain.smallTalk("open gallery", Lang.EN))
        assertNull(Brain.smallTalk("set an alarm for 6", Lang.EN))
    }

    @Test
    fun theNameIsUsedWhenHeKnowsIt() {
        val reply = Brain.smallTalk("who am i", Lang.EN, operatorName = "Rishu")
        assertNotNull(reply)
        assertTrue(reply!!.contains("Rishu"))
    }
}
