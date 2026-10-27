package com.jarvis.assistant.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single automatable action.
 *
 * [paramKind] drives the editor UI, so adding an action here is enough to make it editable.
 * [needsAccessibility] and [needsNotificationAccess] are surfaced before a routine runs, and
 * [sensitive] marks steps that must be confirmed by the operator first.
 *
 * [blocks] marks the control-flow actions. They carry child steps instead of a value, which is what
 * turns a flat list into something that can decide, repeat and wait.
 */
enum class ActionType(
    val id: String,
    val label: String,
    val blurb: String,
    val paramKind: ParamKind,
    val needsAccessibility: Boolean = false,
    val needsNotificationAccess: Boolean = false,
    val sensitive: Boolean = false,
    val blocks: Boolean = false,
) {
    // ---- app + link ----
    OPEN_APP("open_app", "OPEN APP", "Launch an installed application", ParamKind.APP, sensitive = true),
    OPEN_URL("open_url", "OPEN LINK", "Open a web address", ParamKind.TEXT, sensitive = true),
    DEEP_LINK("deep_link", "DEEP LINK", "Open an app's own URI scheme", ParamKind.TEXT, sensitive = true),
    DIAL_NUMBER("dial", "DIAL NUMBER", "Open the dialer with a number filled in", ParamKind.TEXT, sensitive = true),
    COMPOSE_SMS("sms", "COMPOSE SMS", "Open the composer with a number filled in", ParamKind.TEXT, sensitive = true),
    CALL_CONTACT("call_contact", "CALL CONTACT", "Call a saved contact by name, e.g. Dad", ParamKind.TEXT, sensitive = true),

    // ---- capture (JARVIS takes the frame itself; no shutter to press) ----
    CAPTURE_PHOTO("capture_photo", "TAKE PHOTO", "Take one photo and save it to Pictures/JARVIS", ParamKind.NONE, sensitive = true),
    OPEN_LAST_PHOTO("last_photo", "SHOW LAST PHOTO", "Open the newest photo in a gallery app", ParamKind.NONE),
    SCREENSHOT("screenshot", "SCREENSHOT", "Capture the screen to Pictures/JARVIS", ParamKind.NONE, needsAccessibility = true),

    // ---- reminders ----
    REMINDER("reminder", "REMIND ME", "Post a reminder: \"seconds|HH:mm|text\"", ParamKind.TEXT),

    // ---- device ----
    TORCH_ON("torch_on", "TORCH ON", "Turn the flashlight on", ParamKind.NONE),
    TORCH_OFF("torch_off", "TORCH OFF", "Turn the flashlight off", ParamKind.NONE),
    SET_VOLUME("volume", "SET VOLUME", "Set media volume to a percentage", ParamKind.NUMBER),
    PLAY_MEDIA("play", "PLAY", "Resume the active media session", ParamKind.NONE, needsNotificationAccess = true),
    PAUSE_MEDIA("pause", "PAUSE", "Pause the active media session", ParamKind.NONE, needsNotificationAccess = true),
    NEXT_MEDIA("next", "NEXT TRACK", "Skip to the next track", ParamKind.NONE, needsNotificationAccess = true),
    PREVIOUS_MEDIA("previous", "PREVIOUS TRACK", "Skip to the previous track", ParamKind.NONE, needsNotificationAccess = true),
    SET_TIMER("timer", "SET TIMER", "Set a countdown timer, in seconds", ParamKind.NUMBER),
    SET_ALARM("alarm", "SET ALARM", "Set a clock alarm for a time, e.g. 07:30", ParamKind.TEXT),
    DISMISS_NOTIFICATIONS("dismiss_alerts", "CLEAR ALERTS", "Dismiss every clearable alert", ParamKind.NONE, needsNotificationAccess = true),
    NOTIFY("notify", "NOTIFY ME", "Post a JARVIS alert with your own text", ParamKind.TEXT),
    OPEN_PANEL("panel", "OPEN PANEL", "Open a system panel", ParamKind.TEXT),
    SET_DND("dnd", "DO NOT DISTURB", "Silence interruptions: off, priority, alarms, silent", ParamKind.TEXT),

    // ---- privileged (needs Shizuku; degrades to a skip when it is not connected) ----
    SHELL("shell", "SHELL COMMAND", "Run an adb-level command through Shizuku", ParamKind.TEXT, sensitive = true),

    // ---- verification: did the previous step actually work? ----
    VERIFY("verify", "VERIFY", "Check a fact before continuing, e.g. app:com.whatsapp", ParamKind.TEXT),

    // ---- speech + network ----
    SAY("say", "SAY", "Speak text out loud", ParamKind.TEXT),
    WEBHOOK("webhook", "WEBHOOK", "Call a URL you own, e.g. https://host/path", ParamKind.TEXT, sensitive = true),
    DOWNLOAD("download", "DOWNLOAD", "Save a file to Download/JARVIS, e.g. \"https://host/file|name\"", ParamKind.TEXT, sensitive = true),
    TRANSLATE("translate", "TRANSLATE", "Translate text through your AI key: \"lang|text\"", ParamKind.TEXT, sensitive = true),

    // ---- accessibility ----
    GLOBAL_ACTION("global", "SYSTEM GESTURE", "Back, home, recents, shade, quick settings, lock", ParamKind.TEXT, needsAccessibility = true, sensitive = true),
    PRESS_TEXT("press", "PRESS ON SCREEN", "Tap a control with an exact label", ParamKind.TEXT, needsAccessibility = true, sensitive = true),
    TYPE_TEXT("type_text", "TYPE TEXT", "Type into the focused text field", ParamKind.TEXT, needsAccessibility = true, sensitive = true),

    // ---- timing ----
    WAIT("wait", "WAIT", "Pause the routine, in seconds", ParamKind.NUMBER),
    WAIT_UNTIL("wait_until", "WAIT UNTIL", "Hold the routine until a condition is true", ParamKind.NONE, blocks = true),

    // ---- control flow ----
    IF_BLOCK("if", "IF", "Run the steps inside only when a condition holds", ParamKind.NONE, blocks = true),
    REPEAT_BLOCK("repeat", "REPEAT", "Run the steps inside N times", ParamKind.NUMBER, blocks = true),
    STOP_ROUTINE("stop_routine", "STOP ROUTINE", "End the routine here", ParamKind.NONE),
    ;

    companion object {
        fun fromId(id: String): ActionType? = entries.firstOrNull { it.id == id }

        /** Everything that is an ordinary action, for pickers and the AI prompt. */
        val leafActions: List<ActionType> get() = entries.filterNot { it.blocks }
    }
}

