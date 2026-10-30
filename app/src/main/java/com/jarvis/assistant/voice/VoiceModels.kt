package com.jarvis.assistant.voice

/**
 * The state of the voice surface.
 *
 * The HUD renders exactly one of these. Everything the microphone and the command pipeline do is
 * funnelled through here, so the UI never has to guess what is happening and no screen can get
 * stuck showing "listening" after the microphone stopped.
 */
enum class VoicePhase {
    IDLE,
    LISTENING,
    THINKING,
    RUNNING,
    DONE,
    FAILED,
    ;

    val isBusy: Boolean
        get() = this == LISTENING || this == THINKING || this == RUNNING

    val isFinished: Boolean
        get() = this == DONE || this == FAILED
}

/** One row inside the task card. */
enum class StepPhase { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

data class VoiceStep(
    val label: String,
    val phase: StepPhase = StepPhase.PENDING,
    val detail: String = "",
)

/**
 * Everything the card needs, in one immutable value.
 *
 * [taskId] increments on every new command so a late coroutine from a cancelled command can never
 * overwrite what the operator is looking at now.
 */
data class VoiceTask(
    val taskId: Int = 0,
    val phase: VoicePhase = VoicePhase.IDLE,
    val heard: String = "",
    val title: String = "",
    val subtitle: String = "",
    val steps: List<VoiceStep> = emptyList(),
    val reply: String = "",
    val source: String = "",
    val error: String? = null,
    /** True while the ear is being held open for a follow-up sentence. */
    val conversation: Boolean = false,
    /** Which follow-up this is, 1-based; 0 when not in a conversation. */
    val turn: Int = 0,
) {
    val visible: Boolean get() = phase != VoicePhase.IDLE

    /** Human-readable phase chip. Short on purpose — it sits in a corner of the card. */
    val chip: String
        get() = when (phase) {
            VoicePhase.IDLE -> ""
            VoicePhase.LISTENING -> "LISTENING"
            VoicePhase.THINKING -> "THINKING"
            VoicePhase.RUNNING -> "RUNNING"
            VoicePhase.DONE -> "DONE"
            VoicePhase.FAILED -> "FAILED"
        }

    /** Small label for the card while a conversation is open. */
    val conversationLabel: String?
        get() = if (conversation && turn > 0) "CONVERSATION · $turn" else null

    /** 0..1 for the thin progress line under the steps. */
    val progress: Float
        get() = when {
            steps.isEmpty() -> if (phase == VoicePhase.DONE) 1f else 0f
            phase == VoicePhase.DONE -> 1f
            else -> steps.count { it.phase == StepPhase.DONE || it.phase == StepPhase.SKIPPED } /
                steps.size.toFloat()
        }
}
