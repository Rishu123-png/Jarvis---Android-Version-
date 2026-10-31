package com.jarvis.assistant.wake

/**
 * What happens in the two seconds after "Hey Jarvis".
 *
 * The wake word used to end the story: the service released the microphone and opened the app, so the
 * words spoken *with* the phrase — "hey jarvis **open whatsapp**" — were never recorded. This is the
 * fix, kept free of Android so the timing rules can be tested without a phone.
 *
 * Two pieces:
 *
 *  * [Ring] keeps the last [PRE_ROLL_MS] of audio, so the tail of the phrase and the first word of
 *    the command survive the moment of detection.
 *  * [Tracker] decides when the operator has finished speaking, from loudness alone. No second
 *    recogniser, no model call: the same PCM that found the wake word says when the sentence ended.
 *
 * The thresholds are deliberately forgiving in one direction only. Cutting a command short loses it
 * completely, while waiting a moment too long costs a moment — so the silence gap is long, and only
 * a real voice starts the clock.
 */
object WakeCapture {

    /** Audio kept from before the detection, so the command's first syllable is not clipped. */
    const val PRE_ROLL_MS = 1_200

    /** The longest command that will be carried over. Beyond this the operator is talking to someone else. */
    const val MAX_COMMAND_MS = 8_000

    /** Quiet needed after speech to call the sentence finished. */
    const val SILENCE_MS = 1_000

    /** If nothing is said this long after the wake word, he just opens and listens, as before. */
    const val WAIT_FOR_SPEECH_MS = 1_500

    /** A cough or a door is not a command. */
    const val MIN_SPEECH_MS = 200

    /**
     * Loudness that counts as a voice at all. Quiet rooms sit near 30–150 on a phone microphone and
     * speech lands around 800–6,000, so this sits below speech and well above room tone.
     */
    const val SPEECH_RMS = 700.0

    /** Bytes in one millisecond of the 16 kHz, 16-bit, mono stream this app records. */
    const val BYTES_PER_MS = 32

    enum class Decision {
        /** Keep listening; the sentence is still going. */
        CAPTURING,

        /** Speech was heard and has stopped: hand the audio over. */
        FINISH,

        /** Nothing worth transcribing: fall back to opening the app and listening normally. */
        NOTHING,
    }

    /**
     * When to stop capturing.
     *
     * Fed one microphone read at a time. It only ever moves forward: once it says [Decision.FINISH]
     * or [Decision.NOTHING] the caller stops and the tracker is thrown away.
     */
    class Tracker(
        private val silenceMs: Int = SILENCE_MS,
        private val maxMs: Int = MAX_COMMAND_MS,
        private val waitForSpeechMs: Int = WAIT_FOR_SPEECH_MS,
        private val minSpeechMs: Int = MIN_SPEECH_MS,
        private val speechRms: Double = SPEECH_RMS,
    ) {
        private var elapsedMs = 0
        private var speechMs = 0
        private var quietMs = 0
        private var terminal: Decision? = null

        val hasSpeech: Boolean get() = speechMs >= minSpeechMs

        fun feed(rms: Double, chunkMs: Int): Decision {
            terminal?.let { return it }
            if (chunkMs <= 0) return Decision.CAPTURING
            elapsedMs += chunkMs

            if (rms >= speechRms) {
                speechMs += chunkMs
                quietMs = 0
            } else {
                quietMs += chunkMs
            }

            // A sentence that started and then stopped.
            if (speechMs >= minSpeechMs && quietMs >= silenceMs) {
                terminal = Decision.FINISH
                return Decision.FINISH
            }

            if (elapsedMs >= maxMs) {
                terminal = if (speechMs >= minSpeechMs) Decision.FINISH else Decision.NOTHING
                return terminal!!
            }

            // Nothing that counts as a command was said — only the wake word, or a cough and a
            // door. Stop holding the microphone once the configured initial-speech window closes.
            if (speechMs < minSpeechMs && elapsedMs >= waitForSpeechMs) {
                terminal = Decision.NOTHING
                return Decision.NOTHING
            }

            return Decision.CAPTURING
        }
    }

    /**
     * The last [PRE_ROLL_MS] of audio, in order, without allocating per read.
     *
     * A circular buffer rather than an array that shifts: at ten reads a second a copying buffer
     * would move ~380 KB every second for no reason.
     */
    class Ring(capacity: Int = PRE_ROLL_MS * BYTES_PER_MS) {

        private val data = ByteArray(capacity.coerceAtLeast(BYTES_PER_MS))
        private var head = 0
        private var size = 0

        val length: Int get() = size

        fun append(source: ByteArray, count: Int) {
            val limit = minOf(count, source.size)
            var index = 0
            while (index < limit) {
                data[(head) % data.size] = source[index]
                head = (head + 1) % data.size
                if (size < data.size) size++
                index++
            }
        }

        /** A copy of the buffer, oldest byte first. */
        fun snapshot(): ByteArray {
            val out = ByteArray(size)
            val start = (head - size + data.size * 2) % data.size
            for (index in 0 until size) {
                out[index] = data[(start + index) % data.size]
            }
            return out
        }

        fun clear() {
            head = 0
            size = 0
        }
    }
}