enum class ParamKind { NONE, TEXT, NUMBER, APP }

/** What the routine does when a step fails. */
enum class FailPolicy(val id: String, val label: String) {
    CONTINUE("continue", "CONTINUE"),
    HALT("halt", "HALT ROUTINE"),
    ;

    companion object {
        fun fromId(id: String?): FailPolicy =
            entries.firstOrNull { it.id == id } ?: CONTINUE
    }
}

/**
 * A test the routine can branch on.
 *
 * Conditions read live device state through [ConditionEvaluator]. They are declarative on purpose:
 * the AI planner can only choose from this list, never invent a new capability.
 */
enum class ConditionType(
    val id: String,
    val label: String,
    val valueLabel: String,
    val defaultParam: String = "",
) {
    BATTERY_BELOW("battery_below", "BATTERY BELOW", "percent", "20"),
    BATTERY_ABOVE("battery_above", "BATTERY ABOVE", "percent", "80"),
    CHARGING("charging", "IS CHARGING", "no value"),
    NOT_CHARGING("not_charging", "IS NOT CHARGING", "no value"),
    WIFI("wifi", "ON WI-FI", "no value"),
    MOBILE_DATA("mobile_data", "ON MOBILE DATA", "no value"),
    MEDIA_PLAYING("media_playing", "MEDIA IS PLAYING", "no value"),
    TIME_WINDOW("time_window", "TIME BETWEEN", "HH:mm-HH:mm", "07:00-22:00"),
    ALERTS_ABOVE("alerts_above", "ALERTS ABOVE", "count", "0"),
    APP_INSTALLED("app_installed", "APP INSTALLED", "package name", ""),
    SCREEN_ON("screen_on", "SCREEN IS ON", "no value"),
    ;

    val needsValue: Boolean
        get() = this in setOf(BATTERY_BELOW, BATTERY_ABOVE, TIME_WINDOW, ALERTS_ABOVE, APP_INSTALLED)

    companion object {
        fun fromId(id: String?): ConditionType? = entries.firstOrNull { it.id == id }
    }
}

