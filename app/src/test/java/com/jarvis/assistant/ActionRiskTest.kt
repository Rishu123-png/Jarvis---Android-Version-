package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionRisk
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How dangerous each action is, and what that is allowed to decide.
 *
 * The ladder exists for two questions only: may a plan move between the core and the key without
 * saying so, and what does a routine's worst step make it. Both are the kind of rule that gets
 * quietly loosened later, so both are pinned here.
 */
class ActionRiskTest {

    @Test
    fun `sending in the operator's name cannot be taken back`() {
        assertEquals(ActionRisk.IRREVERSIBLE, ActionRisk.of(ActionType.MESSAGE))
        assertEquals(ActionRisk.IRREVERSIBLE, ActionRisk.of(ActionType.MESSAGE_REPLY))
        assertEquals(ActionRisk.IRREVERSIBLE, ActionRisk.of(ActionType.CALL_CONTACT))
        assertEquals(ActionRisk.IRREVERSIBLE, ActionRisk.of(ActionType.SHELL))
        assertEquals(ActionRisk.IRREVERSIBLE, ActionRisk.of(ActionType.WEBHOOK))
    }

    @Test
    fun `looking at something changes nothing`() {
        assertEquals(ActionRisk.READ_ONLY, ActionRisk.of(ActionType.SAY))
        assertEquals(ActionRisk.READ_ONLY, ActionRisk.of(ActionType.CALCULATE))
        assertEquals(ActionRisk.READ_ONLY, ActionRisk.of(ActionType.LOCATION))
        // Accessibility alone must not promote a read: reading the screen is still reading.
        assertEquals(ActionRisk.READ_ONLY, ActionRisk.of(ActionType.READ_SCREEN))
    }

    @Test
    fun `touching the screen is never called harmless`() {
        assertEquals(ActionRisk.SENSITIVE, ActionRisk.of(ActionType.PRESS_TEXT))
        assertEquals(ActionRisk.SENSITIVE, ActionRisk.of(ActionType.TYPE_TEXT))
        assertEquals(ActionRisk.SENSITIVE, ActionRisk.of(ActionType.SCREENSHOT))
    }

    @Test
    fun `a switch is reversible`() {
        assertEquals(ActionRisk.REVERSIBLE, ActionRisk.of(ActionType.TORCH_ON))
        assertEquals(ActionRisk.REVERSIBLE, ActionRisk.of(ActionType.WIFI))
    }

    @Test
    fun `only harmless plans may be moved between brains silently`() {
        assertTrue(ActionRisk.allowsSilentFallback(ActionRisk.READ_ONLY))
        assertTrue(ActionRisk.allowsSilentFallback(ActionRisk.REVERSIBLE))
        assertFalse(ActionRisk.allowsSilentFallback(ActionRisk.SENSITIVE))
        assertFalse(ActionRisk.allowsSilentFallback(ActionRisk.IRREVERSIBLE))
    }

    @Test
    fun `the worst step decides the plan`() {
        val risk = ActionRisk.highest(
            listOf(ActionType.SAY, ActionType.TORCH_ON, ActionType.MESSAGE),
        )
        assertEquals(ActionRisk.IRREVERSIBLE, risk)
    }

    @Test
    fun `an empty plan is read-only`() {
        assertEquals(ActionRisk.READ_ONLY, ActionRisk.highest(emptyList()))
    }

    @Test
    fun `a routine takes the risk of its worst nested step`() {
        val routine = Workflow(
            id = "r1",
            name = "Nested",
            steps = listOf(
                WorkflowStep(
                    action = ActionType.IF_BLOCK,
                    condition = com.jarvis.assistant.automation.Condition(
                        com.jarvis.assistant.automation.ConditionType.CHARGING,
                    ),
                    children = listOf(
                        WorkflowStep(action = ActionType.MESSAGE, param = "whatsapp|Rishu|hi"),
                    ),
                ),
                WorkflowStep(action = ActionType.SAY, param = "done"),
            ),
        )
        assertEquals(ActionRisk.IRREVERSIBLE, routine.risk)
    }

    @Test
    fun `every confirmation level says something true about itself`() {
        assertEquals("This only looks at things.", ActionRisk.consequence(ActionRisk.READ_ONLY))
        assertEquals("This can be undone.", ActionRisk.consequence(ActionRisk.REVERSIBLE))
        assertEquals("This acts in your name.", ActionRisk.consequence(ActionRisk.SENSITIVE))
        assertEquals("This cannot be undone.", ActionRisk.consequence(ActionRisk.IRREVERSIBLE))
    }

    @Test
    fun `only the top two levels are worth interrupting for`() {
        assertFalse(ActionRisk.READ_ONLY.needsConfirmation)
        assertFalse(ActionRisk.REVERSIBLE.needsConfirmation)
        assertTrue(ActionRisk.SENSITIVE.needsConfirmation)
        assertTrue(ActionRisk.IRREVERSIBLE.needsConfirmation)
    }
}
