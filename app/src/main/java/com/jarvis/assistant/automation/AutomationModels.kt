package com.jarvis.assistant.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single automatable action.
 *
 * [paramKind] drives the editor UI, so adding an action here is enough to make it editable.
 * [needsAccessibility] and [needsNotificationAccess] are surfaced before a routine runs, and
 * [sensitive] marks steps that must be confirmed by the operator first.
 */
enum class ActionType(
    val id: String,
    val label: String,
    val blurb: String,
    val paramKind: ParamKind,
    val needsAccessibility: Boolean = false,
    val needsNotificationAccess: Boolean = false,
    val sensitive: Boolean = false,
) {
    OPEN_APP(
        id = "open_app",
        label = "OPEN APP",
        blurb = "Launch an installed application",
        paramKind = ParamKind.APP,
    ),
    OPEN_URL(
        id = "open_url",
        label = "OPEN LINK",
        blurb = "Open a web address in the browser",
        paramKind = ParamKind.TEXT,
    ),
    DIAL_NUMBER(
        id = "dial",
        label = "DIAL NUMBER",
        blurb = "Open the dialer pre-filled — never calls by itself",
        paramKind = ParamKind.TEXT,
    ),
    COMPOSE_SMS(
        id = "sms",
        label = "COMPOSE MESSAGE",
        blurb = "Open the SMS composer, unsent",
        paramKind = ParamKind.TEXT,
    ),
    SET_TIMER(
        id = "timer",
        label = "SET TIMER",
        blurb = "Create a countdown timer",
        paramKind = ParamKind.NUMBER,
    ),
    TORCH_ON(
        id = "torch_on",
        label = "TORCH ON",
        blurb = "Switch the flashlight on",
        paramKind = ParamKind.NONE,
    ),
    TORCH_OFF(
        id = "torch_off",
        label = "TORCH OFF",
        blurb = "Switch the flashlight off",
        paramKind = ParamKind.NONE,
    ),
    SET_VOLUME(
        id = "volume",
        label = "SET VOLUME",
        blurb = "Set media volume, 0–100",
        paramKind = ParamKind.NUMBER,
    ),
    MEDIA_TOGGLE(
        id = "media",
        label = "PLAY / PAUSE",
        blurb = "Toggle the active media session",
        paramKind = ParamKind.NONE,
        needsNotificationAccess = true,
    ),
    DISMISS_NOTIFICATIONS(
        id = "dismiss_alerts",
        label = "CLEAR ALERTS",
        blurb = "Dismiss every clearable notification",
        paramKind = ParamKind.NONE,
        needsNotificationAccess = true,
    ),
    OPEN_PANEL(
        id = "panel",
        label = "OPEN SYSTEM PANEL",
        blurb = "Wi-Fi, Bluetooth, DND, display or volume",
        paramKind = ParamKind.TEXT,
    ),
    WAIT(
        id = "wait",
        label = "WAIT",
        blurb = "Pause the routine, in seconds",
        paramKind = ParamKind.NUMBER,
    ),
    GLOBAL_ACTION(
        id = "global",
        label = "SYSTEM GESTURE",
        blurb = "Back, Home, Recents, shade or lock",
        paramKind = ParamKind.TEXT,
        needsAccessibility = true,
        sensitive = true,
    ),
    PRESS_TEXT(
        id = "press",
        label = "PRESS ON SCREEN",
        blurb = "Tap a visible button by its exact label",
        paramKind = ParamKind.TEXT,
        needsAccessibility = true,
        sensitive = true,
    );

    companion object {
        fun fromId(id: String): ActionType? = entries.firstOrNull { it.id == id }
    }
}

enum class ParamKind { NONE, TEXT, NUMBER, APP }

/** One step inside a routine. [param] holds a package key, free text, a number, or nothing. */
data class WorkflowStep(
    val action: ActionType,
    val param: String = "",
) {
    val displayParam: String
        get() = when (action.paramKind) {
            ParamKind.NONE -> "—"
            ParamKind.APP -> param.substringAfterLast('/').ifBlank { param }.ifBlank { "NOT SET" }
            ParamKind.NUMBER -> param.ifBlank { "0" }
            ParamKind.TEXT -> param.ifBlank { "NOT SET" }
        }

    val isConfigured: Boolean
        get() = when (action.paramKind) {
            ParamKind.NONE -> true
            else -> param.isNotBlank()
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action.id)
        put("param", param)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorkflowStep? {
            val type = ActionType.fromId(obj.optString("action")) ?: return null
            return WorkflowStep(type, obj.optString("param"))
        }
    }
}

/** A named, ordered sequence of [WorkflowStep]s. [id] is stable so edits keep their place. */
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<WorkflowStep>,
    val builtIn: Boolean = false,
) {
    val requiresAccessibility: Boolean get() = steps.any { it.action.needsAccessibility }
    val requiresNotificationAccess: Boolean get() = steps.any { it.action.needsNotificationAccess }
    val sensitiveCount: Int get() = steps.count { it.action.sensitive }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("builtIn", builtIn)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): Workflow? {
            val id = obj.optString("id").ifBlank { return null }
            val name = obj.optString("name").ifBlank { "Untitled routine" }
            val array = obj.optJSONArray("steps") ?: JSONArray()
            val steps = buildList {
                for (i in 0 until array.length()) {
                    val stepObj = array.optJSONObject(i) ?: continue
                    WorkflowStep.fromJson(stepObj)?.let { add(it) }
                }
            }
            return Workflow(
                id = id,
                name = name,
                steps = steps,
                builtIn = obj.optBoolean("builtIn", false),
            )
        }
    }
}
