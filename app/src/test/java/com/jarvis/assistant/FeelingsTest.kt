package com.jarvis.assistant

import com.jarvis.assistant.voice.Feelings
import com.jarvis.assistant.voice.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emotional model, which is the part most likely to embarrass him.
 *
 * Two failure modes matter more than the rest, and both are pinned here: **wrong reading** (turning
 * a normal sentence into "you sound sad") and **hijacking a command** (answering "you sound tired"
 * when the operator asked for an alarm). Everything else is polish.
 */
class FeelingsTest {

    // ---------------------------------------------------------------------------------------------
    // Reading the operator
    // ---------------------------------------------------------------------------------------------

    @Test
    fun tirednessIsHeardInThreeLanguages() {
        assertEquals(Feelings.Affect.TIRED, Feelings.affect("I am so tired today"))
        assertEquals(Feelings.Affect.TIRED, Feelings.affect("aaj bahut thak gaya hoon"))
        assertEquals(Feelings.Affect.TIRED, Feelings.affect("मैं बहुत थक गया हूँ"))
    }

    @Test
    fun sadnessIsHeard() {
        assertEquals(Feelings.Affect.SAD, Feelings.affect("I feel low today"))
        assertEquals(Feelings.Affect.SAD, Feelings.affect("mann nahi lag raha"))
        assertEquals(Feelings.Affect.SAD, Feelings.affect("bahut udaas hoon"))
    }

    @Test
    fun angerAndAnxietyAreKeptApart() {
        assertEquals(Feelings.Affect.ANGRY, Feelings.affect("I am so angry right now"))
        assertEquals(Feelings.Affect.ANXIOUS, Feelings.affect("tension ho rahi hai meeting ko lekar"))
        assertEquals(Feelings.Affect.ANXIOUS, Feelings.affect("I am worried about tomorrow"))
    }

    @Test
    fun brightnessIsHeard() {
        assertEquals(Feelings.Affect.HAPPY, Feelings.affect("bahut khush hoon aaj"))
        assertEquals(Feelings.Affect.EXCITED, Feelings.affect("can't wait for the trip"))
        assertEquals(Feelings.Affect.LOVING, Feelings.affect("love you yaar"))
    }

    @Test
    fun aDenialOfHappinessIsNotHappiness() {
        // The single worst bug this model could have: "I am not happy" read as happy.
        assertEquals(Feelings.Affect.SAD, Feelings.affect("khush nahi hoon"))
        assertEquals(Feelings.Affect.SAD, Feelings.affect("I am not happy"))
        assertEquals(Feelings.Affect.SAD, Feelings.affect("khush to bilkul nahi hoon"))
    }

    @Test
    fun everydayFillersAreNotEmotions() {
        assertEquals(Feelings.Affect.NEUTRAL, Feelings.affect("accha theek hai"))
        assertEquals(Feelings.Affect.NEUTRAL, Feelings.affect("ok"))
        assertEquals(Feelings.Affect.NEUTRAL, Feelings.affect("haan"))
        assertEquals(Feelings.Affect.NEUTRAL, Feelings.affect(""))
    }

    @Test
    fun aCommandIsNotHijackedByAFeelingWord() {
        // The alarm has to be set; the reply can be gentle, but the command must survive.
        assertEquals(Feelings.Affect.NEUTRAL, Feelings.affect("tired hoon, alarm laga do"))
        assertEquals(Feelings.Affect.NEUTRAL, Feelings.affect("open gallery"))
        assertTrue(Feelings.carriesCommand("tired hoon, alarm laga do"))
        assertFalse(Feelings.carriesCommand("aaj bahut thak gaya hoon"))
    }

    @Test
    fun aStrongFeelingSurvivesACommandInTheSameSentence() {
        assertEquals(Feelings.Affect.ANGRY, Feelings.affect("bahut gussa hai, message bhej do"))
        assertEquals(Feelings.Affect.SAD, Feelings.affect("bahut udaas hoon, call karo"))
    }

    // ---------------------------------------------------------------------------------------------
    // His register
    // ---------------------------------------------------------------------------------------------

