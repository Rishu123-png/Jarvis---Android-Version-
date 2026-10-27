package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.Condition
import com.jarvis.assistant.automation.ConditionType
import com.jarvis.assistant.automation.ConditionEvaluator
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stored routines, round-tripped.
 *
 * A routine is written by the AI, edited by hand, then read back after an app update. If the JSON
 * shape drifts, the operator's routines vanish — silently, because `fromJson` returns null and the
 * store skips it.
 */
class WorkflowJsonTest {

    @Test
    fun workflowSurvivesARoundTrip() {
        val original = Workflow(
            id = "w1",
            name = "Morning",
            steps = listOf(
                WorkflowStep(action = ActionType.OPEN_APP, param = "com.example.app/Main"),
                WorkflowStep(action = ActionType.WAIT, param = "2"),
            ),
        )

        val restored = Workflow.fromJson(original.toJson())

        assertNotNull(restored)
        assertEquals("w1", restored?.id)
        assertEquals("Morning", restored?.name)
        assertEquals(2, restored?.steps?.size)
        assertEquals(ActionType.OPEN_APP, restored?.steps?.first()?.action)
        assertEquals("2", restored?.steps?.last()?.param)
    }

    @Test
    fun nestedBlocksKeepTheirChildren() {
        val routine = Workflow(
            id = "w2",
            name = "Nested",
            steps = listOf(
                WorkflowStep(
                    action = ActionType.REPEAT_BLOCK,
                    param = "3",
                    children = listOf(WorkflowStep(action = ActionType.TORCH_ON)),
                ),
            ),
        )

        val restored = Workflow.fromJson(routine.toJson())

        assertEquals(1, restored?.steps?.size)
        assertEquals(1, restored?.steps?.first()?.children?.size)
        // flatSteps is what the budget check and the preflight use.
        assertEquals(2, restored?.flatSteps?.size)
    }

    @Test
    fun conditionsRoundTrip() {
        val condition = Condition(ConditionType.BATTERY_BELOW, "20")
        val restored = Condition.fromJson(condition.toJson())
        assertEquals(ConditionType.BATTERY_BELOW, restored?.type)
        assertEquals("20", restored?.param)
    }

    @Test
    fun unknownConditionTypeIsRejectedRatherThanGuessed() {
        assertNull(Condition.fromJson(null))
        assertNull(Condition.fromJson(org.json.JSONObject().put("type", "not_a_condition")))
    }

    @Test
    fun workflowWithoutIdIsRejected() {
        assertNull(Workflow.fromJson(org.json.JSONObject().put("name", "No id")))
    }

    // ---------------------------------------------------------------------------------------------
    // Time parsing used by triggers and reminders
    // ---------------------------------------------------------------------------------------------

    @Test
    fun twentyFourHourTimesParse() {
        assertNotNull(ConditionEvaluator.parseTime("07:30"))
        assertNotNull(ConditionEvaluator.parseTime(" 23:59 "))
        assertNull(ConditionEvaluator.parseTime("7:30"))
        assertNull(ConditionEvaluator.parseTime("25:00"))
        assertNull(ConditionEvaluator.parseTime("soon"))
    }

    @Test
    fun windowsAcceptHyphenAndEnDash() {
        val window = ConditionEvaluator.parseWindow("07:00-22:00")
        assertEquals(7, window?.first?.hour ?: -1)
        assertEquals(22, window?.second?.hour ?: -1)
        assertNotNull(ConditionEvaluator.parseWindow("07:00 \u2013 22:00"))
        assertNull(ConditionEvaluator.parseWindow("07:00"))
        assertNull(ConditionEvaluator.parseWindow("nonsense"))
    }

    @Test
    fun actionIdsAreUnique() {
        val ids = ActionType.entries.map { it.id }
        assertTrue("duplicate action ids: ${ids.groupBy { it }.filterValues { it.size > 1 }}", ids.size == ids.toSet().size)
    }
}
