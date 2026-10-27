package com.jarvis.assistant

import com.jarvis.assistant.device.Sleep
import com.jarvis.assistant.device.StepMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind steps and the sleep estimate, without a phone. */
class HealthMathTest {

    @Test
    fun stepsAreCountedFromTheStoredBaseline() {
        assertEquals(1200, StepMath.stepsFor(total = 5200, baseline = 4000, sameDay = true))
    }

    @Test
    fun aNewDayStartsAtZero() {
        assertEquals(0, StepMath.stepsFor(total = 5200, baseline = 4000, sameDay = false))
    }

    @Test
    fun aSensorResetNeverGoesNegative() {
        assertEquals(0, StepMath.stepsFor(total = 300, baseline = 5000, sameDay = true))
        assertEquals(0, StepMath.stepsFor(total = 5200, baseline = -1, sameDay = true))
    }

    @Test
    fun distanceUsesAStrideAndClampsNonsense() {
        assertEquals(0.72, StepMath.distanceKm(1000, 0.72), 0.0001)
        // A stride outside the human range falls back to the default instead of producing rubbish.
        assertEquals(StepMath.distanceKm(1000, 0.72), StepMath.distanceKm(1000, 7.0), 0.0001)
    }

    @Test
    fun onlyNightGapsCountAsSleep() {
        val night = 1_700_000_000_000L
        assertTrue(Sleep.isOvernight(night, night + 7 * 60 * 60 * 1000L))
        assertEquals(420, Sleep.estimateMinutes(night, night + 7 * 60 * 60 * 1000L))
    }

    @Test
    fun aForgottenPhoneIsNotFourteenHoursOfSleep() {
        val night = 1_700_000_000_000L
        assertEquals(14 * 60, Sleep.estimateMinutes(night, night + 40 * 60 * 60 * 1000L))
        assertEquals(0, Sleep.estimateMinutes(night, night - 1000L))
    }
}
