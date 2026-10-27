package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ai.JsonReply
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.JarvisAccessibilityService
import com.jarvis.assistant.automation.ParamKind
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.data.PhraseIntent
import com.jarvis.assistant.data.Units
import com.jarvis.assistant.data.When
import com.jarvis.assistant.device.Access
import com.jarvis.assistant.device.AppLaunch
import com.jarvis.assistant.device.CalendarBridge
import com.jarvis.assistant.device.Files
import com.jarvis.assistant.device.HealthLog
import com.jarvis.assistant.device.Messaging
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.NotifyBrain
import com.jarvis.assistant.device.VoiceNotes
import com.jarvis.assistant.device.Whereabouts
import com.jarvis.assistant.device.readBattery
import com.jarvis.assistant.device.readNetwork
import com.jarvis.assistant.device.readStorage
import com.jarvis.assistant.data.InstalledApp
import org.json.JSONArray
import org.json.JSONObject
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

    suspend fun plan(context: Context, heard: String, previous: String? = null): VoicePlan {
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

        // Identity never leaves the phone: a model asked about itself names its provider, and this
        // answer is a fact about a person, not a question for a language model.
        if (Persona.isIdentityQuestion(spoken)) {
            val lang = Language.detect(spoken)
            SpokenLanguage.remember(context, lang)
            return VoicePlan(
                title = "IDENTITY",
                subtitle = "JARVIS",
                steps = emptyList(),
                say = Persona.answer(lang),
                source = "LOCAL",
            )
        }

        // A short follow-up ("do that for Amit", "and tomorrow") only means something next to the
        // previous turn, so it is completed before anything else looks at it.
        val completed = FollowUp.rewritten(FollowUp.last(context), spoken) ?: spoken

        if (aiReady(context)) {
            val viaAi = try {
                aiPlan(context, completed, previous)
            } catch (t: Throwable) {
                null
            }
            if (viaAi != null) return viaAi
        }

        return localPlan(context, completed, previous)
    }

    // ---------------------------------------------------------------------------------------------
    // AI path
    // ---------------------------------------------------------------------------------------------

    private suspend fun aiPlan(context: Context, heard: String, previous: String?): VoicePlan? {
        val system = systemPrompt(context, previous)
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
        val say = Persona.sanitize(json.optString("say").trim().take(MAX_SPOKEN))

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
                    steps.addAll(sendSteps(param, label))
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

        // The provider is deliberately not named in the UI or in speech: the operator asked that
        // JARVIS never discuss which key or service is behind him.
        val sourceLabel = "AI"

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

    private suspend fun systemPrompt(context: Context, previous: String?): String {
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
            - Answer in the SAME language the operator used. If they speak Hindi or Hinglish, write
              "say" in that language; never reply in English to a Hindi sentence.
            - You are JARVIS, built by Rishu Jaswar. Never mention a model, provider, API, key or
              vendor, and never call yourself an AI language model. If asked who built you, answer
              "Rishu Jaswar" only.
            - For a question a phone can answer from itself — distance, steps, water, a file, a sum,
              the time, battery — prefer the matching action over prose; it reads the real device.
            - If the request is a question, conversation, or something a phone cannot do, return
              "steps": [] and answer honestly in "say". Never claim an action you did not plan.
            - Never use shell or webhook. Never return more than $MAX_STEPS steps.
            - Keep "say" under 200 characters and do not describe every step; one sentence is enough.

            Installed apps (label — package):
            $installed

            Recent commands for context:
            $recents

            ${followUpRule(previous)}
        """.trimIndent()
    }

    /**
     * The multi-turn rule.
     *
     * A follow-up such as "again but quieter" or "do that for Divik" only makes sense next to what
     * was just asked, so the previous sentence is handed to the model explicitly rather than being
     * inferred from the recents list.
     */
    private fun followUpRule(previous: String?): String {
        if (previous.isNullOrBlank()) return "There is no previous command; treat this as the first turn."
        return """
            The operator's previous command was: "$previous"
            If the new sentence is a follow-up ("again", "but quieter", "do that for Divik", "turn it
            off", "what about tomorrow"), resolve it against that previous command and plan the action
            it implies. Do not ask for clarification when the previous command makes it obvious.
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

    /**
     * Messaging is deliberately *not* resolved here any more.
     *
     * "Khushi" may be a saved contact, a name that only exists in a WhatsApp chat, or a nickname the
     * operator used once. [com.jarvis.assistant.device.Messaging] resolves all three and decides
     * between replying to a live notification and opening the chat by link, so the router only has
     * to pass the words through in one step.
     */
    private fun sendSteps(param: String, label: String): List<PlannedStep> {
        val parts = param.split('|')
        val app = parts.getOrNull(0)?.trim()?.lowercase(Locale.US).orEmpty().ifBlank { "whatsapp" }
        val contact = parts.getOrNull(1)?.trim().orEmpty()
        val text = parts.getOrNull(2)?.trim().orEmpty()

        if (contact.isBlank()) {
            return listOf(PlannedStep(ActionType.SAY, "I did not catch who to message.", "NO CONTACT"))
        }

        val who = contact.uppercase(Locale.US).take(18)
        return listOf(
            PlannedStep(
                ActionType.MESSAGE,
                "$app|$contact|$text",
                if (label.isBlank()) "MESSAGE \u00B7 $who".take(28) else label.take(28),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Local path — silent fallback, no menu anywhere in the UI
    // ---------------------------------------------------------------------------------------------

    private suspend fun localPlan(context: Context, heard: String, previous: String? = null): VoicePlan {
        val lang = Language.detect(heard)
        SpokenLanguage.remember(context, lang)

        fun answer(
            title: String,
            say: String,
            steps: List<PlannedStep> = emptyList(),
            subtitle: String = "",
        ) = VoicePlan(title, subtitle, steps, say, "LOCAL")

        fun said(
            title: String,
            key: String,
            vararg args: Any,
            steps: List<PlannedStep> = emptyList(),
            subtitle: String = "",
        ) = answer(title, Language.say(lang, key, *args), steps, subtitle)

        fun step(action: ActionType, param: String, label: String) =
            PlannedStep(action, param, label)

        fun upper(value: String, limit: Int = 28) = value.uppercase(Locale.US).take(limit)

        val clean = VoiceParsing.normalise(heard)
        if (clean.isBlank()) return said("NO INPUT", "dont_know")

        val hit = PhraseBook.match(clean)

        // ---- stop / cancel / repeat ------------------------------------------------------------
        if (hit?.intent == PhraseIntent.STOP) {
            val cancelled = when (lang.replyLang) {
                Lang.HI -> "रद्द कर दिया।"
                Lang.HINGLISH -> "Cancel kar diya."
                else -> "Cancelled."
            }
            return answer("CANCELLED", cancelled, subtitle = "IDLE")
        }

        if (hit?.intent == PhraseIntent.REPEAT) {
            val last = VoiceMemory.lastCommand(context)
                ?: return said("NOTHING TO REPEAT", "dont_know")
            return localPlan(context, last, null)
        }

        // ---- who are you ------------------------------------------------------------------------
        // Answered here, never by the model: the builder's name is not a matter of opinion, and the
        // reply must never mention a provider, a key or a model.
        if (hit?.intent == PhraseIntent.IDENTITY) {
            return answer("IDENTITY", Persona.answer(lang), subtitle = "JARVIS")
        }

        // People speak in chains; flatten before parsing.
        val spoken = VoiceParsing.flatten(clean)

        // ---- messaging --------------------------------------------------------------------------
        VoiceParsing.parseMessage(spoken)?.let { (app, contact, text) ->
            val who = contact.trim()
            if (who.isBlank()) {
                return said("MESSAGE", "who_reply", "contact", "", subtitle = "MESSAGE")
            }
            // Resolve the person before deciding *how* to reach them. Three cases, in order of
            // reliability: a live chat to answer, a number to open, or nothing at all — in which
            // case JARVIS searches inside the app rather than refusing.
            val people = try {
                Messaging.resolve(context, who)
            } catch (t: Throwable) {
                emptyList()
            }
            val reachable = people.any { !it.number.isNullOrBlank() } ||
                NotifyBrain.findReplyTarget(who) != null

            if (!reachable && text.isNotBlank()) {
                val screenReady = AccessibilityStatus.isServiceEnabled(context)
                return if (screenReady) {
                    VoicePlan(
                        title = "FIND ${upper(who, 16)}",
                        subtitle = "SCREEN TASK",
                        steps = listOf(
                            step(
                                ActionType.OPEN_APP,
                                if (app == "telegram") "org.telegram.messenger" else "com.whatsapp",
                                "OPEN ${app.uppercase(Locale.US)}",
                            ),
                        ),
                        say = "I have no number for $who, so I am searching inside the app.",
                        source = "LOCAL",
                        screenGoal = "search for $who, open the chat, type \"$text\" and press send",
                    )
                } else {
                    VoicePlan(
                        title = "NO NUMBER",
                        subtitle = "MESSAGE",
                        steps = emptyList(),
                        say = "I have no number for $who. Save them in contacts, or turn on " +
                            "screen access in Advanced and I will search inside the app myself.",
                        source = "LOCAL",
                    )
                }
            }

            return answer(
                "MESSAGE \u00B7 ${upper(who, 16)}",
                Language.say(lang, "message", who),
                listOf(step(ActionType.MESSAGE, "$app|$who|$text", "MESSAGE ${upper(who, 14)}")),
                "MESSAGE",
            )
        }

        // ---- calling ----------------------------------------------------------------------------
        Regex("^(?:call|phone|dial|ring)\\s+(?:to\\s+)?(.+)$").find(clean)?.let { match ->
            val who = match.groupValues[1].trim()
            if (who.isNotBlank() && who !in setOf("me", "back")) {
                return said(
                    "CALL",
                    "calling",
                    who,
                    steps = listOf(step(ActionType.CALL_CONTACT, who, "CALL ${upper(who, 20)}")),
                )
            }
        }

        // ---- reminders --------------------------------------------------------------------------
        VoiceParsing.parseReminder(clean)?.let { ask ->
            val payload = "${ask.inSeconds ?: 0}|${ask.atClock.orEmpty()}|${ask.text}"
            return said(
                "REMINDER",
                "reminder",
                steps = listOf(step(ActionType.REMINDER, payload, "SET REMINDER")),
            )
        }

        // ---- camera -----------------------------------------------------------------------------
        VoiceParsing.cameraAsk(clean)?.let { ask ->
            val steps = mutableListOf(step(ActionType.CAPTURE_PHOTO, "", "TAKE PHOTO"))
            if (ask.showNewest) {
                steps.add(step(ActionType.WAIT, "1", "WAIT"))
                steps.add(step(ActionType.OPEN_LAST_PHOTO, "", "SHOW PHOTO"))
            }
            return said(
                if (ask.showNewest) "PHOTO \u00B7 SHOW" else "PHOTO",
                if (ask.showNewest) "photo" else "photo",
                steps = steps,
            )
        }

        // ---- alarm / timer / volume / translate / download ---------------------------------------
        VoiceParsing.parseAlarm(clean)?.let { clock ->
            return said(
                "ALARM $clock",
                "alarm",
                clock,
                steps = listOf(step(ActionType.SET_ALARM, clock, "ALARM $clock")),
            )
        }

        VoiceParsing.parseTimerSeconds(clean)?.let { seconds ->
            return said(
                "TIMER",
                "timer",
                steps = listOf(step(ActionType.SET_TIMER, seconds.toString(), "TIMER")),
            )
        }

        Regex("\\b(?:download|save)\\s+(https?://\\S+)").find(clean)?.let { match ->
            return answer(
                "DOWNLOAD",
                "Downloading.",
                listOf(step(ActionType.DOWNLOAD, match.groupValues[1], "DOWNLOAD")),
            )
        }

        Regex("translate\\s+(.+?)\\s+(?:to|in|into)\\s+([a-z ]{3,20})$").find(clean)?.let { match ->
            val text = heard.trim()
                .replace(Regex("(?i)^translate\\s+"), "")
                .substringBeforeLast(" to ", "")
                .substringBeforeLast(" in ", "")
                .trim()
            val target = match.groupValues[2].trim()
            return answer(
                "TRANSLATE",
                "Translating to $target.",
                listOf(step(ActionType.TRANSLATE, "$target|$text", "TRANSLATE")),
            )
        }

        // ---- "find my aadhaar pdf" ---------------------------------------------------------------
        filesQueryOf(clean)?.let { query ->
            val found = Files.search(context, query)
            return when {
                found.isEmpty() && !Files.hasAllFiles(context) && !Access.hasAll(context, Access.MEDIA_PERMISSIONS) ->
                    said("FIND FILE", "file_permission", subtitle = "FILES")

                found.isEmpty() -> said("FIND FILE", "file_none", query, subtitle = "FILES")

                found.size == 1 -> answer(
                    "FILE \u00B7 ${upper(found.first().name, 20)}",
                    Language.say(lang, "file_found", 1, found.first().name),
                    listOf(step(ActionType.OPEN_FILE, found.first().name, "OPEN FILE")),
                    "FILES",
                )

                else -> answer(
                    "${found.size} FILES",
                    found.joinToString(" \u00B7 ") { it.name },
                    subtitle = "FILES",
                )
            }
        }

        // ---- "how far is India Gate" --------------------------------------------------------------
        distanceTargetOf(clean)?.let { place ->
            if (!Whereabouts.hasPermission(context)) {
                return said("DISTANCE", "location_permission", subtitle = "LOCATION")
            }
            val fix = Whereabouts.currentFix(context)
                ?: return answer(
                    "DISTANCE",
                    "I cannot get a location fix. Turn location on, then ask me again.",
                    subtitle = "LOCATION",
                )
            val destination = Whereabouts.geocode(context, place)
                ?: return said("DISTANCE", "distance_unknown", place, subtitle = "LOCATION")

            val road = if (Whereabouts.roadRoutingEnabled(context)) {
                Whereabouts.roadKm(fix, destination)
            } else {
                null
            }
            val km = road ?: Whereabouts.haversineKm(
                fix.latitude,
                fix.longitude,
                destination.first,
                destination.second,
            )
            val spoken = if (road != null) {
                Language.say(lang, "distance_road", place, Whereabouts.formatKm(km))
            } else {
                Language.say(lang, "distance_line", place, Whereabouts.formatKm(km))
            }
            return answer("DISTANCE \u00B7 ${upper(place, 16)}", spoken, subtitle = "LOCATION")
        }

        // ---- "where am I" ------------------------------------------------------------------------
        if (hit?.intent == PhraseIntent.WHERE_AM_I) {
            if (!Whereabouts.hasPermission(context)) {
                return said("LOCATION", "location_permission", subtitle = "LOCATION")
            }
            val fix = Whereabouts.currentFix(context)
                ?: return answer("LOCATION", "I cannot get a location fix right now.", subtitle = "LOCATION")
            val place = Whereabouts.describe(context, fix)
                ?: String.format(Locale.US, "%.4f, %.4f", fix.latitude, fix.longitude)
            return answer("LOCATION", place, subtitle = "LOCATION")
        }

        // ---- "take me to ..." --------------------------------------------------------------------
        navigateTargetOf(clean)?.let { place ->
            val coordinates = Whereabouts.geocode(context, place)
            if (coordinates == null) {
                return said("NAVIGATE", "distance_unknown", place, subtitle = "NAVIGATION")
            }
            return answer(
                "NAVIGATE \u00B7 ${upper(place, 14)}",
                Language.say(lang, "opening", "directions to $place"),
                listOf(step(ActionType.NAVIGATE, place, "NAVIGATE")),
                "NAVIGATION",
            )
        }

        // ---- arithmetic and units, offline --------------------------------------------------------
        if (hit?.intent == PhraseIntent.CALCULATE || looksLikeMath(clean)) {
            Units.tryCalculate(clean)?.let { outcome ->
                return said("CALCULATE", "calc", outcome.text, subtitle = "MATH")
            }
        }

        // ---- the phrase table ---------------------------------------------------------------------
        when (hit?.intent) {
            PhraseIntent.TORCH -> {
                val off = clean.contains("off") || clean.contains("band") ||
                    clean.contains("close") || clean.contains("bujha")
                val action = if (off) ActionType.TORCH_OFF else ActionType.TORCH_ON
                return said(
                    if (off) "TORCH OFF" else "TORCH ON",
                    if (off) "torch_off" else "torch_on",
                    steps = listOf(step(action, "", action.label)),
                )
            }

            PhraseIntent.DND -> {
                val off = clean.contains("off") || clean.contains("disable") ||
                    clean.contains("band") || clean.contains("chalu")
                val param = if (off) "off" else "silent"
                return said(
                    if (off) "DND OFF" else "DND ON",
                    if (off) "dnd_off" else "dnd_on",
                    steps = listOf(step(ActionType.SET_DND, param, if (off) "DND OFF" else "DND ON")),
                )
            }

            PhraseIntent.SCREENSHOT -> return said(
                "SCREENSHOT",
                "screenshot",
                steps = listOf(step(ActionType.SCREENSHOT, "", "SCREENSHOT")),
            )

            PhraseIntent.PHOTO -> return said(
                "PHOTO",
                "photo",
                steps = listOf(step(ActionType.CAPTURE_PHOTO, "", "TAKE PHOTO")),
            )

            PhraseIntent.LAST_PHOTO -> return said(
                "PHOTO \u00B7 LAST",
                "opening",
                "your last photo",
                steps = listOf(step(ActionType.OPEN_LAST_PHOTO, "", "SHOW PHOTO")),
            )

            PhraseIntent.GALLERY -> return said(
                "GALLERY",
                "opening",
                "the gallery",
                steps = listOf(step(ActionType.OPEN_GALLERY, "", "OPEN GALLERY")),
            )

            PhraseIntent.STEPS -> {
                val steps = HealthLog.stepsToday(context)
                return if (steps == null) {
                    said("STEPS", "steps_none", subtitle = "HEALTH")
                } else {
                    said("STEPS", "steps", steps, subtitle = "HEALTH")
                }
            }

            PhraseIntent.WATER -> {
                val total = HealthLog.addWater(context, waterAmountOf(clean))
                return said("WATER", "water", total, subtitle = "HEALTH")
            }

            PhraseIntent.HEART -> {
                if (!HealthLog.hasHeartSensor(context)) {
                    return said("HEART", "heart_none", subtitle = "HEALTH")
                }
                val bpm = HealthLog.heartRate(context)
                return if (bpm == null) {
                    answer("HEART", "The sensor did not report a rate.", subtitle = "HEALTH")
                } else {
                    said("HEART", "heart", bpm, subtitle = "HEALTH")
                }
            }

            PhraseIntent.SLEEP -> {
                val minutes = HealthLog.sleepLastNight(context)
                return if (minutes == null) {
                    answer("SLEEP", "No night recorded yet.", subtitle = "HEALTH")
                } else {
                    val hours = String.format(Locale.US, "%.1f hours", minutes / 60.0)
                    said("SLEEP", "sleep", hours, subtitle = "HEALTH")
                }
            }

            PhraseIntent.HEALTH -> {
                val steps = HealthLog.stepsToday(context)
                val water = HealthLog.waterToday(context)
                val parts = mutableListOf<String>()
                if (steps != null) {
                    parts.add(Language.say(lang, "steps", steps))
                    parts.add(String.format(Locale.US, "%.2f km.", HealthLog.distanceKm(steps)))
                }
                parts.add(Language.say(lang, "water", water))
                return answer("HEALTH", parts.joinToString(" "), subtitle = "HEALTH")
            }

            PhraseIntent.NOTIF_MISSED, PhraseIntent.NOTIF_READ -> {
                val limit = if (hit?.intent == PhraseIntent.NOTIF_READ) 1 else 5
                val items = NotifyBrain.lastSummary(context, limit)
                if (items.isEmpty()) return said("ALERTS", "messages_none", subtitle = "ALERTS")

                val lines = items.map { item ->
                    val verdict = NotifyBrain.classify(
                        item.packageName,
                        item.appLabel,
                        item.title,
                        item.text,
                        hasReplyAction = false,
                    )
                    NotifyBrain.announceLine(verdict, item.appLabel, item.title, item.text, lang)
                }
                return answer(
                    if (lines.size == 1) "ALERT" else "${lines.size} ALERTS",
                    lines.joinToString(" "),
                    subtitle = "ALERTS",
                )
            }

            PhraseIntent.REPLY -> {
                val target = NotifyBrain.findReplyTarget(replyTargetOf(clean))
                    ?: NotifyBrain.replyableNewestFirst().firstOrNull()
                    ?: return answer("REPLY", "There is no message waiting for a reply.", subtitle = "REPLY")

                val who = NotifyBrain.senderOf(target).ifBlank { "them" }
                val body = replyBodyOf(clean)
                if (body.isBlank()) {
                    val options = NotifyBrain.suggestions(NotifyBrain.textOf(target), who, lang)
                    return answer(
                        "REPLY \u00B7 ${upper(who, 14)}",
                        options.joinToString(" \u00B7 "),
                        subtitle = "SUGGESTIONS",
                    )
                }
                return answer(
                    "REPLY \u00B7 ${upper(who, 14)}",
                    Language.say(lang, "reply_sent", who),
                    listOf(step(ActionType.MESSAGE_REPLY, "$who|$body", "REPLY ${upper(who, 14)}")),
                    "REPLY",
                )
            }

            PhraseIntent.MEDIA_PLAY -> return answer(
                "PLAY",
                "Playing.",
                listOf(step(ActionType.PLAY_MEDIA, "", "PLAY")),
            )

            PhraseIntent.MEDIA_PAUSE -> return answer(
                "PAUSE",
                "Paused.",
                listOf(step(ActionType.PAUSE_MEDIA, "", "PAUSE")),
            )

            PhraseIntent.MEDIA_NEXT -> return answer(
                "NEXT TRACK",
                "Next.",
                listOf(step(ActionType.NEXT_MEDIA, "", "NEXT")),
            )

            PhraseIntent.MEDIA_PREVIOUS -> return answer(
                "PREVIOUS TRACK",
                "Going back.",
                listOf(step(ActionType.PREVIOUS_MEDIA, "", "PREVIOUS")),
            )

            PhraseIntent.VOLUME_UP -> return said(
                "VOLUME UP",
                "volume",
                80,
                steps = listOf(step(ActionType.SET_VOLUME, "80", "VOLUME 80")),
            )

            PhraseIntent.VOLUME_DOWN -> return said(
                "VOLUME DOWN",
                "volume",
                25,
                steps = listOf(step(ActionType.SET_VOLUME, "25", "VOLUME 25")),
            )

            PhraseIntent.VOLUME_SET -> {
                VoiceParsing.parseVolume(clean)?.let { level ->
                    return said(
                        "VOLUME $level",
                        "volume",
                        level,
                        steps = listOf(step(ActionType.SET_VOLUME, level.toString(), "VOLUME $level")),
                    )
                }
            }

            PhraseIntent.LOCK_SCREEN -> return answer(
                "LOCK",
                "Locking.",
                listOf(step(ActionType.GLOBAL_ACTION, "lock", "LOCK")),
            )

            PhraseIntent.HOME -> return answer(
                "HOME",
                "Home.",
                listOf(step(ActionType.GLOBAL_ACTION, "home", "HOME")),
            )

            PhraseIntent.BACK -> return answer(
                "BACK",
                "Back.",
                listOf(step(ActionType.GLOBAL_ACTION, "back", "BACK")),
            )

            PhraseIntent.RECENTS -> return answer(
                "RECENTS",
                "Recent apps.",
                listOf(step(ActionType.GLOBAL_ACTION, "recents", "RECENTS")),
            )

            PhraseIntent.SHADE -> return answer(
                "ALERTS",
                "Opening notifications.",
                listOf(step(ActionType.GLOBAL_ACTION, "notifications", "SHADE")),
            )

            PhraseIntent.QUICK_SETTINGS -> return answer(
                "QUICK SETTINGS",
                "Opening quick settings.",
                listOf(step(ActionType.GLOBAL_ACTION, "quick", "QUICK")),
            )

            PhraseIntent.BATTERY -> {
                val percent = try {
                    readBattery(context).percent
                } catch (t: Throwable) {
                    0
                }
                return said("BATTERY", "battery", percent, subtitle = "STATUS")
            }

            PhraseIntent.STORAGE -> {
                val storage = try {
                    readStorage(context)
                } catch (t: Throwable) {
                    null
                }
                return if (storage == null) {
                    answer("STORAGE", "I could not read the storage.", subtitle = "STATUS")
                } else {
                    answer(
                        "STORAGE",
                        "${storage.usedLabel} used, ${storage.freeLabel} free.",
                        subtitle = "STATUS",
                    )
                }
            }

            PhraseIntent.NETWORK -> {
                val network = try {
                    readNetwork(context)
                } catch (t: Throwable) {
                    null
                }
                val label = network?.let { if (it.connected) it.transport else "offline" } ?: "offline"
                return answer("NETWORK", "Network: $label.", subtitle = "STATUS")
            }

            PhraseIntent.TIME -> {
                val clock = try {
                    java.text.SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date())
                } catch (t: Throwable) {
                    ""
                }
                return said("TIME", "time", clock, subtitle = "CLOCK")
            }

            PhraseIntent.DATE -> {
                val date = try {
                    java.text.SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(java.util.Date())
                } catch (t: Throwable) {
                    ""
                }
                return answer("DATE", date, subtitle = "CLOCK")
            }

            PhraseIntent.SYSTEM_SETTINGS -> return said(
                "SETTINGS",
                "opening",
                "settings",
                steps = listOf(step(ActionType.SETTINGS_PAGE, "settings", "OPEN SETTINGS")),
            )

            PhraseIntent.READ_SCREEN -> {
                if (!AccessibilityStatus.isServiceEnabled(context)) {
                    return answer(
                        "READ SCREEN",
                        "Screen access is off. Turn it on in Advanced and I can read what is on the screen.",
                        subtitle = "SCREEN",
                    )
                }
                val labels = JarvisAccessibilityService.visibleLabels(limit = 20)
                return if (labels.isEmpty()) {
                    said("READ SCREEN", "no_screen", subtitle = "SCREEN")
                } else {
                    answer("READ SCREEN", labels.joinToString(", ").take(400), subtitle = "SCREEN")
                }
            }

            PhraseIntent.CALENDAR -> {
                if (!CalendarBridge.canRead(context)) {
                    return said("CALENDAR", "calendar_permission", subtitle = "CALENDAR")
                }
                val mode = when {
                    clean.contains("next") || clean.contains("agla") -> "next"
                    clean.contains("week") || clean.contains("hafte") -> "week"
                    else -> "today"
                }
                val events = when (mode) {
                    "next" -> CalendarBridge.next(context)?.let { listOf(it) }
                    "week" -> CalendarBridge.upcoming(context, days = 7, limit = 5)
                    else -> CalendarBridge.today(context)
                }.orEmpty()

                if (events.isEmpty()) {
                    return said("CALENDAR", "calendar_none", "", subtitle = "CALENDAR")
                }
                if (mode == "next" && events.size == 1) {
                    val event = events.first()
                    return answer(
                        "NEXT \u00B7 ${upper(event.title, 16)}",
                        Language.say(
                            lang,
                            "calendar_next",
                            event.title,
                            CalendarBridge.clockOf(event.startMillis),
                        ),
                        subtitle = "CALENDAR",
                    )
                }
                val listing = events.joinToString("; ") { event ->
                    "${event.title} \u00B7 ${CalendarBridge.clockOf(event.startMillis)}"
                }
                return answer("CALENDAR \u00B7 ${events.size}", listing, subtitle = "CALENDAR")
            }

            PhraseIntent.CALENDAR_ADD -> {
                if (!CalendarBridge.canWrite(context)) {
                    return said("ADD EVENT", "calendar_permission", subtitle = "CALENDAR")
                }
                // "add meeting with Amit tomorrow at 4" -> title, then the time words.
                val title = calendarTitleOf(heard)
                val parsed = When.parse(heard)
                if (parsed == null) {
                    return said("ADD EVENT", "calendar_no_time", subtitle = "CALENDAR")
                }
                return answer(
                    "ADD \u00B7 ${upper(title, 16)}",
                    Language.say(lang, "calendar_added", "$title ${When.spoken(parsed.at).lowercase(Locale.US)}"),
                    listOf(
                        step(
                            ActionType.CALENDAR_ADD,
                            "${parsed.at}|$title",
                            "ADD EVENT",
                        ),
                    ),
                    "CALENDAR",
                )
            }

            PhraseIntent.CONTACT_ADD -> {
                val name = contactNameOf(heard)
                val number = contactNumberOf(heard)
                if (name.isNullOrBlank() || number.isNullOrBlank()) {
                    return answer(
                        "SAVE CONTACT",
                        "Tell me the number and the name: \"save 98765 43210 as Amit\".",
                        subtitle = "CONTACTS",
                    )
                }
                return answer(
                    "SAVE \u00B7 ${upper(name, 16)}",
                    Language.say(lang, "contact_saved", name),
                    listOf(step(ActionType.CONTACT_ADD, "$name|$number", "SAVE CONTACT")),
                    "CONTACTS",
                )
            }

            PhraseIntent.VOICE_NOTE -> {
                if (!VoiceNotes.canRecord(context)) {
                    return answer(
                        "VOICE NOTE",
                        "Microphone access is off. Allow it in Advanced and I can record.",
                        subtitle = "NOTES",
                    )
                }
                val seconds = noteSecondsOf(clean)
                return answer(
                    "RECORD \u00B7 ${seconds}S",
                    Language.say(lang, "note_recording", seconds),
                    listOf(step(ActionType.RECORD_NOTE, seconds.toString(), "RECORD NOTE")),
                    "NOTES",
                )
            }

            PhraseIntent.HELP -> return answer("HELP", helpText(context, lang), subtitle = "JARVIS")

            PhraseIntent.CALL -> return answer(
                "CALL",
                "Opening the dialer.",
                listOf(step(ActionType.OPEN_APP, "dialer", "DIALER")),
                "CALL",
            )

            PhraseIntent.MESSAGE -> return answer(
                "MESSAGE",
                "Opening messages.",
                listOf(step(ActionType.OPEN_APP, "messages", "MESSAGES")),
                "MESSAGE",
            )

            PhraseIntent.LAUNCHER -> return answer(
                "APPS",
                "Say \u201copen\u201d and the app name, or open the drawer from the home screen.",
                subtitle = "HINT",
            )

            PhraseIntent.OPEN_APP -> {
                val target = openTargetOf(clean)
                if (!target.isNullOrBlank()) return openAnything(context, target, lang)
            }

            else -> Unit
        }

        // ---- "open <anything>" ---------------------------------------------------------------------
        openTargetOf(clean)?.let { target ->
            if (target.isNotBlank()) return openAnything(context, target, lang)
        }

        // ---- never a dead end ----------------------------------------------------------------------
        val appGuess = AppLaunch.closestLabels(context, clean).firstOrNull()
        if (appGuess != null) {
            return said("DID YOU MEAN", "which_app", appGuess, subtitle = "SUGGESTION")
        }

        return said("NOT UNDERSTOOD", "dont_know", subtitle = "HINT")
    }

    /** Opens an app, a system surface or an ambiguous name, and says what happened. */
    private suspend fun openAnything(context: Context, target: String, lang: Lang): VoicePlan =
        when (val outcome = AppLaunch.open(context, target)) {
            is AppLaunch.Outcome.Opened -> VoicePlan(
                title = "OPEN ${outcome.label.uppercase(Locale.US).take(20)}",
                subtitle = "LAUNCH",
                steps = emptyList(),
                say = Language.say(lang, "opening", outcome.label),
                source = "LOCAL",
            )

            is AppLaunch.Outcome.Failed -> VoicePlan(
                title = "CANNOT OPEN",
                subtitle = "LAUNCH",
                steps = emptyList(),
                say = Language.say(lang, "open_failed", target),
                source = "LOCAL",
            )

            is AppLaunch.Outcome.Unknown -> {
                val guess = outcome.suggestions.firstOrNull()
                if (guess == null) {
                    VoicePlan(
                        title = "NOT FOUND",
                        subtitle = "LAUNCH",
                        steps = emptyList(),
                        say = Language.say(lang, "not_found_app", target, helpText(context, lang)),
                        source = "LOCAL",
                    )
                } else {
                    VoicePlan(
                        title = "DID YOU MEAN",
                        subtitle = "SUGGESTION",
                        steps = emptyList(),
                        say = Language.say(lang, "which_app", guess),
                        source = "LOCAL",
                    )
                }
            }
        }

    /** "what can you do" — answered from what this phone actually has. */
    private fun helpText(context: Context, lang: Lang): String {
        val hasSteps = HealthLog.hasStepSensor(context)
        val hasNotifications = NotificationRepository.hasAccess(context)
        val hasFiles = Files.hasAllFiles(context)
        return when (lang.replyLang) {
            Lang.HI -> buildString {
                append("गैलरी खोलो, किसी को संदेश भेजो, कॉल करो, फ़ोटो लो, ")
                append("दूरी बताओ, कैलकुलेट करो")
                if (hasSteps) append(", कदम बताओ")
                if (hasNotifications) append(", और पूछो क्या मिस किया")
                if (hasFiles) append("। मैं सारी फ़ाइलें भी ढूँढ सकता हूँ।")
            }
            Lang.HINGLISH -> buildString {
                append("Gallery kholo, kisi ko message bhejo, call karo, photo lo, ")
                append("distance batao, calculate karo")
                if (hasSteps) append(", steps batao")
                if (hasNotifications) append(", ya pucho kya miss kiya")
                if (hasFiles) append(". Main saari files bhi dhundh sakta hoon.")
            }
            else -> buildString {
                append("I can open any app or gallery, message or call someone, take a photo, ")
                append("measure distance, do the maths")
                if (hasSteps) append(", count your steps")
                if (hasNotifications) append(", and tell you what you missed")
                if (hasFiles) append(". I can search every file on this phone too.")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Slot extraction — the words around a request, never the model's guesswork
    // ---------------------------------------------------------------------------------------------

    /** "add meeting with Amit tomorrow at 4" -> "Meeting with Amit". */
    private fun calendarTitleOf(heard: String): String {
        var text = heard.trim()
        text = text.replace(
            Regex("(?i)^(?:add|create|schedule|book|new)\\s+(?:an?\\s+)?(?:event|meeting|appointment)\\s*"),
            "",
        )
        text = text.replace(Regex("(?i)^(?:calendar mein add karo|meeting rakho)\\s*"), "")
        text = text.replace(
            Regex(
                "(?i)\\b(?:today|tomorrow|tonight|aaj|kal|parso|day after tomorrow|next week)\\b.*$",
            ),
            "",
        )
        text = text.replace(Regex("(?i)\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\b.*$"), "")
        text = text.replace(Regex("(?i)\\s+\\d{1,2}\\s*(?:am|pm|baje).*$"), "")
        text = text.replace(Regex("\\s+"), " ").trim().trimEnd(',', '.')
        if (text.isBlank()) return "Event"
        return text.replaceFirstChar { it.uppercaseChar() }.take(80)
    }

    private fun contactNameOf(heard: String): String? =
        Regex("(?i)\\bas\\s+([\\p{L}][\\p{L} .]{1,40})$").find(heard.trim())
            ?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("(?i)\\b(?:name is|naam)\\s+([\\p{L}][\\p{L} .]{1,40})$").find(heard.trim())
                ?.groupValues?.getOrNull(1)?.trim()

    private fun contactNumberOf(heard: String): String? =
        Regex("(\\+?\\d[\\d \\-]{5,18}\\d)").find(heard)?.groupValues?.getOrNull(1)
            ?.filter { it.isDigit() || it == '+' }
            ?.takeIf { it.count { ch -> ch.isDigit() } >= 6 }

    private fun noteSecondsOf(clean: String): Int {
        val match = Regex("(\\d{1,4})\\s*(?:seconds?|secs?|minutes?|mins?)").find(clean)
        if (match != null) {
            val value = match.groupValues[1].toIntOrNull() ?: 60
            val isMinutes = clean.contains("minute") || clean.contains("min")
            return (if (isMinutes) value * 60 else value).coerceIn(2, VoiceNotes.MAX_SECONDS)
        }
        return VoiceNotes.DEFAULT_SECONDS
    }

    private fun openTargetOf(clean: String): String? {
        val pattern = Regex("^(?:open|launch|start|run|khol|kholo|khol do|open up)\\s+(.+)$")
        val raw = pattern.find(clean)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val target = raw
            .removePrefix("the ")
            .removePrefix("my ")
            .removeSuffix(" app")
            .removeSuffix(" please")
            .trim()
        return target.ifBlank { null }
    }

    private fun filesQueryOf(clean: String): String? {
        val prefixes = listOf(
            "find my file named ", "find the file named ", "find a file called ", "find file ",
            "search my files for ", "search for the file ", "search file ", "search for ",
            "locate the file ", "locate file ", "locate ", "where is my ", "where is file ",
            "file dhundo ", "file dhoondo ", "dhundo ", "dhoondo ", "find my ", "find ",
        )
        val prefix = prefixes.firstOrNull { clean.startsWith(it) } ?: return null
        var rest = clean.removePrefix(prefix).trim()
        rest = rest.removePrefix("my ").removePrefix("the ").removePrefix("a ").trim()
        rest = rest.removeSuffix(" file").removeSuffix(" please").trim()
        if (rest.isBlank()) return null

        val documentWords = listOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "sheet", "photo", "image", "video",
            "mp3", "mp4", "zip", "apk", "file", "download", "aadhaar", "aadhar", "pan card",
            "invoice", "receipt", "ticket", "resume", "certificate", "screenshot",
        )
        val fileish = documentWords.any { rest.contains(it) } ||
            prefix.contains("file") || prefix.contains("dhundo") || prefix.contains("dhoondo")
        return if (fileish) rest else null
    }

    private fun distanceTargetOf(clean: String): String? {
        val prefixes = listOf(
            "how far is it from here to ", "how far is it to ", "how far away is ",
            "how far is ", "distance from here to ", "distance from my location to ",
            "distance to ", "distance between here and ", "kitna door hai ", "kitna door ",
            "kitni doori hai ", "doori ", "how far ",
        )
        val prefix = prefixes.firstOrNull { clean.startsWith(it) } ?: return null
        var rest = clean.removePrefix(prefix).trim()
        rest = rest.removePrefix("the ").removePrefix("my ").trim()
        rest = rest.removeSuffix(" from here").removeSuffix(" se").removeSuffix(" hai").trim()
        rest = rest.removePrefix("to ").trim()
        if (rest.isBlank()) return null
        // "how far is my battery" is not a distance question.
        if (rest in setOf("battery", "storage", "internet")) return null
        return rest
    }

    private fun navigateTargetOf(clean: String): String? {
        val prefixes = listOf(
            "navigate to ", "navigate ", "take me to ", "directions to ", "drive me to ",
            "guide me to ", "how do i get to ", "raasta ", "le chalo ",
        )
        val prefix = prefixes.firstOrNull { clean.startsWith(it) } ?: return null
        var rest = clean.removePrefix(prefix).trim()
        rest = rest.removePrefix("the ").removePrefix("my ").trim()
        return rest.ifBlank { null }
    }

    private fun replyTargetOf(clean: String): String {
        val match = Regex("^reply\\s+(?:to\\s+)?([^:]+?)(?:\\s+(?:with|saying|saying:|:)\\s+.*)?$")
            .find(clean) ?: return ""
        return match.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    private fun replyBodyOf(clean: String): String {
        val marker = Regex("\\b(?:with|saying|bolo|kehdo)\\b|:")
        val parts = marker.split(clean, limit = 2)
        return if (parts.size == 2) parts[1].trim() else ""
    }

    private fun waterAmountOf(clean: String): Int = when {
        clean.contains("bottle") -> 750
        clean.contains("glass") || clean.contains("gilas") -> 250
        else -> 250
    }

    private fun looksLikeMath(clean: String): Boolean {
        val digits = clean.count { it.isDigit() }
        val operators = clean.count { it in "+-*/^%" } +
            (if (clean.contains(" plus ") || clean.contains(" minus ")) 1 else 0) +
            (if (clean.contains(" times ") || clean.contains(" into ")) 1 else 0)
        return digits >= 2 && operators >= 1
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
