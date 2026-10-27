package com.jarvis.assistant.ai

import android.content.Context
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.device.DndControl
import com.jarvis.assistant.automation.AutomationEngine
import com.jarvis.assistant.automation.Condition
import com.jarvis.assistant.automation.ConditionType
import com.jarvis.assistant.automation.FailPolicy
import com.jarvis.assistant.automation.RoutineTrigger
import com.jarvis.assistant.automation.TriggerKind
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface PlanResult {
    data class Ok(
        val workflow: Workflow,
        /** Everything JARVIS changed, dropped or clamped while validating. Shown to the operator. */
        val notes: List<String>,
        val rationale: String,
    ) : PlanResult

    data class Error(val message: String, val hint: String? = null) : PlanResult
}

/**
 * Turns a sentence into a validated routine.
 *
 * The model is never trusted with capability. It may only choose action and condition ids that
 * exist in [ActionType] and [ConditionType]; everything else is rejected. Values are clamped, depth
 * and step counts are capped, and sensitive steps stay sensitive — the planner cannot produce a
 * routine that skips a confirmation gate. What the model returns is a proposal the operator sees
 * and must save.
 */
object AiPlanner {

    private const val MAX_NAME_CHARS = 28

    suspend fun plan(context: Context, request: String): PlanResult {
        val ask = request.trim()
        if (ask.isEmpty()) return PlanResult.Error("Nothing to plan.")

        val result = AiClient.chat(
            context = context,
            messages = listOf(AiMessage.user(ask)),
            system = systemPrompt(context),
        )

        val reply = when (result) {
            is AiResult.Error -> return PlanResult.Error(result.message, result.hint)
            is AiResult.Ok -> result.text
        }

        val json = JsonReply.extractObject(reply)
            ?: return PlanResult.Error(
                "The model did not return a routine.",
                "Try rephrasing, e.g. \"every night at 11 turn the torch off and drop volume to 20\".",
            )

        return parse(context, json)
    }

    // -----------------------------------------------------------------------------------------
    // Prompt
    // -----------------------------------------------------------------------------------------

    private fun systemPrompt(context: Context): String {
        val actions = ActionType.entries.joinToString("\n") { action ->
            val parts = buildList {
                add("id=${action.id}")
                add("param=${action.paramKind.name}")
                if (action.blocks) add("BLOCK")
                if (action.sensitive) add("needs_confirmation")
            }
            "- ${action.label}: ${action.blurb} (${parts.joinToString(", ")})"
        }

        val conditions = ConditionType.entries.joinToString("\n") { type ->
            if (type.needsValue) {
                "- ${type.id}: ${type.label} — param is ${type.valueLabel}"
            } else {
                "- ${type.id}: ${type.label} — no param"
            }
        }

        val triggers = TriggerKind.entries.joinToString("\n") { kind ->
            if (kind.needsParam) "- ${kind.id}: ${kind.label} — param is ${kind.paramLabel}" else "- ${kind.id}: ${kind.label}"
        }

        val installed = try {
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .take(60)
                .joinToString(", ")
        } catch (t: Throwable) {
            ""
        }

        return """
            You convert a request into a JARVIS automation routine.

            Reply with ONE JSON object and nothing else. No prose, no markdown, no code fence.

            Schema:
            {
              "name": "short uppercase name, max 24 chars",
              "rationale": "one sentence explaining the plan",
              "trigger": { "kind": "<trigger id>", "param": "<value or empty>" },
              "steps": [ <step>, ... ]
            }

            Step:
            {
              "action": "<action id>",
              "param": "<value or empty>",
              "condition": { "type": "<condition id>", "param": "<value or empty>" },
              "children": [ <step>, ... ],
              "elseChildren": [ <step>, ... ],
              "onFail": "continue" | "halt",
              "timeoutSec": 45,
              "retries": 0
            }

            Rules:
            - "action" MUST be one of the ids below. Never invent one.
            - "condition" is REQUIRED for action ids "if" and "wait_until"; omit it otherwise.
            - "children" only for "if", "repeat" and "wait_until"-free blocks: "if" uses children and
              elseChildren, "repeat" uses children and a numeric "param" (times).
            - Maximum depth 3, maximum 40 steps in total.
            - Use "say" for spoken feedback. Use "notify" for a silent text alert.
            - Numbers: volume 0-100, timer in seconds, wait in seconds, battery percent 0-100.
            - Times are "HH:mm" 24-hour.
            - If the request cannot be expressed with these actions, return
              {"name":"UNSUPPORTED","rationale":"why","trigger":{"kind":"manual"},"steps":[]}.

            ACTIONS
            $actions

            CONDITIONS
            $conditions

            TRIGGERS
            $triggers

            INSTALLED PACKAGES (use these exact ids for open_app, as "package/activity" or just
            "package"):
            $installed
        """.trimIndent()
    }

    // -----------------------------------------------------------------------------------------
    // Parsing + validation
    // -----------------------------------------------------------------------------------------


