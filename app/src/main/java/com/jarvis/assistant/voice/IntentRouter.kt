package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ai.JsonReply
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.ParamKind
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.InstalledApp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** One executable step, already resolved to something [com.jarvis.assistant.automation.StepExecutor] knows. */
data class PlannedStep(val action: ActionType, val param: String, val label: String)

data class VoicePlan(
    val title: String,
    val subtitle: String,
    val steps: List<PlannedStep>,
    val say: String,
    val source: String,
    /** When set, the screen-aware loop takes over after [steps] and works towards this goal. */
    val screenGoal: String? = null,
)

/**
 * The brain: one spoken sentence in, a short validated plan out.
 *
 * Two paths, in this order:
 *
 *  1. **AI** — used whenever a key is stored. The operator never sees a command list; the model
 *     reads the raw words (typos, mixed language, half-finished sentences included) and picks from
 *     the capabilities the phone actually has. Anything the model invents is dropped.
 *  2. **Local** — a small, silent parser used when no key is stored, when the network is down, or
 *     when the model answers with nonsense. It covers the handful of phrasings people really use
 *     ("call dad", "open whatsapp", "torch on").
 *
 * Nothing here executes anything. [VoiceController] runs the steps and shows them on the card.
 */
object IntentRouter {

    private const val VIRTUAL_SEND = "send_message"
    private const val VIRTUAL_SCREEN = "screen_task"
    private const val MAX_STEPS = 8
    private const val MAX_SPOKEN = 240

    /** Actions the voice surface may use. Shell and webhooks are excluded on purpose. */
    private val voiceActions: List<ActionType> = ActionType.entries.filter {
        !it.blocks && it != ActionType.SHELL && it != ActionType.WEBHOOK
    }

    fun aiReady(context: Context): Boolean = AiKeyStore.config(context) != null

    suspend fun plan(context: Context, heard: String): VoicePlan {
        val spoken = heard.trim()
        if (spoken.isEmpty()) {
            return VoicePlan(
                title = "NO INPUT",
                subtitle = "",
                steps = emptyList(),
                say = "I did not hear anything.",
                source = "LOCAL",
            )
        }

        if (aiReady(context)) {
            val viaAi = try {
                aiPlan(context, spoken)
            } catch (t: Throwable) {
                null
            }
            if (viaAi != null) return viaAi
        }

        return localPlan(context, spoken)
    }

    // ---------------------------------------------------------------------------------------------
    // AI path
    // ---------------------------------------------------------------------------------------------

    private suspend fun aiPlan(context: Context, heard: String): VoicePlan? {
        val system = systemPrompt(context)
        val result = AiClient.chat(
            context = context,
            messages = listOf(AiMessage.user(heard)),
            system = system,
        )
        val reply = when (result) {
            is AiResult.Error -> return null
            is AiResult.Ok -> result.text
        }

        val json = JsonReply.extractObject(reply) ?: return null
        val title = json.optString("title").trim().uppercase(Locale.US).take(28).ifBlank { "COMMAND" }
        val say = json.optString("say").trim().take(MAX_SPOKEN)

        val steps = mutableListOf<PlannedStep>()
        var screenGoal: String? = null
        val rawSteps: JSONArray? = json.optJSONArray("steps")
        if (rawSteps != null) {
            for (index in 0 until minOf(rawSteps.length(), MAX_STEPS)) {
                val item = rawSteps.optJSONObject(index) ?: continue
                val id = item.optString("action").trim()
                val param = item.optString("param").trim()
                val label = item.optString("label").trim().uppercase(Locale.US).take(28)

                if (id == VIRTUAL_SEND) {
                    steps.addAll(sendSteps(context, param, label))
                    continue
                }

                if (id == VIRTUAL_SCREEN) {
                    screenGoal = param.ifBlank { heard }
                    continue
                }

                val action = voiceActions.firstOrNull { it.id == id } ?: continue
                steps.add(PlannedStep(action, param, label.ifBlank { action.label }))
            }
        }

        val resolved = steps.take(MAX_STEPS)
        if (resolved.isEmpty() && screenGoal == null && say.isBlank()) return null

        val sourceLabel = when (result) {
            is AiResult.Ok -> "AI · ${result.providerLabel}"
            else -> "AI"
        }

        val subtitle = when {
            resolved.isEmpty() && screenGoal != null -> "SCREEN TASK"
            resolved.isEmpty() -> "ANSWER"
            screenGoal != null -> "${resolved.size} STEP + SCREEN"
            else -> "${resolved.size} STEP" + if (resolved.size == 1) "" else "S"
        }

        return VoicePlan(
            title = title,
            subtitle = subtitle,
            steps = resolved,
            say = say.ifBlank { if (resolved.isEmpty()) "Done." else "On it." },
            source = sourceLabel,
            screenGoal = screenGoal,
        )
    }

