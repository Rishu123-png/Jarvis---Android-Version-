package com.jarvis.assistant.automation

/**
 * Walking a failed routine backwards.
 *
 * A routine that turns the torch on, drops the volume and then fails to open the camera has left the
 * phone in a state nobody asked for. [ActionRisk] already knows which of those steps can be taken
 * back, and `WorkflowStep.undoAction` is where a step says how. This decides whether to bother.
 *
 * Two rules keep it honest:
 *
 *  * **Only earlier steps.** The step that failed did not complete, so undoing it would be acting on
 *    something that never happened. Compensation runs from the step before the failure, backwards.
 *  * **Only a reversible undo.** A step whose own action is irreversible is skipped — it cannot be
 *    taken back, and pretending otherwise is the one lie this engine will not tell. An undo that is
 *    itself irreversible is skipped too: "undo" that cannot be undone is worse than nothing.
 *
 * Pure Kotlin, so `CompensationTest` covers the ordering and the refusals without a device.
 */
object Compensation {

    /** One planned reversal, in the order it runs. */
    data class Undo(
        val index: Int,
        val step: WorkflowStep,
        val action: ActionType,
    ) {
        val label: String get() = "UNDO ${action.label}"
    }

    /**
     * The reversals for a routine that stopped at [failedIndex], in execution order.
     *
     * Empty when nothing before the failure declared an undo — which is most routines, and is fine.
     */
    fun planFor(steps: List<WorkflowStep>, failedIndex: Int): List<Undo> {
        if (failedIndex <= 0) return emptyList()
        val out = ArrayList<Undo>()
        for (index in failedIndex - 1 downTo 0) {
            val step = steps.getOrNull(index) ?: continue
            val undo = canUndo(step) ?: continue
            out.add(Undo(index, step, undo))
        }
        return out
    }

    /** The reversal action for a step, or null when it has none that is safe to run. */
    fun canUndo(step: WorkflowStep): ActionType? {
        val declared = ActionType.fromId(step.undoAction) ?: return null
        // Nothing is taken back for work that cannot be taken back.
        if (ActionRisk.of(step.action) == ActionRisk.IRREVERSIBLE) return null
        // And an undo must itself be something that can be undone.
        if (ActionRisk.of(declared) != ActionRisk.REVERSIBLE) return null
        // An undo with no value is a step the operator did not really describe.
        if (declared.paramKind != ParamKind.NONE && step.undoParam.isBlank()) return null
        return declared
    }

    /** True when any step before the failure could be taken back. Used to decide whether to offer it. */
    fun possible(steps: List<WorkflowStep>, failedIndex: Int): Boolean =
        planFor(steps, failedIndex).isNotEmpty()

    /** The step that performs one reversal, ready for [StepExecutor]. */
    fun stepFor(undo: Undo): WorkflowStep = WorkflowStep(
        action = undo.action,
        param = undo.step.undoParam,
        onFail = FailPolicy.CONTINUE,
    )

    /** The line the run log carries afterwards. States what happened, never what might have. */
    fun summary(ran: Int, planned: Int): String = when {
        planned == 0 -> ""
        ran == 0 -> "$planned step(s) could be taken back, but none of them could run."
        ran < planned -> "$ran of $planned step(s) taken back; the rest refused."
        else -> "$ran step(s) taken back."
    }
}