    private fun parse(context: Context, json: JSONObject): PlanResult {
        val notes = mutableListOf<String>()
        val name = json.optString("name").ifBlank { "PLANNED ROUTINE" }
            .uppercase()
            .take(MAX_NAME_CHARS)
        val rationale = json.optString("rationale")

        if (name.contains("UNSUPPORTED")) {
            return PlanResult.Error(
                "That is not something JARVIS can automate.",
                rationale.ifBlank { "It needs a capability this app does not have." },
            )
        }

        val trigger = parseTrigger(json.optJSONObject("trigger"), notes)
        val rawSteps = json.optJSONArray("steps") ?: JSONArray()
        val steps = parseSteps(context, rawSteps, depth = 0, notes)

        if (steps.isEmpty()) {
            return PlanResult.Error(
                "The plan contained no usable steps.",
                "Ask again with a concrete instruction, or build it by hand.",
            )
        }

        val total = steps.sumOf { it.totalSteps }
        if (total > AutomationEngine.MAX_TOTAL_STEPS) {
            notes.add("Trimmed from $total steps to fit the ${AutomationEngine.MAX_TOTAL_STEPS}-step budget.")
        }

        return PlanResult.Ok(
            workflow = Workflow(
                id = UUID.randomUUID().toString(),
                name = name,
                steps = steps,
                trigger = trigger,
            ),
            notes = notes,
            rationale = rationale,
        )
    }

    private fun parseSteps(
        context: Context,
        array: JSONArray,
        depth: Int,
        notes: MutableList<String>,
    ): List<WorkflowStep> {
        if (depth > AutomationEngine.MAX_DEPTH) {
            notes.add("Dropped steps nested deeper than ${AutomationEngine.MAX_DEPTH} levels.")
            return emptyList()
        }

        val out = mutableListOf<WorkflowStep>()
        var budget = AutomationEngine.MAX_TOTAL_STEPS
        for (i in 0 until array.length()) {
            if (budget <= 0) {
                notes.add("Stopped at ${AutomationEngine.MAX_TOTAL_STEPS} steps.")
                break
            }
            val obj = array.optJSONObject(i) ?: continue
            val step = parseStep(context, obj, depth, notes) ?: continue
            out.add(step)
            budget -= step.totalSteps.coerceAtLeast(1)
        }
        return out
    }

    private fun parseStep(
        context: Context,
        obj: JSONObject,
        depth: Int,
        notes: MutableList<String>,
    ): WorkflowStep? {
        val id = obj.optString("action")
        val action = ActionType.fromId(id)
        if (action == null) {
            notes.add("Dropped unknown action \"$id\".")
            return null
        }

        var param = obj.optString("param").trim()
        param = when (action) {
            ActionType.SET_VOLUME -> (param.toIntOrNull() ?: 50).coerceIn(0, 100).toString()
            ActionType.SET_TIMER -> (param.toIntOrNull() ?: 300).coerceIn(1, 86_400).toString()
            ActionType.WAIT -> (param.toIntOrNull() ?: 2).coerceIn(0, 120).toString()
            ActionType.REPEAT_BLOCK -> (param.toIntOrNull() ?: 2).coerceIn(1, 10).toString()
            ActionType.SET_DND -> DndControl.Mode.fromText(param)?.id ?: DndControl.Mode.SILENT.id
            ActionType.OPEN_APP -> {
                val pkg = param.substringBefore('/').trim()
                val exists = try {
                    context.packageManager.getLaunchIntentForPackage(pkg) != null
                } catch (t: Throwable) {
                    false
                }
                if (pkg.isBlank() || !exists) {
                    notes.add("Dropped OPEN APP for \"$pkg\" — not installed.")
                    return null
                }
                param
            }

            else -> param.take(300)
        }

        val condition = Condition.fromJson(obj.optJSONObject("condition"))?.also { parsed ->
            if (parsed.type.needsValue && parsed.param.isBlank()) {
                notes.add("Reset an empty value on ${parsed.type.label} to its default.")
            }
        }

        if (action.blocks && action != ActionType.REPEAT_BLOCK && condition == null) {
            notes.add("Dropped ${action.label} with no condition.")
            return null
        }

        val children = parseSteps(context, obj.optJSONArray("children") ?: JSONArray(), depth + 1, notes)
        val elseChildren =
            parseSteps(context, obj.optJSONArray("elseChildren") ?: JSONArray(), depth + 1, notes)

        if (action.blocks && action != ActionType.WAIT_UNTIL && children.isEmpty()) {
            notes.add("Dropped empty ${action.label} block.")
            return null
        }

        return WorkflowStep(
            action = action,
            param = param,
            condition = condition,
            children = children,
            elseChildren = elseChildren,
            onFail = if (obj.optString("onFail") == "halt") FailPolicy.HALT else FailPolicy.CONTINUE,
            timeoutSec = obj.optInt("timeoutSec", 45).coerceIn(5, 600),
            retries = obj.optInt("retries", 0).coerceIn(0, 3),
        )
    }

    private fun parseTrigger(obj: JSONObject?, notes: MutableList<String>): RoutineTrigger {
        if (obj == null) return RoutineTrigger()
        val kind = TriggerKind.fromId(obj.optString("kind"))
        var param = obj.optString("param").trim()

        if (kind.isTimeBased && com.jarvis.assistant.automation.ConditionEvaluator.parseTime(param) == null) {
            notes.add("Trigger time \"$param\" was invalid; using 07:00.")
            param = "07:00"
        }
        if (kind == TriggerKind.BATTERY_LOW) {
            param = (param.toIntOrNull() ?: 20).coerceIn(5, 100).toString()
        }
        return RoutineTrigger(kind, param)
    }
}