    private suspend fun systemPrompt(context: Context): String {
        val actions = voiceActions.joinToString("\n") { action ->
            val kind = when (action.paramKind) {
                ParamKind.NONE -> "no param"
                ParamKind.NUMBER -> "numeric param"
                ParamKind.APP -> "app param (package name)"
                ParamKind.TEXT -> "text param"
            }
            "- ${action.id}: ${action.label} — ${action.blurb} ($kind)"
        }

        val installed = installedAppLines(context)

        val recents = VoiceMemory.recent(context, 4)
            .joinToString("\n") { "- ${it.heard} -> ${it.summary}" }
            .ifBlank { "- none yet" }

        return """
            You are JARVIS on the operator's own Android phone. Turn ONE spoken sentence into a
            short list of device actions.

            Reply with ONE JSON object and nothing else. No prose, no markdown, no code fence.

            {
              "title": "3 TO 5 WORD UPPERCASE SUMMARY",
              "say": "one short sentence spoken back to the operator",
              "steps": [ { "action": "action id", "param": "value", "label": "2 TO 4 WORD STEP" } ]
            }

            Actions you may use:
            $actions
            - $VIRTUAL_SEND: SEND MESSAGE — send a chat message. param is exactly
              "app|contact|text", where app is whatsapp, telegram or sms.
              Example: "whatsapp|Divik|hi, on my way".

            Rules:
            - To call someone use call_contact with the person's name exactly as heard. Never invent a number.
            - Chained requests become several steps, in the order spoken: "open camera, capture, show me"
              is capture_photo, wait 1, last_photo. Put a wait (2 seconds) between steps that hand over
              to another app, and before pressing a control in it.
            - To remind the operator use reminder with param "seconds|HH:mm|text", leaving the part
              that does not apply empty. Example: "600||call dad" or "0|18:30|leave for the station".
            - To capture the screen use screenshot.
            - Take a photo with capture_photo — JARVIS has its own capture surface, so there is no
              shutter to press. Show the newest photo with last_photo.
            - For anything that needs finding your way around inside another app (a switch on a
              settings page, a chat buried behind a search box), use exactly one step:
              {"action": "$VIRTUAL_SCREEN", "param": "<the goal in plain words>"}. That loop taps one
              control at a time and reports what it sees. Never guess presses for an app you cannot see.
            - To message someone use $VIRTUAL_SEND. Never invent a number either.
            - To open an app use open_app with a package name from the installed list.
            - The operator's speech may be sloppy, abbreviated or mixed with Hindi words. Infer intent.
            - If the request is a question, conversation, or something a phone cannot do, return
              "steps": [] and answer honestly in "say". Never claim an action you did not plan.
            - Never use shell or webhook. Never return more than $MAX_STEPS steps.
            - Keep "say" under 200 characters and do not describe every step; one sentence is enough.

            Installed apps (label — package):
            $installed

            Recent commands for context:
            $recents
        """.trimIndent()
    }

    private suspend fun installedAppLines(context: Context): String = try {
        AppRepository.loadApps(context)
            .take(45)
            .joinToString("\n") { "- ${it.label} — ${it.packageName}" }
    } catch (t: Throwable) {
        "- unavailable"
    }

    // ---------------------------------------------------------------------------------------------
    // Messaging — the part that needs a contact, so it is resolved locally and never by the model
    // ---------------------------------------------------------------------------------------------

    private suspend fun sendSteps(context: Context, param: String, label: String): List<PlannedStep> {
        val parts = param.split('|')
        val app = parts.getOrNull(0)?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "whatsapp" }
        val contact = parts.getOrNull(1)?.trim().orEmpty()
        val text = parts.getOrNull(2)?.trim().orEmpty()

        if (contact.isBlank()) {
            return listOf(PlannedStep(ActionType.SAY, "I did not catch who to message.", "NO CONTACT"))
        }

        val match = Contacts.resolve(context, contact)
            ?: return listOf(
                PlannedStep(
                    ActionType.SAY,
                    "I could not find $contact in your contacts.",
                    "CONTACT NOT FOUND",
                ),
            )

        val digits = match.number.filter { it.isDigit() }
        if (digits.length < 6) {
            return listOf(
                PlannedStep(
                    ActionType.SAY,
                    "The number saved for ${match.name} does not look complete.",
                    "NUMBER INCOMPLETE",
                ),
            )
        }

        val who = if (match.how == "memory") contact.uppercase(Locale.US) else match.name.uppercase(Locale.US)

        if (app == "sms") {
            val uri = "smsto:${match.number}" + if (text.isNotBlank()) "?body=${encode(text)}" else ""
            return listOf(PlannedStep(ActionType.DEEP_LINK, uri, "SMS · $who".take(28)))
        }

