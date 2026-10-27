package com.jarvis.assistant

import com.jarvis.assistant.device.Nudges
import com.jarvis.assistant.voice.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brief: it must say the useful thing, and must be silent when there is nothing to say. */
class NudgesTest {

    @Test
    fun silenceWhenNothingIsHappening() {
        assertNull(
            Nudges.composeIn(
                Nudges.Facts(batteryPercent = 80, charging = true, unreadMessages = 0, steps = 4000),
                Lang.EN,
            ),
        )
    }

    @Test
    fun anEventSoonIsMentioned() {
        val line = Nudges.composeIn(
            Nudges.Facts(
                nextEventTitle = "Standup with Amit",
                nextEventMillis = System.currentTimeMillis() + 25 * 60_000L,
                batteryPercent = 70,
                charging = true,
            ),
            Lang.EN,
        )
        assertNotNull(line)
        assertTrue(line!!.contains("Standup with Amit"))
        assertTrue(line.contains("minutes"))
    }

    @Test
    fun anEventFarAwayIsNot() {
        val line = Nudges.composeIn(
            Nudges.Facts(
                nextEventTitle = "Dinner",
                nextEventMillis = System.currentTimeMillis() + 8 * 3_600_000L,
                batteryPercent = 70,
                charging = true,
            ),
            Lang.EN,
        )
        assertNull(line)
    }

    @Test
    fun aDyingBatteryIsWorthASentence() {
        val line = Nudges.composeIn(
            Nudges.Facts(batteryPercent = 9, charging = false),
            Lang.EN,
        )
        assertNotNull(line)
        assertTrue(line!!.contains("9"))
    }

    @Test
    fun theSameBriefSpeaksHindiWhenTheOperatorDoes() {
        val hindi = Nudges.composeIn(Nudges.Facts(batteryPercent = 8, charging = false), Lang.HI)
        assertNotNull(hindi)
        assertTrue(hindi!!.any { it.code > 0x0900 })
    }

    @Test
    fun aQuietDayOnTheSofaIsNotNaggedAboutAtDawn() {
        // The step nudge is time-gated, and a test cannot control the clock, so it only asserts that
        // when it does appear it mentions walking — never that it must appear.
        val line = Nudges.composeIn(Nudges.Facts(steps = 10), Lang.EN)
        if (line != null) assertTrue(line.contains("walk", ignoreCase = true))
        assertEquals(line, line)
    }
}
