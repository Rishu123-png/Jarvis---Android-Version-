package com.jarvis.assistant.wake

/**
 * How long to wait after a voice stops before deciding the sentence is over.
 *
 * One fixed number can only be wrong in one of two directions. Too long and every command carries a
 * second of dead air between the last word and the answer — the largest avoidable cost in the whole
 * voice path. Too short and a mid-sentence breath ends the recording, and the operator has to start
 * again.
 *
 * The obvious way out is a proper neural endpoint model. The cheap and honest way is to use what the
 * audio already tells us: **how much speech came before the pause**. A two-word command is finished
 * when it stops; a fifteen-second description of a shopping list may pause in the middle and is not.
 * So the gap scales with the utterance, and the ceiling stays where it was.
 *
 * This is not a substitute for a real VAD. It is the half that costs nothing and can be tested today.
 */
object Endpointing {

    /** A short command: nothing more is coming, so stop holding the microphone. */
    const val SHORT_MS = 550

    /** An ordinary sentence. */
    const val NORMAL_MS = 700

    /** A long one, where a pause may only be a pause. Also the caller's ceiling. */
    const val LONG_MS = 1_000

    /** Below this, the "speech" is a cough or a door, and no command is coming. */
    const val MIN_SPEECH_MS = 200

    /**
     * Quiet needed after [speechMs] of voice.
     *
     * Monotonic on purpose: more speech can never produce a shorter gap, so a caller cannot end up
     * with a discontinuous rule that drops the end of a long sentence.
     */
    fun silenceFor(speechMs: Int): Int = when {
        speechMs < 700 -> SHORT_MS
        speechMs < 2_000 -> NORMAL_MS
        else -> LONG_MS
    }

    /** Whether what was heard is worth transcribing at all. */
    fun enoughSpeech(speechMs: Int, minimum: Int = MIN_SPEECH_MS): Boolean = speechMs >= minimum
}
