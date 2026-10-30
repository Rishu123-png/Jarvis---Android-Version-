package com.jarvis.assistant.voice

/**
 * The sentence that arrived with the wake word.
 *
 * "Hey Jarvis, open WhatsApp" is one breath, so the audio for it exists in exactly one place — the
 * wake service's microphone — and the app that plans the command only starts afterwards. This is the
 * one slot between them: the service leaves the captured audio here, opens the app, and the app takes
 * it. No Intent extra (the buffer is far too big to be safe in a binder transaction), no file.
 *
 * Four rules, because this holds a recording of the room:
 *
 *  * one slot only — a second capture replaces the first rather than queueing;
 *  * it expires, so a tap on the notification an hour later does not fire an old sentence;
 *  * [take] clears it, so the same words can never run twice;
 *  * everything is guarded, because a failed handoff must fall back to listening, not crash.
 */
object WakeHandoff {

    /** Long enough for the app to cold-start, short enough that the words are still about now. */
    private const val TTL_MS = 20_000L

    @Volatile
    private var audio: ByteArray? = null

    @Volatile
    private var capturedAt: Long = 0L

    /** Called by the wake service once the operator has finished the sentence. */
    fun deliver(pcm: ByteArray) {
        if (pcm.isEmpty()) {
            clear()
            return
        }
        audio = pcm
        capturedAt = System.currentTimeMillis()
    }

    /** The captured sentence, once. Null when there is none or it has gone stale. */
    fun take(): ByteArray? {
        val held = audio
        audio = null
        if (held == null) return null
        val age = System.currentTimeMillis() - capturedAt
        capturedAt = 0L
        if (!isFresh(age)) return null
        return held
    }

    fun peek(): Boolean = audio != null && isFresh(System.currentTimeMillis() - capturedAt)

    fun clear() {
        audio = null
        capturedAt = 0L
    }

    /** Exposed so the rule can be tested without waiting twenty seconds. */
    fun isFresh(ageMs: Long): Boolean = ageMs in 0..TTL_MS
}
