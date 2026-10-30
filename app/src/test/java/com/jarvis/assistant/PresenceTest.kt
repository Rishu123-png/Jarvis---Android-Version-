package com.jarvis.assistant

import com.jarvis.assistant.voice.Presence
import com.jarvis.assistant.voice.VoicePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orb's input.
 *
 * If any of this is wrong the picture lies about the room — an orb that jumps on silence, or sits
 * still while somebody is talking. So the loudness mapping and the smoothing are pinned down here,
 * away from the microphone.
 */
class PresenceTest {

    /** 16-bit little-endian mono, every sample the same amplitude. */
    private fun pcm(amplitude: Short, samples: Int): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (index in 0 until samples) {
            bytes[index * 2] = (amplitude.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = ((amplitude.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun rmsOfAConstantSignalIsItsAmplitude() {
        val rms = Presence.rmsOf(pcm(1_000, 400), 800)
        assertEquals(1_000.0, rms, 2.0)
    }

    @Test
    fun silenceAndShortBuffersAreZeroNotNaN() {
        assertEquals(0.0, Presence.rmsOf(ByteArray(0), 0), 0.0)
        assertEquals(0.0, Presence.rmsOf(ByteArray(1), 1), 0.0)
        assertEquals(0.0, Presence.rmsOf(pcm(0, 100), 200), 0.0)
        // A length longer than the buffer must be clamped, not read past the end.
        assertTrue(Presence.rmsOf(pcm(500, 50), 10_000) >= 0.0)
    }

    @Test
    fun negativeSamplesCountAsLoudness() {
        val quiet = Presence.rmsOf(pcm(-1_500, 200), 400)
        assertEquals(1_500.0, quiet, 3.0)
    }

    @Test
    fun loudnessMapsIntoTheUnitRange() {
        assertEquals(0f, Presence.normalise(0.0), 0.001f)
        assertTrue(Presence.normalise(60.0) in 0f..0.2f)
        assertTrue(Presence.normalise(1_200.0) > Presence.normalise(300.0))
        assertEquals(1f, Presence.normalise(50_000.0), 0.001f)
        assertEquals(0f, Presence.normalise(Double.NaN), 0.001f)
    }

    @Test
    fun theLevelRisesFastAndFallsSlow() {
        // A voice should show up at once; a pause should fade rather than flicker.
        val rise = Presence.smooth(0f, 1f)
        val fall = Presence.smooth(1f, 0f)
        assertTrue(rise > fall)
        assertTrue(rise in 0.5f..0.6f)
        assertTrue(fall in 0.8f..0.85f)
        assertEquals(0.5f, Presence.smooth(0.5f, 0.5f), 0.0001f)
    }

    @Test
    fun theSmoothedValueNeverLeavesTheRange() {
        var level = 0f
        repeat(50) { level = Presence.smooth(level, 1f) }
        assertTrue(level <= 1f)
        repeat(50) { level = Presence.smooth(level, 0f) }
        assertTrue(level >= 0f)
    }

    @Test
    fun everyVoicePhaseHasAPresencePhase() {
        assertEquals(Presence.Phase.IDLE, Presence.phaseFor(VoicePhase.IDLE))
        assertEquals(Presence.Phase.LISTENING, Presence.phaseFor(VoicePhase.LISTENING))
        assertEquals(Presence.Phase.THINKING, Presence.phaseFor(VoicePhase.THINKING))
        assertEquals(Presence.Phase.WORKING, Presence.phaseFor(VoicePhase.RUNNING))
        assertEquals(Presence.Phase.DONE, Presence.phaseFor(VoicePhase.DONE))
        assertEquals(Presence.Phase.FAULT, Presence.phaseFor(VoicePhase.FAILED))
    }
}
