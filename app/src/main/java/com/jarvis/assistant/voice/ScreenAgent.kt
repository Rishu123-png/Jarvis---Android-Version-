package com.jarvis.assistant.voice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ai.ToolResult
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.JarvisAccessibilityService
import com.jarvis.assistant.automation.ScreenItem
import com.jarvis.assistant.automation.ScreenMap
import com.jarvis.assistant.automation.StepReflection
import com.jarvis.assistant.automation.StepExecutor
import com.jarvis.assistant.automation.WorkflowStep
import com.jarvis.assistant.data.CoreLink
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

    private const val MAX_STEPS = 20

    /**
     * A wall-clock ceiling as well as a step ceiling. A loop that keeps finding something to tap can
     * otherwise sit in an app for a very long time; four minutes is longer than any single spoken
     * task should ever take, and it is a promise the operator's phone is not being held.
     */
    private const val MAX_TASK_MS = 4 * 60 * 1000L
    private const val MAX_LABELS = 40
    private const val SETTLE_MS = 900L
    private const val DECISION_TIMEOUT_MS = 20_000L

    /**
     * How long one step waits on the core before falling back to the key. Shorter than a whole
     * sentence gets: this loop can make twenty decisions, so a sleeping PC must not cost twenty
     * full timeouts.
     */
    private const val CORE_DECISION_TIMEOUT_MS = 12_000L

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
        val startedAt = System.currentTimeMillis()

        repeat(MAX_STEPS) { index ->
            if (System.currentTimeMillis() - startedAt > MAX_TASK_MS) {
                return "That was taking too long, so I stopped. Say it again if you want me to carry on."
            }
            val app = JarvisAccessibilityService.currentPackage() ?: "unknown"
            var items = JarvisAccessibilityService.describeScreen(MAX_LABELS)
            var viaOcr = false

            if (items.isEmpty()) {
                // Some apps draw their own UI, so the tree is empty. Read the pixels instead.
                items = readScreenByOcr(context).map { ScreenItem(label = it) }
                viaOcr = items.isNotEmpty()
                if (items.isEmpty() && index > 0) {
                    // A blank screen is usually an ad or a splash; wait once, then decide again.
                    delay(SETTLE_MS)
                }
            }

            // What the screen looked like before the action, so the next iteration can tell whether
            // the action did anything at all. Skipped for OCR frames: two readings of the same page
            // can differ by a character, and a false "changed" is worse than no check.
            val before = if (viaOcr) null else items.joinToString("|") { it.label }

            val decision = decide(context, goal, app, items, history, viaOcr)
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
                    if (!JarvisAccessibilityService.pressLoose(target)) {
                        // The label was on screen a moment ago. Try the exact control by id before
                        // calling it a miss: labels are ambiguous, resource ids are not.
                        val byId = StepReflection.retryTarget(target, items)
                        if (byId == null || !JarvisAccessibilityService.pressLoose(byId)) {
                            return "I could not find \"$target\" on screen."
                        }
                        history.add("pressed \"$target\" by id")
                    } else {
                        history.add("pressed \"$target\"")
                    }
                }

                "scroll" -> {
                    val forward = !decision.param.trim().equals("up", ignoreCase = true)
                    onEvent(Event(if (forward) "SCROLL DOWN" else "SCROLL UP", decision.say.take(48)))
                    val moved = if (forward) {
                        JarvisAccessibilityService.scrollForward()
                    } else {
                        JarvisAccessibilityService.scrollBackward()
                    }
                    if (!moved) return "Nothing on this screen scrolls."
                    history.add(if (forward) "scrolled down" else "scrolled up")
                }

                "swipe" -> {
                    val metrics = context.resources.displayMetrics
                    val path = ScreenMap.swipePath(decision.param, metrics.widthPixels, metrics.heightPixels)
                        ?: return "It asked for a swipe I do not understand (${decision.param})."
                    onEvent(Event("SWIPE ${decision.param.uppercase(Locale.US).take(16)}", decision.say.take(48)))
                    val done = JarvisAccessibilityService.swipe(
                        path.first.first,
                        path.first.second,
                        path.second.first,
                        path.second.second,
                    )
                    if (!done) return "The swipe was refused."
                    history.add("swiped ${decision.param}")
                }

                "tap" -> {
                    val point = ScreenMap.parsePoint(decision.param)
                        ?: return "It gave a tap position I could not read."
                    onEvent(Event("TAP ${point.first},${point.second}", decision.say.take(48)))
                    if (!JarvisAccessibilityService.tapAt(point.first, point.second)) {
                        return "The tap was refused."
                    }
                    history.add("tapped ${point.first},${point.second}")
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

            // Open-loop no longer. If an action that must change something changed nothing, say so
            // in the history rather than letting the model believe it worked — and give the same
            // control one more attempt, by resource id, for free.
            if (before != null && decision.action != "wait") {
                val afterItems = JarvisAccessibilityService.describeScreen(MAX_LABELS)
                val after = afterItems.joinToString("|") { it.label }
                val appAfter = JarvisAccessibilityService.currentPackage() ?: app
                when (StepReflection.of(decision.action, before, after, app, appAfter)) {
                    StepReflection.Verdict.NO_EFFECT -> {
                        val note = reflectAndRetry(decision, items, onEvent)
                        history.add(note)
                    }

                    StepReflection.Verdict.BLIND ->
                        history.add("the screen could not be read after that")

                    else -> Unit
                }
            }
        }

        return "I ran out of steps before finishing."
    }

    /**
     * One more go at a control that did nothing, and a line of history either way.
     *
     * The second attempt is by resource id — the retry the model could not make, because a model can
     * only read labels. If it works the loop carries on as if the first tap had landed; if it does
     * not, the model is told, in the same list it already reads, that this control is not responding.
     * That is the cheapest possible feedback loop: no extra model call, no extra screen read.
     */
    private suspend fun reflectAndRetry(
        decision: Decision,
        items: List<ScreenItem>,
        onEvent: (Event) -> Unit,
    ): String {
        val target = decision.param
        val byId = if (decision.action == "press") StepReflection.retryTarget(target, items) else null
        if (byId == null) return "$target did nothing"
        onEvent(Event("RETRY ${byId.uppercase(Locale.US).take(20)}", "no change, trying by id"))
        delay(SETTLE_MS)
        if (!JarvisAccessibilityService.pressLoose(byId)) return "$target did nothing; $byId was gone"
        return "$target did nothing; $byId worked"
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

    /** Screenshot + on-device OCR, used only when the accessibility tree has nothing to offer. */
    private suspend fun readScreenByOcr(context: Context): List<String> {
        val (bitmap, _) = JarvisAccessibilityService.captureBitmap(context)
        if (bitmap == null) return emptyList()
        return try {
            Ocr.readLines(context, bitmap, MAX_LABELS)
        } catch (t: Throwable) {
            emptyList()
        } finally {
            try {
                bitmap.recycle()
            } catch (t: Throwable) {
                // Already recycled.
            }
        }
    }

    private suspend fun decide(
        context: Context,
        goal: String,
        app: String,
        items: List<ScreenItem>,
        history: List<String>,
        viaOcr: Boolean,
    ): Decision? {
        if (!IntentRouter.aiReady(context)) return null

        val done = if (history.isEmpty()) "- nothing yet" else history.joinToString("\n") { "- $it" }
        val visible = ScreenMap.render(items)
        val source = if (viaOcr) "read from the screen image (no accessibility text)" else "accessibility text"

        val system = """
            You are driving an Android phone through an accessibility service, ONE action at a time.

            Reply with ONE JSON object and nothing else:
            {"action":"press|scroll|swipe|tap|type|global|open_app|wait|done|give_up","param":"...","say":"short note"}

            Action meanings:
            - press: tap a control. param must be a label from the visible list, copied exactly.
            - scroll: move the page. param is down or up. Use this before giving up on a list.
            - swipe: drag across the screen. param is up, down, left or right.
            - tap: tap an exact point. param is "x,y" copied from an "@(x,y)" in the visible list.
            - type: type into the field that already has focus. Press the field first if needed.
            - global: system gesture. param is back, home, recents, shade or lock.
            - open_app: launch an app. param is a package name.
            - wait: pause. param is seconds, 1 to 5.
            - done: the goal is achieved.
            - give_up: the goal cannot be reached from here. Explain in say.

            Each visible line is: #index "label" [class] #id @(x,y) tappable
            Use the label for press. Use #id when the label is missing or shared by several rows.
            Use @(x,y) only with tap, and only when no label or id fits.

            Rules:
            - One action per reply. Do not describe several steps.
            - Only press labels that appear in the visible list.
            - If the visible text was read from a screenshot, labels are approximate: copy the words
              exactly as given, and prefer the shortest label that clearly means the control you want.
            - Never repeat an action that already appears in the actions taken list.
            - When the goal is met, reply done immediately.
            - "say" is at most 40 characters, present tense, about what you are doing.
        """.trimIndent()

        val prompt = """
            Goal: $goal
            App in front: $app
            Actions taken:
            $done
            Visible, $source:
            $visible
        """.trimIndent()

        // The decision is one action from a fixed list, which is exactly what a tool schema is for:
        // ask for it as a call and there is no object to parse, and no way to answer in prose.
        val asked = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            if (AiClient.toolsWorthTrying()) {
                val fromCore = if (CoreLink.isEnabled(context)) {
                    withTimeoutOrNull(CORE_DECISION_TIMEOUT_MS) {
                        AiClient.coreWithTools(
                            context,
                            listOf(AiMessage.user(prompt)),
                            system,
                            screen = true,
                        )
                    }
                } else {
                    null
                }
                when (val tool = fromCore ?: AiClient.chatWithTools(
                    context,
                    listOf(AiMessage.user(prompt)),
                    system,
                    screen = true,
                )) {
                    is ToolResult.Called -> tool.call.argumentsObject()?.let { decisionFrom(it) }
                    is ToolResult.Said -> tool.text.takeIf { it.isNotBlank() }?.let { decisionFromProse(it) }
                    is ToolResult.Unsupported, is ToolResult.Failed -> null
                }
            } else {
                null
            }
        }
        asked?.let { return it }

        // Prose fallback, unchanged: a provider that refuses tools, or a model that ignores them.
        // Same preference as a spoken command: the operator's own machine first when it is on,
        // the key when it is not. A step gets a shorter core deadline than a whole sentence —
        // this loop runs many times, so waiting a full timeout on every step would crawl.
        val reply = withTimeoutOrNull(DECISION_TIMEOUT_MS) {
            val fromCore = if (CoreLink.isEnabled(context)) {
                withTimeoutOrNull(CORE_DECISION_TIMEOUT_MS) {
                    AiClient.chatOnCore(context, listOf(AiMessage.user(prompt)), system)
                }
            } else {
                null
            }
            val answer = if (fromCore is AiResult.Ok) {
                fromCore
            } else {
                AiClient.chat(context, listOf(AiMessage.user(prompt)), system)
            }
            (answer as? AiResult.Ok)?.text
        } ?: return null

        return decisionFromProse(reply)
    }

    private fun decisionFromProse(reply: String): Decision? {
        val json = com.jarvis.assistant.ai.JsonReply.extractObject(reply) ?: return null
        return decisionFrom(json)
    }

    /** One object, one decision. Shared by the tool path and the prose path so they cannot drift. */
    private fun decisionFrom(json: JSONObject): Decision? {
        val action = json.optString("action").trim().lowercase(Locale.US)
        if (action.isEmpty()) return null
        return Decision(
            action = action,
            param = json.optString("param").trim(),
            say = json.optString("say").trim(),
        )
    }

    private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(ms) { block() }

}
