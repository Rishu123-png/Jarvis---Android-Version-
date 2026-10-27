package com.jarvis.assistant.voice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.JarvisAccessibilityService
import com.jarvis.assistant.automation.StepExecutor
import com.jarvis.assistant.automation.WorkflowStep
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * The screen-aware half of the brain.
 *
 * Fixed plans cover what JARVIS already knows how to do. Everything else — tapping through an app it
 * has never seen, finding a switch on a settings page, filling a form — needs a loop that can look
 * at the screen, decide one action, do it, and look again. That is what this is: a bounded
 * observe → decide → act loop, driven by the operator's own model.
 *
 * Boundaries, because unbounded agents are how phones end up in strange states:
 *
 *  - [MAX_STEPS] actions per task, one at a time, with a pause between them.
 *  - Every decision is one of a small closed set: press, type, global, open_app, wait, done, give_up.
 *  - A tap only ever targets a label that was on screen in the observation it was decided from.
 *  - Every action is surfaced on the task card as it happens, so it can be watched and stopped.
 *  - Nothing here escalates privileges; it uses the same accessibility tier the operator already
 *    granted, plus the actions StepExecutor already had.
 */
object ScreenAgent {

    private const val MAX_STEPS = 6
    private const val MAX_LABELS = 40
    private const val SETTLE_MS = 900L
    private const val DECISION_TIMEOUT_MS = 20_000L

    data class Event(val label: String, val detail: String)

    private data class Decision(
        val action: String,
        val param: String,
        val say: String,
    )

    /**
     * Runs the loop. Returns null on success, or a human-readable reason it stopped.
     * [onEvent] is called for each action so the card can show the work as it happens.
     */
    suspend fun run(
        context: Context,
        goal: String,
        onEvent: (Event) -> Unit,
    ): String? {
        if (!AccessibilityStatus.isServiceEnabled(context)) {
            return "Screen control is off, so I cannot see the app to finish this."
        }

        val history = mutableListOf<String>()

        repeat(MAX_STEPS) { index ->
            val app = AccessibilityStatus.currentPackage() ?: "unknown"
            val labels = AccessibilityStatus.visibleLabels(MAX_LABELS)
            if (labels.isEmpty() && index > 0) {
                // A blank screen is usually an ad or a splash; wait once, then give up honestly.
                delay(SETTLE_MS)
            }

            val decision = decide(context, goal, app, labels, history)
                ?: return "The brain stopped answering partway through."

            when (decision.action) {
                "done" -> {
                    onEvent(Event("FINISHED", decision.say.ifBlank { goal }.take(60)))
                    return null
                }

                "give_up" -> {
                    onEvent(Event("STOPPED", decision.say.take(60)))
                    return decision.say.ifBlank { "I could not find a way to finish that." }
                }

                "press" -> {
                    val target = decision.param
                    if (target.isBlank()) return "It tried to press nothing."
                    onEvent(Event("PRESS ${target.uppercase(Locale.US).take(22)}", decision.say.take(48)))
                    if (!AccessibilityStatus.pressLoose(target)) {
                        return "I could not find \"$target\" on screen."
                    }
                    history.add("pressed \"$target\"")
                }

                "type" -> {
                    val text = decision.param
                    if (text.isBlank()) return "It tried to type nothing."
                    onEvent(Event("TYPE ${text.take(18).uppercase(Locale.US)}", decision.say.take(48)))
                    if (!JarvisAccessibilityService.typeInto(text)) {
                        return "No text field had focus to type into."
                    }
                    history.add("typed \"${text.take(24)}\"")
                }

                "global" -> {
                    val key = decision.param.ifBlank { "back" }
                    onEvent(Event("SYSTEM ${key.uppercase(Locale.US).take(18)}", decision.say.take(48)))
                    if (!performGlobal(key)) return "The system gesture \"$key\" failed."
                    history.add("system $key")
                }

                "open_app" -> {
                    val target = decision.param
                    onEvent(Event("OPEN ${target.take(18).uppercase(Locale.US)}", decision.say.take(48)))
                    val outcome = StepExecutor.execute(
                        context,
                        WorkflowStep(action = ActionType.OPEN_APP, param = target),
                    )
                    if (outcome is StepExecutor.Result.Failed) return outcome.reason
                    history.add("opened $target")
                }

                "wait" -> {
                    val seconds = decision.param.toIntOrNull()?.coerceIn(1, 5) ?: 2
                    onEvent(Event("WAIT ${seconds}S", decision.say.take(48)))
                    delay(seconds * 1_000L)
                }

                else -> return "It asked for an action I do not support (${decision.action})."
            }

            delay(SETTLE_MS)
        }

        return "I ran out of steps before finishing."
    }

    private fun performGlobal(key: String): Boolean {
        val action = when (key.lowercase(Locale.US)) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "shade", "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return false
        }
        return JarvisAccessibilityService.perform(action)
    }

    private suspend fun decide(
        context: Context,
        goal: String,
        app: String,
        labels: List<String>,
        history: List<String>,
    ): Decision? {
        if (AiKeyStore.config(context) == null) return null

        val done = if (history.isEmpty()) "- nothing yet" else history.joinToString("\n") { "- $it" }
        val visible = if (labels.isEmpty()) "(nothing readable)" else labels.joinToString(" | ")

        val system = """
            You are driving an Android phone through an accessibility service, ONE action at a time.

            Reply with ONE JSON object and nothing else:
            {"action":"press|type|global|open_app|wait|done|give_up","param":"...","say":"short note"}

            Action meanings:
            - press: tap a control. param must be a label from the visible list, copied exactly.
            - type: type into the field that already has focus. Press the field first if needed.
            - global: system gesture. param is back, home, recents, shade or lock.
            - open_app: launch an app. param is a package name.
            - wait: pause. param is seconds, 1 to 5.
            - done: the goal is achieved.
            - give_up: the goal cannot be reached from here. Explain in say.

            Rules:
            - One action per reply. Do not describe several steps.
            - Only press labels that appear in the visible list.
            - Never repeat an action that already appears in the actions taken list.
            - When the goal is met, reply done immediately.
            - "say" is at most 40 characters, present tense, about what you are doing.
        """.trimIndent()

        val prompt = """
            Goal: $goal
            App in front: $app
            Actions taken:
            $done
            Visible labels: $visible
        """.trimIndent()

        val reply = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            when (val result = AiClient.chat(context, listOf(AiMessage.user(prompt)), system)) {
                is AiResult.Ok -> result.text
                is AiResult.Error -> null
            }
        } ?: return null

        val json = extractJson(reply) ?: return null
        return Decision(
            action = json.optString("action").trim().lowercase(Locale.US),
            param = json.optString("param").trim(),
            say = json.optString("say").trim(),
        )
    }

    private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(ms) { block() }

    private fun extractJson(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(reply.substring(start, end + 1))
        } catch (t: Throwable) {
            null
        }
    }
}
