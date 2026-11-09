package com.jarvis.assistant

import com.jarvis.assistant.ai.ToolSchema
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.ConditionType
import com.jarvis.assistant.automation.FailPolicy
import com.jarvis.assistant.automation.TriggerKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool schema says exactly what the engine can do — no more, and no less.
 *
 * The whole reason the catalogue is generated from the enums is that a hand-written copy is a second
 * source of truth, and the day the two disagree a model is offered an action that does not exist.
 * These tests are that guarantee, and they read the wire shapes rather than the helper functions.
 */
class ToolSchemaTest {

    private fun openAiTool(screen: Boolean = false): JSONObject =
        ToolSchema.forOpenAi(screen).getJSONObject(0).getJSONObject("function")

    private fun parameters(screen: Boolean = false): JSONObject =
        openAiTool(screen).getJSONObject("parameters")

    private fun stringEnum(container: JSONObject, path: List<String>): Set<String> {
        var node = container
        for (key in path.dropLast(1)) node = node.getJSONObject(key)
        val array = node.getJSONArray(path.last())
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    // ---------------------------------------------------------------------------------------------
    // Coverage: every action, condition and trigger
    // ---------------------------------------------------------------------------------------------

    @Test
    fun everyActionIsInTheRoutineSchema() {
        val offered = stringEnum(parameters(), listOf("properties", "steps", "items", "properties", "action", "enum"))
        assertEquals(ActionType.entries.map { it.id }.toSet(), offered)
    }

    @Test
    fun everyTriggerIsInTheRoutineSchema() {
        val offered = stringEnum(parameters(), listOf("properties", "trigger", "properties", "kind", "enum"))
        assertEquals(TriggerKind.entries.map { it.id }.toSet(), offered)
    }

    @Test
    fun everyFailPolicyIsInTheRoutineSchema() {
        val offered = stringEnum(parameters(), listOf("properties", "steps", "items", "properties", "onFail", "enum"))
        assertEquals(FailPolicy.entries.map { it.id }.toSet(), offered)
    }

    @Test
    fun theUndoActionIsAlsoDrawnFromTheCatalogue() {
        val offered = stringEnum(
            parameters(),
            listOf("properties", "steps", "items", "properties", "undo", "properties", "action", "enum"),
        )
        assertEquals(ActionType.entries.map { it.id }.toSet(), offered)
    }

    @Test
    fun conditionsHaveAnIdListEvenThoughNoToolOffersOne() {
        // The routine tool does not take a condition type — the planner still reads conditions out of
        // the parsed object — but the list must stay complete for the prose prompt and the guard.
        assertEquals(ConditionType.entries.map { it.id }.toSet(), ToolSchema.conditionIds().toSet())
    }

    // ---------------------------------------------------------------------------------------------
    // Three wires, one schema
    // ---------------------------------------------------------------------------------------------

    @Test
    fun allThreeWiresCarryTheSameParameters() {
        val openAi = ToolSchema.forOpenAi(screen = false).getJSONObject(0).getJSONObject("function")
        val anthropic = ToolSchema.forAnthropic(screen = false).getJSONObject(0)
        val gemini = ToolSchema.forGemini(screen = false)
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")
            .getJSONObject(0)

        assertEquals(ToolSchema.ROUTINE, openAi.getString("name"))
        assertEquals(ToolSchema.ROUTINE, anthropic.getString("name"))
        assertEquals(ToolSchema.ROUTINE, gemini.getString("name"))

        assertEquals(openAi.getJSONObject("parameters").keySet(), anthropic.getJSONObject("input_schema").keySet())
        assertEquals(openAi.getJSONObject("parameters").keySet(), gemini.getJSONObject("parameters").keySet())
    }

    @Test
    fun theScreenToolIsASmallerThingThanTheRoutineTool() {
        val screen = ToolSchema.forOpenAi(screen = true).getJSONObject(0).getJSONObject("function")
        assertEquals(ToolSchema.SCREEN, screen.getString("name"))
        val properties = screen.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("action"))
        assertTrue(properties.has("param"))
        assertFalse("a screen action has no steps", properties.has("steps"))
    }

    @Test
    fun geminiNeverSeesAKeywordItDoesNotKnow() {
        val gemini = ToolSchema.forGemini(screen = false).toString()
        assertFalse(gemini.contains("additionalProperties"))
        assertFalse(gemini.contains("\$ref"))
        assertFalse(gemini.contains("minLength"))
    }

    @Test
    fun everyToolDeclaresTheFieldsItNeeds() {
        val routine = parameters().getJSONArray("required")
        val required = (0 until routine.length()).map { routine.getString(it) }.toSet()
        assertEquals(setOf("name", "trigger", "steps"), required)

        val step = parameters()
            .getJSONObject("properties")
            .getJSONObject("steps")
            .getJSONObject("items")
            .getJSONArray("required")
        assertEquals("action", step.getString(0))
    }

    @Test
    fun theSchemaRoundTripsAsJson() {
        // A schema that cannot be serialised is a request body that never arrives.
        val text = ToolSchema.forOpenAi(screen = false).toString()
        assertNotNull(JSONObject("{\"tools\":$text}"))
        assertTrue(JSONArray(text).length() == 1)
    }
}
