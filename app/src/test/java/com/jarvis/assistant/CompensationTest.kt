package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.Compensation
import com.jarvis.assistant.automation.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking a failed routine backwards.
 *
 * Two refusals matter more than the ordering: a step that cannot be taken back is never "undone", and
 * an undo that cannot itself be undone is never planned. Everything else is bookkeeping.
 */
class CompensationTest {

    private fun torchOn() = WorkflowStep(
        action = ActionType.TORCH_ON,
        param = "on",
        undoAction = ActionType.TORCH_OFF.id,
        undoParam = "off",
    )

    private fun volume(level: String, undo: String = "40") = WorkflowStep(
        action = ActionType.SET_VOLUME,
        param = level,
        undoAction = ActionType.SET_VOLUME.id,
        undoParam = undo,
    )

    @Test
    fun thePlanRunsInReverseOrder() {
        val steps = listOf(torchOn(), volume("10"))
        val plan = Compensation.planFor(steps, failedIndex = 2)
        assertEquals(2, plan.size)
        assertEquals(1, plan[0].index)
        assertEquals(0, plan[1].index)
    }

    @Test
    fun onlyStepsBeforeTheFailureAreUndone() {
        val steps = listOf(torchOn(), volume("10"), WorkflowStep(action = ActionType.CAPTURE_PHOTO))
        val plan = Compensation.planFor(steps, failedIndex = 1)
        assertEquals(1, plan.size)
        assertEquals(0, plan[0].index)
    }

    @Test
    fun nothingBeforeTheFailureMeansNoPlan() {
        assertTrue(Compensation.planFor(listOf(torchOn()), failedIndex = 0).isEmpty())
    }

    @Test
    fun aStepThatCannotBeTakenBackIsNeverUndone() {
        // A sent message declares "delete it" as its undo. That is not an undo, it is a second deed.
        val sent = WorkflowStep(
            action = ActionType.MESSAGE,
            param = "Divik: hello",
            undoAction = ActionType.SHELL.id,
            undoParam = "content delete",
        )
        assertNull(Compensation.canUndo(sent))
    }

    @Test
    fun anUndoThatIsItselfIrreversibleIsRefused() {
        val step = WorkflowStep(
            action = ActionType.SET_VOLUME,
            param = "0",
            undoAction = ActionType.SHELL.id,
            undoParam = "settings put system volume 40",
        )
        assertNull(Compensation.canUndo(step))
    }

    @Test
    fun aReversibleUndoNeedsAValue() {
        assertNull(Compensation.canUndo(volume("10", undo = "")))
        // A no-param action needs no value: torch off says everything.
        assertEquals(ActionType.TORCH_OFF, Compensation.canUndo(torchOn()))
    }

    @Test
    fun theUndoStepCarriesTheValueAndNeverHalts() {
        val undo = Compensation.planFor(listOf(torchOn()), failedIndex = 1).first()
        val step = Compensation.stepFor(undo)
        assertEquals(ActionType.TORCH_OFF, step.action)
        assertEquals("off", step.param)
        assertEquals(0, step.retries)
    }

    @Test
    fun theSummarySaysWhatActuallyHappened() {
        assertTrue(Compensation.summary(2, 2).contains("2"))
        assertTrue(Compensation.summary(0, 2).contains("none"))
        assertTrue(Compensation.summary(1, 2).contains("1 of 2"))
        assertEquals("", Compensation.summary(0, 0))
    }
}
