package com.jarvis.assistant

import com.jarvis.assistant.voice.Brain
import com.jarvis.assistant.voice.Lang
import com.jarvis.assistant.voice.Persona
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who he is, and whose he is. These are the lines every model call starts from, so a test here
 * protects every conversation at once.
 */
class BossIdentityTest {

    @Test
    fun identityNamesJarvisTheBossAndTheBuilder() {
        val text = Persona.IDENTITY
        assertTrue(text.contains("You are JARVIS"))
        assertTrue(text.contains("boss"))
        assertTrue(text.contains("built by Rishu Jaswar"))
    }

    @Test
    fun identityNeverInvitesTheModelToNameItsProvider() {
        val text = Persona.IDENTITY.lowercase()
        assertTrue(text.contains("never name a provider"))
        assertTrue(text.contains("never call yourself a language model"))
    }

    @Test
    fun contextBlockCarriesIdentityAndTheBossName() {
        val block = Brain.contextBlock(
            world = "",
            people = emptyList(),
            facts = listOf(Brain.Fact("name", "Rishu")),
            thread = emptyList(),
            language = Lang.HINGLISH,
        )
        assertTrue(block.contains("You are JARVIS"))
        assertTrue(block.contains("The boss's name is Rishu."))
        assertTrue(block.contains("Rishu Jaswar"))
    }

    @Test
    fun recentTalkTravelsAsMessagesNotAsAPastedTranscript() {
        val block = Brain.contextBlock(
            world = "",
            people = emptyList(),
            facts = emptyList(),
            thread = listOf(Brain.Turn(heard = "remind me at six", reply = "Done, six it is.", kind = Brain.Kind.COMMAND)),
            language = Lang.HINGLISH,
        )
        assertFalse(block.contains("The last few turns"))
        assertFalse(block.contains("remind me at six"))
    }
}