data class Condition(
    val type: ConditionType,
    val param: String = type.defaultParam,
) {
    val display: String
        get() = if (type.needsValue && param.isNotBlank()) {
            "${type.label} $param"
        } else {
            type.label
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("param", param)
    }

    companion object {
        fun fromJson(obj: JSONObject?): Condition? {
            val source = obj ?: return null
            val type = ConditionType.fromId(source.optString("type")) ?: return null
            return Condition(type, source.optString("param", type.defaultParam))
        }
    }
}

/**
 * One step inside a routine.
 *
 * Blocks ([ActionType.IF_BLOCK], [REPEAT_BLOCK], [WAIT_UNTIL]) carry [children] (and, for IF,
 * [elseChildren]). Everything else carries a [param].
 *
 * [timeoutSec] stops a single step from stalling a whole routine, and [retries] covers the common
 * case of a transient failure (an app still launching, a media session not ready yet).
 */
data class WorkflowStep(
    val action: ActionType,
    val param: String = "",
    val condition: Condition? = null,
    val children: List<WorkflowStep> = emptyList(),
    val elseChildren: List<WorkflowStep> = emptyList(),
    val onFail: FailPolicy = FailPolicy.CONTINUE,
    val timeoutSec: Int = 45,
    val retries: Int = 0,
) {
    val displayParam: String
        get() = when {
            action.blocks -> when (action) {
                ActionType.IF_BLOCK -> condition?.display ?: "NO CONDITION"
                ActionType.REPEAT_BLOCK -> param.ifBlank { "1" } + "×"
                else -> condition?.display ?: "NO CONDITION"
            }

            action.paramKind == ParamKind.APP ->
                param.substringAfterLast('/').ifBlank { param }.ifBlank { "NOT SET" }

            action.paramKind == ParamKind.NUMBER -> param.ifBlank { "0" }
            action.paramKind == ParamKind.TEXT -> param.ifBlank { "NOT SET" }
            else -> "—"
        }

    val isConfigured: Boolean
        get() = when {
            action.blocks -> when (action) {
                ActionType.REPEAT_BLOCK -> (param.toIntOrNull() ?: 0) > 0
                else -> condition != null
            }

            action.paramKind == ParamKind.NONE -> true
            else -> param.isNotBlank()
        }

    /** Total steps including nested ones, for the run budget and the editor summary. */
    val totalSteps: Int
        get() = 1 + children.sumOf { it.totalSteps } + elseChildren.sumOf { it.totalSteps }

    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action.id)
        put("param", param)
        put("onFail", onFail.id)
        put("timeoutSec", timeoutSec)
        put("retries", retries)
        condition?.let { put("condition", it.toJson()) }
        if (children.isNotEmpty()) {
            put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })
        }
        if (elseChildren.isNotEmpty()) {
            put("elseChildren", JSONArray().apply { elseChildren.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): WorkflowStep? {
            val type = ActionType.fromId(obj.optString("action")) ?: return null
            return WorkflowStep(
                action = type,
                param = obj.optString("param"),
                condition = Condition.fromJson(obj.optJSONObject("condition")),
                children = parseList(obj.optJSONArray("children")),
                elseChildren = parseList(obj.optJSONArray("elseChildren")),
                onFail = FailPolicy.fromId(obj.optString("onFail")),
                timeoutSec = obj.optInt("timeoutSec", 45).coerceIn(5, 600),
                retries = obj.optInt("retries", 0).coerceIn(0, 3),
            )
        }

        private fun parseList(array: JSONArray?): List<WorkflowStep> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    fromJson(item)?.let { add(it) }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Triggers
// -------------------------------------------------------------------------------------------

/**
 * What makes a routine start on its own.
 *
 * Every trigger here is one Android actually permits a sideloaded app to use. Nothing pretends to
 * work while the app is dead: [MANUAL] can never fire on its own, and the app-level switch gates
 * the rest.
 */
enum class TriggerKind(
    val id: String,
    val label: String,
    val blurb: String,
    val paramLabel: String? = null,
) {
    MANUAL("manual", "MANUAL", "Only when you tap run"),
    DAILY("daily", "DAILY AT A TIME", "Every day at a set time", "HH:mm"),
    WEEKDAYS("weekdays", "WEEKDAYS AT A TIME", "Monday to Friday", "HH:mm"),
    CHARGING("charging", "ON CHARGER", "When a charger is connected"),
    UNPLUGGED("unplugged", "OFF CHARGER", "When the charger is removed"),
    WIFI_CONNECTED("wifi_on", "ON WI-FI", "When a Wi-Fi link comes up"),
    BATTERY_LOW("battery_low", "BATTERY BELOW", "When the charge drops under a level", "percent"),
    BOOT("boot", "AFTER REBOOT", "Once the device finishes booting"),
    NOTIFICATION("notification", "ON NOTIFICATION", "When an app posts an alert", "package name"),
    UNLOCK("unlock", "ON UNLOCK", "When the screen unlocks while JARVIS runs"),
    ;

    val needsParam: Boolean get() = paramLabel != null
    val isTimeBased: Boolean get() = this == DAILY || this == WEEKDAYS
    val isSilent: Boolean get() = this == MANUAL

    companion object {
        fun fromId(id: String?): TriggerKind =
            entries.firstOrNull { it.id == id } ?: MANUAL
    }
}

data class RoutineTrigger(
    val kind: TriggerKind = TriggerKind.MANUAL,
    val param: String = "",
) {
    val display: String
        get() = when {
            kind == TriggerKind.MANUAL -> "Manual only"
            kind.needsParam -> "${kind.label} $param".trim()
            else -> kind.label
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.id)
        put("param", param)
    }

    companion object {
        fun fromJson(obj: JSONObject?): RoutineTrigger {
            if (obj == null) return RoutineTrigger()
            return RoutineTrigger(
                kind = TriggerKind.fromId(obj.optString("kind")),
                param = obj.optString("param"),
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Workflow
// -------------------------------------------------------------------------------------------

/** A named, ordered sequence of [WorkflowStep]s. [id] is stable so edits keep their place. */
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<WorkflowStep>,
    val builtIn: Boolean = false,
    val trigger: RoutineTrigger = RoutineTrigger(),
    val enabled: Boolean = true,
) {
    private val allSteps: List<WorkflowStep>
        get() {
            val out = mutableListOf<WorkflowStep>()
            fun walk(list: List<WorkflowStep>) {
                list.forEach { step ->
                    out.add(step)
                    walk(step.children)
                    walk(step.elseChildren)
                }
            }
            walk(steps)
            return out
        }

    val flatSteps: List<WorkflowStep> get() = allSteps
    val requiresAccessibility: Boolean get() = allSteps.any { it.action.needsAccessibility }
    val requiresNotificationAccess: Boolean get() = allSteps.any { it.action.needsNotificationAccess }
    val sensitiveCount: Int get() = allSteps.count { it.action.sensitive }
    val totalSteps: Int get() = steps.sumOf { it.totalSteps }
    val isArmed: Boolean get() = enabled && !trigger.kind.isSilent

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("builtIn", builtIn)
        put("enabled", enabled)
        put("trigger", trigger.toJson())
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        /** Bumped whenever the stored shape changes, so old data can be migrated rather than lost. */
        const val SCHEMA = 2

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
                // Schema 1 had no trigger or enabled flag: those routines were manual and active.
                trigger = RoutineTrigger.fromJson(obj.optJSONObject("trigger")),
                enabled = obj.optBoolean("enabled", true),
            )
        }
    }
}