    @Test
    fun heavinessOutranksTheClock() {
        // Someone sad at 2 a.m. needs care, not "go to sleep".
        assertEquals(
            Feelings.Mood.CONCERNED,
            Feelings.moodOf(hour = 2, affect = Feelings.Affect.SAD),
        )
        assertEquals(
            Feelings.Mood.SLEEPY,
            Feelings.moodOf(hour = 2, affect = Feelings.Affect.NEUTRAL),
        )
    }

    @Test
    fun tirednessBecomesAQuietTone() {
        assertEquals(
            Feelings.Mood.SLEEPY,
            Feelings.moodOf(hour = 15, affect = Feelings.Affect.TIRED),
        )
    }

    @Test
    fun happinessIsLightAndAnOldBondIsWarm() {
        assertEquals(
            Feelings.Mood.PLAYFUL,
            Feelings.moodOf(hour = 15, affect = Feelings.Affect.HAPPY),
        )
        assertEquals(
            Feelings.Mood.WARM,
            Feelings.moodOf(
                hour = 15,
                affect = Feelings.Affect.NEUTRAL,
                bond = Feelings.Bond(firstMetAt = 1L, turns = 30, daysTogether = 12),
            ),
        )
        assertEquals(
            Feelings.Mood.CALM,
            Feelings.moodOf(hour = 15, affect = Feelings.Affect.NEUTRAL, bond = Feelings.Bond()),
        )
    }

    @Test
    fun theToneActuallyMovesTheVoice() {
        val concerned = Feelings.Tone.forMood(Feelings.Mood.CONCERNED)
        val playful = Feelings.Tone.forMood(Feelings.Mood.PLAYFUL)
        assertTrue("concerned must be softer", concerned.pitch < 1f)
        assertTrue("concerned must be slower", concerned.rate < 1f)
        assertTrue("playful must lift", playful.pitch > 1f)
        assertEquals(Feelings.Tone.NEUTRAL, Feelings.Tone.forMood(Feelings.Mood.CALM))
    }

    // ---------------------------------------------------------------------------------------------
    // The bond
    // ---------------------------------------------------------------------------------------------

    @Test
    fun daysTogetherAreCalendarDays() {
        val day = 24 * 60 * 60 * 1000L
        val start = 1_700_000_000_000L
        assertEquals(0, Feelings.daysBetween(start, start))
        assertEquals(3, Feelings.daysBetween(start, start + 3 * day))
        assertEquals(0, Feelings.daysBetween(start, start - day))
        assertEquals(0, Feelings.daysBetween(0L, start))
    }

    @Test
    fun aNewBondSaysSo() {
        assertTrue(Feelings.Bond().isNew)
        assertFalse(Feelings.Bond(firstMetAt = 1L, daysTogether = 9).isNew)
    }

    @Test
    fun theObservationIsHonestAboutBeingAnObservation() {
        assertNull(Feelings.observation(Feelings.Affect.NEUTRAL, Feelings.Bond()))
        val line = Feelings.observation(Feelings.Affect.SAD, Feelings.Bond())
        assertNotNull(line)
        assertTrue(line!!.contains("Do not name it like a diagnosis"))
        val old = Feelings.observation(
            Feelings.Affect.TIRED,
            Feelings.Bond(firstMetAt = 1L, turns = 40, daysTogether = 11, isNew = false),
        )
        assertTrue(old!!.contains("11 days"))
    }

    // ---------------------------------------------------------------------------------------------
    // What he says
    // ---------------------------------------------------------------------------------------------

    @Test
    fun onlyHeavyFeelingsGetAnAcknowledgement() {
        assertNull(Feelings.acknowledge(Feelings.Affect.NEUTRAL, Lang.EN))
        assertNull(Feelings.acknowledge(Feelings.Affect.HAPPY, Lang.EN))
        for (affect in listOf(
            Feelings.Affect.TIRED,
            Feelings.Affect.SAD,
            Feelings.Affect.ANXIOUS,
            Feelings.Affect.ANGRY,
        )) {
            assertNotNull(Feelings.acknowledge(affect, Lang.EN, seed = 0))
        }
    }

