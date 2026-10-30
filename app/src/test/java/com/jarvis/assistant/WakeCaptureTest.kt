package com.jarvis.assistant

import com.jarvis.assistant.wake.WakeCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seconds right after "Hey Jarvis".
 *
 * This is the part that decides whether "hey jarvis open whatsapp" works or whether the operator has
 * to say it twice. It is all arithmetic and timers, so it can be pinned down exactly here — no
 * microphone, no waiting.
 */
class WakeCaptureTest {

    private fun loud() = 3_000.0
    private fun quiet() = 60.0

    // ---------------------------------------------------------------------------------------------
    // The ring buffer: the audio from before the detection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theRingKeepsWhatWasSaidJustBeforeTheHit() {
        val ring = WakeCapture.Ring(capacity = 8)
        ring.append(byteArrayOf(1, 2, 3, 4), 4)
        ring.append(byteArrayOf(5, 6, 7, 8), 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ring.snapshot())
    }

    @Test
    fun theRingDropsTheOldestWhenItOverflows() {
        val ring = WakeCapture.Ring(capacity = 8)
        ring.append(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
        ring.append(byteArrayOf(7, 8, 9, 10), 4)
        // Newest eight bytes survive, in order.
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10), ring.snapshot())
        assertEquals(8, ring.length)
    }

    @Test
    fun theRingHandlesWrapsAndOverlongReads() {
        val ring = WakeCapture.Ring(capacity = 4)
        ring.append(byteArrayOf(1, 2), 2)
        ring.append(byteArrayOf(3, 4), 2)
        ring.append(byteArrayOf(5, 6), 2)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), ring.snapshot())

        // A count larger than the array must be clamped, not read past the end.
        ring.clear()
        val short = byteArrayOf(9, 9)
        ring.append(short, 50)
        assertArrayEquals(byteArrayOf(9, 9), ring.snapshot())
    }

    @Test
    fun anEmptyRingIsEmptyNotBroken() {
        val ring = WakeCapture.Ring(capacity = 4)
        assertArrayEquals(ByteArray(0), ring.snapshot())
        assertEquals(0, ring.length)
    }

    @Test
    fun thePreRollHoldsExactlyTheConfiguredWindow() {
        // 1.2 s at 16 kHz, 16-bit mono: the default ring must hold all of it and not a byte more.
        val capacity = WakeCapture.PRE_ROLL_MS * WakeCapture.BYTES_PER_MS
        val ring = WakeCapture.Ring()
        // Write twice the capacity; only the newest window survives.
        val chunk = ByteArray(1_000) { it.toByte() }
        repeat(capacity * 2 / chunk.size) { ring.append(chunk, chunk.size) }
        assertEquals(capacity, ring.length)
        assertTrue("a window under a second cannot cover the phrase", WakeCapture.PRE_ROLL_MS >= 800)
    }

    // ---------------------------------------------------------------------------------------------
    // The tracker: when the sentence is over
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aSentenceInProgressIsNotCutOff() {
        val tracker = WakeCapture.Tracker()
        // Half a second of speech with no pause: keep going.
        repeat(5) { assertEquals(WakeCapture.Decision.CAPTURING, tracker.feed(loud(), 100)) }
        assertTrue(tracker.hasSpeech)
    }

    @Test
    fun aPauseAfterSpeechFinishesTheSentence() {
        val tracker = WakeCapture.Tracker()
        repeat(4) { tracker.feed(loud(), 100) }
        // Short gaps inside a sentence are not a stop.
        assertEquals(WakeCapture.Decision.CAPTURING, tracker.feed(quiet(), 100))
        assertEquals(WakeCapture.Decision.CAPTURING, tracker.feed(quiet(), 500))
        // A full second of quiet is.
        assertEquals(WakeCapture.Decision.FINISH, tracker.feed(quiet(), 500))
    }

    @Test
    fun aCoughIsNotASentence() {
        val tracker = WakeCapture.Tracker()
        // 100 ms of noise is below the minimum for speech, so it never counts as a command.
        assertEquals(WakeCapture.Decision.CAPTURING, tracker.feed(loud(), 100))
        assertFalse(tracker.hasSpeech)
        // And it gives up at the wait window instead of holding the microphone for eight seconds.
        assertEquals(WakeCapture.Decision.NOTHING, tracker.feed(quiet(), 1_500))
    }

    @Test
    fun onlyTheWakeWordIsSaidSoItFallsBackToListening() {
        val tracker = WakeCapture.Tracker()
        var elapsed = 0
        var decision = WakeCapture.Decision.CAPTURING
        // Silence only: it should give up exactly when the wait window closes, not later.
        while (decision == WakeCapture.Decision.CAPTURING && elapsed < 10_000) {
            decision = tracker.feed(quiet(), 100)
            elapsed += 100
        }
        assertEquals(WakeCapture.Decision.NOTHING, decision)
        assertTrue("gave up after ${elapsed}ms", elapsed >= WakeCapture.WAIT_FOR_SPEECH_MS)
        assertTrue("held the microphone too long: ${elapsed}ms", elapsed <= WakeCapture.WAIT_FOR_SPEECH_MS + 200)
    }

    @Test
    fun aLongMonologueIsCutAtTheCeiling() {
        val tracker = WakeCapture.Tracker()
        var decision = WakeCapture.Decision.CAPTURING
        repeat(120) {
            if (decision == WakeCapture.Decision.CAPTURING) decision = tracker.feed(loud(), 100)
        }
        assertEquals(WakeCapture.Decision.FINISH, decision)
    }

    @Test
    fun aZeroLengthChunkChangesNothing() {
        val tracker = WakeCapture.Tracker()
        assertEquals(WakeCapture.Decision.CAPTURING, tracker.feed(loud(), 0))
        assertEquals(WakeCapture.Decision.CAPTURING, tracker.feed(loud(), -50))
    }

    @Test
    fun theThresholdsAreForgivingRatherThanEager() {
        // Cutting a command short loses it; waiting a moment too long costs a moment.
        assertTrue(WakeCapture.SILENCE_MS >= 700)
        assertTrue(WakeCapture.MAX_COMMAND_MS >= 5_000)
        assertTrue(WakeCapture.WAIT_FOR_SPEECH_MS >= 1_000)
        // Room tone must not count as speech.
        assertTrue(WakeCapture.SPEECH_RMS > 150.0)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("lengths differ", expected.size, actual.size)
        expected.indices.forEach { index ->
            assertEquals("byte $index", expected[index], actual[index])
        }
    }
}
