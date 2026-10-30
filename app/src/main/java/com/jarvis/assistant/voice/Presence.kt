package com.jarvis.assistant.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * How alive he looks.
 *
 * The reactor has always spun on a fixed loop, which reads as a screensaver. This is the missing
 * input: while the microphone is open, the recorder publishes the actual loudness of the room, and
 * the orb answers it. Nothing here invents a signal — the level is computed from the same PCM buffer
 * that becomes the transcript, so a quiet room is a still orb and a voice lifts it.
 *
 * Two rules kept deliberately:
 *
 *  * **No timer.** The level is published per audio read, which already happens while listening, and
 *    it decays through the smoothing below. Nothing wakes up to animate a picture.
 *  * **Never on the critical path.** [publishRms] is a few hundred arithmetic operations on a
 *    thread that is already reading the microphone, and the recorder wraps the call so a failure
 *    here can never end a recording.
 *
 * The mapping and the smoothing are pure, so `PresenceTest` pins them down without a phone.
 */
object Presence {

    /** What he is doing — the same phases the HUD already shows, without inventing new ones. */
    enum class Phase { IDLE, LISTENING, THINKING, WORKING, DONE, FAULT }

    private val _level = MutableStateFlow(0f)

    /** 0..1, smoothed. Read by the reactor; never written by the UI. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Called when the microphone closes, so the orb drops instead of holding its last peak. */
    fun reset() {
        _level.value = 0f
    }

    /** One audio read's loudness. Safe to call from the recorder thread at any rate. */
    fun publishRms(rms: Double) {
        _level.value = smooth(_level.value, normalise(rms))
    }

    /** The orb's phase follows the voice phase the app already reports. */
    fun phaseFor(phase: VoicePhase): Phase = when (phase) {
        VoicePhase.IDLE -> Phase.IDLE
        VoicePhase.LISTENING -> Phase.LISTENING
        VoicePhase.THINKING -> Phase.THINKING
        VoicePhase.RUNNING -> Phase.WORKING
        VoicePhase.DONE -> Phase.DONE
        VoicePhase.FAILED -> Phase.FAULT
    }

    /**
     * Root-mean-square of 16-bit little-endian mono PCM, over at most [length] bytes.
     *
     * Every fourth sample is enough: loudness does not change between adjacent samples at 16 kHz,
     * and reading all of them would be work for no extra fidelity.
     */
    fun rmsOf(pcm: ByteArray, length: Int): Double {
        val end = minOf(length, pcm.size) - 1
        if (end < 1) return 0.0

        var sum = 0.0
        var count = 0
        var index = 0
        while (index < end) {
            val sample = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort()
            val value = sample.toDouble()
            sum += value * value
            count++
            index += 8
        }
        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    /**
     * Quiet rooms sit near 30–150; speech lands around 800–6,000 on a phone microphone. A square root
     * gives small sounds some movement without pinning loud ones at the top.
     */
    fun normalise(rms: Double): Float {
        if (rms <= 0.0 || !rms.isFinite()) return 0f
        val scaled = (rms / LOUD_RMS).coerceIn(0.0, 1.0)
        return sqrt(scaled).toFloat().coerceIn(0f, 1f)
    }

    /** Rises quickly so the orb answers a voice, falls slowly so it does not flicker. */
    fun smooth(previous: Float, next: Float): Float {
        val blend = if (next > previous) 0.55f else 0.18f
        return (previous + (next - previous) * blend).coerceIn(0f, 1f)
    }

    /** Loudness that counts as "full". Well above speech, so the orb is not pinned while talking. */
    private const val LOUD_RMS = 12_000.0
}
