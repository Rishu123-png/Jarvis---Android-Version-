package com.jarvis.assistant

import com.jarvis.assistant.wake.Endpointing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a silence is a full stop.
 *
 * One fixed number is wrong in one direction or the other: too long and every command carries a second
 * of dead air, too short and a breath ends the sentence. The gap scales with what was said, and never
 * exceeds the old ceiling.
 */
class EndpointingTest {

    @Test
    fun aShortCommandIsFinishedQuickly() {
        assertEquals(Endpointing.SHORT_MS, Endpointing.silenceFor(300))
    }

    @Test
    fun AnOrdinarySentenceGetsALittleLonger() {
        assertEquals(Endpointing.NORMAL_MS, Endpointing.silenceFor(1_200))
    }

    @Test
    fun aLongDescriptionMayOnlyBePausing() {
        assertEquals(Endpointing.LONG_MS, Endpointing.silenceFor(4_000))
    }

    @Test
    fun theGapNeverShrinksAsMoreIsSaid() {
        var last = 0
        for (ms in 0..5_000 step 50) {
            val gap = Endpointing.silenceFor(ms)
            assertTrue("gap shrank at ${ms}ms", gap >= last)
            last = gap
        }
    }

    @Test
    fun theCeilingIsTheOldFixedValue() {
        assertEquals(1_000, Endpointing.LONG_MS)
    }

    @Test
    fun aCoughIsNotACommand() {
        assertFalse(Endpointing.enoughSpeech(120))
        assertTrue(Endpointing.enoughSpeech(400))
    }
}
