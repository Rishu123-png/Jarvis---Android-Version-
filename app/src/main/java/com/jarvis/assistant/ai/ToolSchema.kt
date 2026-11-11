package com.jarvis.assistant.ai

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.ConditionType
import com.jarvis.assistant.automation.FailPolicy
import com.jarvis.assistant.automation.TriggerKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * The action catalogue, said in the language the models actually enforce.
 *
 * Every planner in this app used to do the same thing: paste the catalogue into a prompt and ask for
 * "ONE JSON object and nothing else", then scrape an object back out with [JsonReply]. That works
 * most of the time, which is the problem — one failure in eight, compounded over a multi-step plan,
 * is most plans. Native tool calling puts the same catalogue in the request's own `tools` field,
 * where the model is trained to answer into a schema instead of into prose.
 *
 * The catalogue is **generated from the enums**, never written by hand. There is no second list to
 * forget: adding an `ActionType` adds it to the tool the same day. `ToolSchemaTest` fails if the two
 * ever disagree.
 *
 * Three wire shapes, one schema:
 *  * OpenAI and every OpenAI-compatible server (including the core on the operator's PC)
 *    — `[{type:function, function:{name, description, parameters}}]`
 *  * Anthropic — `[{name, description, input_schema}]`
 *  * Gemini — `[{functionDeclarations:[{name, description, parameters}]}]`
 *
 * The parameters are kept to the subset all three accept (type, description, enum, items, properties,
 * required). No `additionalProperties`, no `$ref`, no `minLength` — Gemini rejects what it does not
 * know, and a tool definition that fails to parse fails the whole request.
 */
object ToolSchema {

    /** The tool that turns a sentence into a routine. */
    const val ROUTINE = "save_routine"

    /** The tool that drives the screen, one action at a time. */
    const val SCREEN = "screen_action"

    const val ROUTINE_DESCRIPTION =
        "Save an automation routine. Call this once, with the whole routine. Only use the ids " +
            "listed in this schema; anything else is rejected."

    const val SCREEN_DESCRIPTION =
        "Performs exactly one action on the phone. Call this once per step, then look at the " +
            "screen again."

    /** Every action id, in enum order. The test asserts this matches [ActionType]. */
    fun actionIds(): List<String> = ActionType.entries.map { it.id }

    fun conditionIds(): List<String> = ConditionType.entries.map { it.id }

    fun triggerIds(): List<String> = TriggerKind.entries.map { it.id }

    fun failPolicyIds(): List<String> = FailPolicy.entries.map { it.id }

    // -----------------------------------------------------------------------------------------
    // Parameters
    // -----------------------------------------------------------------------------------------

    /**
     * The routine schema.
     *
     * `undo` is optional and deliberately small: one action, one param. A routine that can say what
     * it would take back is worth more than one that cannot, but an undo tree would be a programming
     * language, and this app is not one.
     */
    fun routineParameters(): JSONObject {
        val trigger = JSONObject()
            .put("type", "object")
            .put("description", "When the routine runs.")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "kind",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Trigger id. 'manual' runs it only when asked.")
                            .put("enum", JSONArray(triggerIds())),
                    )
                    .put(
                        "param",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Trigger value: HH:mm for time, SSID for wifi, app id, or empty."),
                    ),
            )
            .put("required", JSONArray().put("kind"))

        val step = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "action",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Action id.")
                            .put("enum", JSONArray(actionIds())),
                    )
                    .put("param", JSONObject().put("type", "string").put("description", "The action's value."))
                    .put(
                        "onFail",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "continue, or halt the rest of the routine.")
                            .put("enum", JSONArray(failPolicyIds())),
                    )
                    .put(
                        "undo",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "description",
                                "Optional. What would take this step back if a later step fails.",
                            )
                            .put(
                                "properties",
                                JSONObject()
                                    .put("action", JSONObject().put("type", "string").put("enum", JSONArray(actionIds())))
                                    .put("param", JSONObject().put("type", "string")),
                            ),
                    ),
            )
            .put("required", JSONArray().put("action"))

        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "name",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Short name, uppercase, at most 24 characters."),
                    )
                    .put("rationale", JSONObject().put("type", "string").put("description", "One sentence."))
                    .put("trigger", trigger)
                    .put("steps", JSONObject().put("type", "array").put("items", step).put("description", "In order.")),
            )
            .put("required", JSONArray().put("name").put("trigger").put("steps"))
    }

    /** The screen schema. Same shape as a step, minus the routine around it. */
    fun screenParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "action",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "One action.")
                        .put(
                            "enum",
                            JSONArray(
                                listOf(
                                    "press", "scroll", "swipe", "tap", "type",
                                    "global", "open_app", "wait", "done", "give_up",
                                ),
                            ),
                        ),
                )
                .put("param", JSONObject().put("type", "string").put("description", "The action's value."))
                .put("say", JSONObject().put("type", "string").put("description", "At most 40 characters.")),
        )
        .put("required", JSONArray().put("action"))

    // -----------------------------------------------------------------------------------------
    // The three wire shapes
    // -----------------------------------------------------------------------------------------

    /** OpenAI, and everything that copies it (Ollama, LM Studio, vLLM, OpenRouter). */
    fun forOpenAi(screen: Boolean): JSONArray = JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", if (screen) SCREEN else ROUTINE)
                    .put("description", if (screen) SCREEN_DESCRIPTION else ROUTINE_DESCRIPTION)
                    .put("parameters", if (screen) screenParameters() else routineParameters()),
            ),
    )

    fun forAnthropic(screen: Boolean): JSONArray = JSONArray().put(
        JSONObject()
            .put("name", if (screen) SCREEN else ROUTINE)
            .put("description", if (screen) SCREEN_DESCRIPTION else ROUTINE_DESCRIPTION)
            .put("input_schema", if (screen) screenParameters() else routineParameters()),
    )

    fun forGemini(screen: Boolean): JSONArray = JSONArray().put(
        JSONObject().put(
            "functionDeclarations",
            JSONArray().put(
                JSONObject()
                    .put("name", if (screen) SCREEN else ROUTINE)
                    .put("description", if (screen) SCREEN_DESCRIPTION else ROUTINE_DESCRIPTION)
                    .put(
                        "parameters",
                        stripGeminiUnsupported(
                            if (screen) screenParameters() else routineParameters(),
                        ),
                    ),
            ),
        ),
    )

    /**
     * Gemini's schema dialect has no `additionalProperties` and a shorter keyword list. Drop what it
     * does not accept rather than sending a declaration it may reject wholesale.
     */
    private fun stripGeminiUnsupported(schema: JSONObject): JSONObject {
        val copy = JSONObject()
        val names = schema.keys()
        while (names.hasNext()) {
            val key = names.next()
            if (key == "additionalProperties") continue
            val value = schema.opt(key)
            copy.put(
                key,
                when (value) {
                    is JSONObject -> stripGeminiUnsupported(value)
                    is JSONArray -> JSONArray().also { array ->
                        for (index in 0 until value.length()) {
                            val item = value.opt(index)
                            array.put(if (item is JSONObject) stripGeminiUnsupported(item) else item)
                        }
                    }
                    else -> value
                },
            )
        }
        return copy
    }
}