        if (app == "telegram") {
            return listOf(
                PlannedStep(
                    ActionType.SAY,
                    "Telegram has no link that opens a chat by phone number, so I can open it but not write to ${match.name}.",
                    "TELEGRAM LIMITED",
                ),
                PlannedStep(ActionType.OPEN_APP, telegramPackage(), "OPEN TELEGRAM"),
            )
        }

        val uri = "https://wa.me/$digits" + if (text.isNotBlank()) "?text=${encode(text)}" else ""
        val steps = mutableListOf(PlannedStep(ActionType.DEEP_LINK, uri, "CHAT · $who".take(28)))

        if (text.isNotBlank() && canAutoSend(context)) {
            steps.add(PlannedStep(ActionType.WAIT, "3", "WAIT FOR CHAT"))
            steps.add(PlannedStep(ActionType.PRESS_TEXT, "Send", "PRESS SEND"))
        }
        return steps
    }

    private fun telegramPackage(): String = "org.telegram.messenger/org.telegram.ui.LaunchActivity"

    private fun canAutoSend(context: Context): Boolean =
        AccessibilityStatus.isServiceEnabled(context) && AccessibilityStatus.isScreenPressAllowed(context)

    private fun encode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (t: Throwable) {
        value
    }

    // ---------------------------------------------------------------------------------------------
    // Local path — silent fallback, no menu anywhere in the UI
    // ---------------------------------------------------------------------------------------------

    private suspend fun localPlan(context: Context, heard: String): VoicePlan {
        fun answer(
            title: String,
            say: String,
            steps: List<PlannedStep> = emptyList(),
            subtitle: String = "",
        ) = VoicePlan(title, subtitle, steps, say, "LOCAL")

        val clean = VoiceParsing.normalise(heard)
        if (clean.isBlank()) return answer("NO INPUT", "I did not hear anything.")

        if (clean == "cancel" || clean == "stop" || clean == "never mind" || clean == "nevermind") {
            return answer("CANCELLED", "Cancelled.", subtitle = "IDLE")
        }

        // "again" / "repeat" re-runs the previous command.
        if (clean in setOf("again", "repeat", "do it again", "repeat that", "same again")) {
            val previous = VoiceMemory.lastCommand(context)
                ?: return answer("NOTHING TO REPEAT", "There is nothing to repeat yet.")
            return localPlan(context, previous)
        }

        // People speak in chains; flatten before parsing.
        val spoken = VoiceParsing.flatten(clean)

        // Messaging first: "whatsapp divik hi" would otherwise read as "open whatsapp".
        VoiceParsing.parseMessage(spoken)?.let { (app, contact, text) ->
            val steps = sendSteps(context, "$app|$contact|$text", "MESSAGE")
            val say = if (text.isBlank()) "Opening the chat with $contact." else "Writing to $contact."
            return answer("MESSAGE \u00B7 $contact".take(28).uppercase(Locale.US), say, steps, "LOCAL")
        }

        // Calling.
        Regex("^(?:call|phone|dial|ring)\\s+(?:to\\s+)?(.+)$").find(clean)?.let { match ->
            val who = match.groupValues[1].trim()
            return answer(
                "CALL",
                "Calling $who.",
                listOf(PlannedStep(ActionType.CALL_CONTACT, who, "CALL ${who.take(22).uppercase(Locale.US)}")),
            )
        }

        // Reminders.
        VoiceParsing.parseReminder(clean)?.let { ask ->
            val payload = "${ask.inSeconds ?: 0}|${ask.atClock.orEmpty()}|${ask.text}"
            return answer(
                "REMINDER",
                "Reminder noted.",
                listOf(PlannedStep(ActionType.REMINDER, payload, "SET REMINDER")),
            )
        }

        // Camera chain: "open camera, capture, show me". Works with no key at all.
        VoiceParsing.cameraAsk(clean)?.let { ask ->
            val steps = mutableListOf(PlannedStep(ActionType.CAPTURE_PHOTO, "", "TAKE PHOTO"))
            if (ask.showNewest) {
                steps.add(PlannedStep(ActionType.WAIT, "1", "WAIT"))
                steps.add(PlannedStep(ActionType.OPEN_LAST_PHOTO, "", "SHOW PHOTO"))
            }
            return answer(
                if (ask.showNewest) "PHOTO \u00B7 SHOW" else "PHOTO",
                if (ask.showNewest) "Taking a photo and opening it." else "Taking a photo.",
                steps,
            )
        }

        // Screenshot.
        if (clean.contains("screenshot") || clean.contains("screen shot") || clean.contains("screen grab")) {
            return answer(
                "SCREENSHOT",
                "Capturing the screen.",
                listOf(PlannedStep(ActionType.SCREENSHOT, "", "SCREENSHOT")),
            )
        }

        // Torch.
        if (clean.contains("torch") || clean.contains("flashlight")) {
            val off = clean.contains("off") || clean.contains("band") || clean.contains("close")
            val action = if (off) ActionType.TORCH_OFF else ActionType.TORCH_ON
            return answer(
                if (off) "TORCH OFF" else "TORCH ON",
                if (off) "Torch off." else "Torch on.",
                listOf(PlannedStep(action, "", action.label)),
            )
        }

        // Do not disturb.
        if (clean.contains("do not disturb") || clean.contains("dnd") || clean.contains("silent mode")) {
            val off = clean.contains("off") || clean.contains("disable") || clean.contains("band")
            val param = if (off) "off" else "silent"
            return answer(
                if (off) "DND OFF" else "DND ON",
                if (off) "Interruptions back on." else "Do not disturb on.",
                listOf(PlannedStep(ActionType.SET_DND, param, if (off) "DND OFF" else "DND ON")),
            )
        }

        // Volume.
        VoiceParsing.parseVolume(clean)?.let { level ->
            return answer(
                "VOLUME $level",
                "Volume $level.",
                listOf(PlannedStep(ActionType.SET_VOLUME, level.toString(), "VOLUME $level")),
            )
        }

        // Panels.
        Regex("^(?:open|show)\\s+(wifi|wi-fi|bluetooth|bt|display|screen brightness|battery|audio|sound)(?:\\s+(?:panel|settings|controls))?$")
            .find(clean)?.let { match ->
                val key = when (match.groupValues[1]) {
                    "wi-fi" -> "wifi"
                    "bt" -> "bluetooth"
                    "screen brightness" -> "display"
                    "sound" -> "volume"
                    else -> match.groupValues[1]
                }
                return answer(
                    "PANEL",
                    "Opening $key.",
                    listOf(
                        PlannedStep(
                            ActionType.OPEN_PANEL,
                            key,
                            "PANEL \u00B7 ${key.uppercase(Locale.US)}".take(28),
                        ),
                    ),
                )
            }

        // Alarm.
        VoiceParsing.parseAlarm(clean)?.let { clock ->
            return answer(
                "ALARM $clock",
                "Alarm set for $clock.",
                listOf(PlannedStep(ActionType.SET_ALARM, clock, "ALARM $clock")),
            )
        }

        // Timer.
        VoiceParsing.parseTimerSeconds(clean)?.let { seconds ->
            return answer(
                "TIMER",
                "Timer set.",
                listOf(PlannedStep(ActionType.SET_TIMER, seconds.toString(), "TIMER")),
            )
        }

        // Speak this.
        if (clean.startsWith("say ")) {
            val text = heard.trim().substring(4).trim()
            return answer("SAY", text, listOf(PlannedStep(ActionType.SAY, text, "SAY")))
        }

        // Open an app.
        Regex("^(?:open|launch|start|run|khol)\\s+(.+)$").find(clean)?.let { match ->
            val target = match.groupValues[1].trim()
            if (target.contains("chat") || target.contains("message")) return@let
            val app = matchApp(context, target)
            if (app != null) {
                VoiceMemory.rememberAppAlias(context, target, app.packageName)
                return answer(
                    "OPEN ${app.label.uppercase(Locale.US)}".take(28),
                    "Opening ${app.label}.",
                    listOf(
                        PlannedStep(
                            ActionType.OPEN_APP,
                            "${app.packageName}/${app.activityName}",
                            "OPEN ${app.label.uppercase(Locale.US)}".take(28),
                        ),
                    ),
                )
            }
            return answer("APP NOT FOUND", "I could not find an app called $target.")
        }

        val hint = if (aiReady(context)) {
            "The AI did not answer, and that phrase is not one I match locally."
        } else {
            "Add a key in Settings \u2192 Intelligence so I understand free speech. Locally I handle call, message, open, torch, DND, volume, alarm, timer, photo and reminders."
        }
        return answer("NOT UNDERSTOOD", hint)
    }

    private suspend fun matchApp(context: Context, target: String): InstalledApp? {
        val needle = target.lowercase(Locale.US).trim()
        if (needle.isEmpty()) return null

        val apps = try {
            AppRepository.loadApps(context)
        } catch (t: Throwable) {
            return null
        }

        VoiceMemory.appAlias(context, needle)?.let { pkg ->
            apps.firstOrNull { it.packageName == pkg }?.let { return it }
        }

        apps.firstOrNull { it.sortKey == needle }?.let { return it }
        apps.firstOrNull { it.sortKey.startsWith(needle) }?.let { return it }
        apps.firstOrNull { it.packageName.lowercase(Locale.US).startsWith("com.$needle") }?.let { return it }
        apps.firstOrNull { it.sortKey.contains(needle) }?.let { return it }
        return null
    }
}
