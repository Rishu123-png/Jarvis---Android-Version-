package com.jarvis.assistant.automation

/**
 * How many times a step may be tried again, and the one case where the answer is zero.
 *
 * Retrying is the most ordinary failure handling there is, and this engine already had it — a step
 * carries `retries`, and [AutomationEngine.executeWithRetry] walks them with a growing pause. What it
 * did not have was a notion of when retrying is **wrong**.
 *
 * A retry is only safe when the step is idempotent or reversible. `torch on` twice is still on.
 * `volume 30` twice is still 30. But a `SHELL` command, a sent message, a placed call, a webhook, or
 * a download can each have happened *and* failed on the way back — the classic duplicate. Retrying
 * those because the response was slow is how a routine sends the same message twice. So they are
 * attempted exactly once, whatever the step says, and the run log says why.
 */
object RetryPolicy {

    /** The engine's ceiling, mirrored from the model's own clamp, so the two cannot disagree. */
    const val MAX_ATTEMPTS = 3

    /**
     * Attempts *after* the first, for this step.
     *
     * Zero for irreversible work. Otherwise the step's own asking, clamped.
     */
    fun retriesFor(step: WorkflowStep): Int {
        if (!retryable(step.action)) return 0
        return step.retries.coerceIn(0, MAX_ATTEMPTS - 1)
    }

    /** True when trying the same step twice cannot do the thing twice. */
    fun retryable(action: ActionType): Boolean = ActionRisk.of(action) != ActionRisk.IRREVERSIBLE

    /**
     * What to tell the operator when their request for retries was overruled.
     *
     * Null when nothing was overruled — an unused warning is a warning nobody reads.
     */
    fun overruledNote(step: WorkflowStep): String? {
        if (retryable(step.action) || step.retries <= 0) return null
        return "${step.action.label} is attempted once: it cannot be taken back, and a retry could " +
            "do it twice."
    }

    /** The pause before attempt `n` (1-based), slow enough to survive a stutter, short enough to feel alive. */
    fun backoffMs(attempt: Int): Long = 400L * attempt.coerceIn(1, 3)
}