    @Test
    fun theAcknowledgementFollowsTheLanguage() {
        val hindi = Feelings.acknowledge(Feelings.Affect.SAD, Lang.HI)!!
        val hinglish = Feelings.acknowledge(Feelings.Affect.SAD, Lang.HINGLISH)!!
        val english = Feelings.acknowledge(Feelings.Affect.SAD, Lang.EN)!!
        assertTrue("Hindi must be Devanagari", hindi.any { it.code in 0x0900..0x097F })
        assertFalse("Hinglish must stay Roman", hinglish.any { it.code in 0x0900..0x097F })
        assertNotEquals(hinglish, english)
    }

    @Test
    fun theSeedPicksAVariantSoHeDoesNotRepeatHimself() {
        val first = Feelings.acknowledge(Feelings.Affect.TIRED, Lang.EN, seed = 0)
        val second = Feelings.acknowledge(Feelings.Affect.TIRED, Lang.EN, seed = 1)
        assertNotEquals(first, second)
        // …and the same seed is stable, so a retry does not change the sentence.
        assertEquals(first, Feelings.acknowledge(Feelings.Affect.TIRED, Lang.EN, seed = 0))
    }

    @Test
    fun aFinishedTaskGetsOneShortTailWhenTheDayWasHard() {
        assertNull(Feelings.afterCommand(Feelings.Affect.NEUTRAL, Lang.EN))
        assertNull(Feelings.afterCommand(Feelings.Affect.HAPPY, Lang.EN))
        assertNotNull(Feelings.afterCommand(Feelings.Affect.TIRED, Lang.HINGLISH))
        assertTrue(Feelings.afterCommand(Feelings.Affect.SAD, Lang.EN)!!.startsWith("Done"))
    }

    @Test
    fun carryOverLinesExistForEveryHeavyFeeling() {
        for (affect in listOf(
            Feelings.Affect.TIRED,
            Feelings.Affect.SAD,
            Feelings.Affect.ANXIOUS,
            Feelings.Affect.ANGRY,
        )) {
            assertTrue(Feelings.carryOverLine(affect, Lang.HINGLISH).isNotBlank())
            assertTrue(Feelings.carryOverLine(affect, Lang.HI).isNotBlank())
            assertTrue(Feelings.carryOverLine(affect, Lang.EN).isNotBlank())
        }
        assertEquals("", Feelings.carryOverLine(Feelings.Affect.NEUTRAL, Lang.EN))
    }

    // ---------------------------------------------------------------------------------------------
    // Care, from real signals only
    // ---------------------------------------------------------------------------------------------

    @Test
    fun lateNightIsMentionedOnce() {
        val line = Feelings.careLine(Lang.HINGLISH, hour = 1)
        assertNotNull(line)
        assertTrue(line!!.contains("Raat"))
    }

    @Test
    fun aDyingBatteryIsARealReason() {
        val line = Feelings.careLine(Lang.EN, hour = 14, batteryPercent = 8)
        assertTrue(line!!.contains("8%"))
        // Charging means nothing is wrong.
        assertNull(Feelings.careLine(Lang.EN, hour = 14, batteryPercent = 8, charging = true))
    }

    @Test
    fun hydrationAndMovementNeedTheirOwnHours() {
        assertNotNull(Feelings.careLine(Lang.EN, hour = 16, waterMl = 0))
        assertNull(Feelings.careLine(Lang.EN, hour = 9, waterMl = 0))
        assertNotNull(Feelings.careLine(Lang.EN, hour = 19, steps = 400))
        assertNull(Feelings.careLine(Lang.EN, hour = 19, steps = 6000))
    }

    @Test
    fun nothingIsSaidWithoutASignal() {
        assertNull(Feelings.careLine(Lang.EN, hour = 14))
        assertNull(Feelings.careLine(Lang.EN, hour = 14, batteryPercent = 80, waterMl = 900, steps = 5000))
    }

    @Test
    fun unreadMessagesOnlyMatterInBulk() {
        assertNotNull(Feelings.careLine(Lang.EN, hour = 14, unread = 7))
        assertNull(Feelings.careLine(Lang.EN, hour = 14, unread = 2))
    }
}
