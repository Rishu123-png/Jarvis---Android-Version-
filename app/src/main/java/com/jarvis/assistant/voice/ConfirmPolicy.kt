package com.jarvis.assistant.voice

import com.jarvis.assistant.automation.ActionType
import java.util.Locale

/**
 * The only things that wait for a yes.
 *
 * Reversible device controls run immediately. Calls, messages, records, destructive shell commands,
 * and every screen-agent goal wait for a yes: a misheard word must not reach another person, write
 * data, wipe something, or start interacting with an app.
 *
 * The check is on the planned step or screen goal, before anything runs, so the question is asked
 * once and the plan is stored whole. There is no second guess after the yes.
 */
object ConfirmPolicy {

    /** Irreversible by nature: they reach another person or change the phone's records. */
    private val ALWAYS_ASK = setOf(
        ActionType.CALL_CONTACT,
        ActionType.DIAL_NUMBER,
        ActionType.MESSAGE,
        ActionType.MESSAGE_REPLY,
        ActionType.CONTACT_ADD,
        ActionType.CALENDAR_ADD,
    )

    /** Shell is allowed in full, but these words cannot be undone. */
    private val FINAL_SHELL = listOf(
        "master_clear",
        "master clear",
        "pm clear",
        "reboot",
        "fastboot",
        "recovery",
        "rm -rf",
        "format ",
        "factory",
        "wipe",
        "uninstall",
    )

    fun needsAsking(action: ActionType, param: String): Boolean =
        action in ALWAYS_ASK || (action == ActionType.SHELL && shellIsFinal(param))

    fun shellIsFinal(command: String): Boolean {
        val text = command.lowercase(Locale.US)
        return FINAL_SHELL.any { text.contains(it) }
    }

    /** The first step that needs a yes, if any. */
    fun firstAskable(steps: List<PlannedStep>): PlannedStep? =
        steps.firstOrNull { needsAsking(it.action, it.param) }

    /** Screen automation can type, submit or send, so every screen goal needs explicit approval. */
    fun needsScreenConfirmation(screenGoal: String?): Boolean = !screenGoal.isNullOrBlank()

    /** States the actual screen goal so the yes is informed, not a blanket permission. */
    fun screenTaskQuestion(screenGoal: String, lang: Lang): String {
        val goal = screenGoal.trim().replace(Regex("\\s+"), " ").take(120)
        return when (lang) {
            Lang.HI -> "स्क्रीन पर यह काम करूँ: \"$goal\"? हाँ कहिए।"
            Lang.HINGLISH -> "Screen par yeh kaam karun: \"$goal\"? Haan kahiye."
            else -> "Use the screen to: \"$goal\"? Say yes to continue."
        }
    }

    /**
     * One short sentence the operator can answer. Kept at a handful of words so it is spoken before
     * the person has moved on, and phrased so "yes" is the only thing it invites.
     */
    fun question(step: PlannedStep, lang: Lang): String {
        val hindi = lang == Lang.HI
        val hinglish = lang == Lang.HINGLISH

        return when (step.action) {
            ActionType.CALL_CONTACT -> {
                val name = step.param.trim().take(40)
                when {
                    hindi -> "$name को कॉल करूँ? हाँ कहिए।"
                    hinglish -> "$name ko call karun? Haan kahiye."
                    else -> "Call $name? Say yes to call."
                }
            }

            ActionType.DIAL_NUMBER -> {
                val number = step.param.trim().take(24)
                when {
                    hindi -> "$number डायल करूँ? हाँ कहिए।"
                    hinglish -> "$number dial karun? Haan kahiye."
                    else -> "Dial $number? Say yes to dial."
                }
            }

            ActionType.MESSAGE, ActionType.MESSAGE_REPLY -> {
                val parts = step.param.split('|')
                val app = if (step.action == ActionType.MESSAGE) {
                    parts.getOrNull(0)?.trim().orEmpty().ifBlank { "message" }
                } else {
                    ""
                }
                val who = parts.getOrNull(1)?.trim().orEmpty().ifBlank { parts.firstOrNull()?.trim().orEmpty() }.take(30)
                val text = parts.getOrNull(2)?.trim().orEmpty().take(40)
                val to = who.ifBlank { "them" }
                when {
                    hindi -> "$to को" + (if (app.isNotBlank()) " $app पर" else "") +
                        (if (text.isNotBlank()) " \"$text\"" else " संदेश") + " भेजूँ? हाँ कहिए।"
                    hinglish -> "$to ko" + (if (app.isNotBlank()) " $app par" else "") +
                        (if (text.isNotBlank()) " \"$text\"" else " message") + " bhejun? Haan kahiye."
                    else -> "Send" + (if (text.isNotBlank()) " \"$text\"" else " a message") +
                        " to $to" + (if (app.isNotBlank()) " on $app" else "") + "? Say yes to send."
                }
            }

            ActionType.CONTACT_ADD -> {
                val name = step.param.split('|').firstOrNull()?.trim().orEmpty().take(30).ifBlank { "this person" }
                when {
                    hindi -> "$name को संपर्क में सहेजूँ? हाँ कहिए।"
                    hinglish -> "$name ko contact mein save karun? Haan kahiye."
                    else -> "Save $name as a contact? Say yes to save."
                }
            }

            ActionType.CALENDAR_ADD -> {
                val title = step.param.split('|').getOrNull(1)?.trim().orEmpty().take(40)
                    .ifBlank { step.param.trim().take(40) }
                when {
                    hindi -> "\"$title\" कैलेंडर में जोड़ूँ? हाँ कहिए।"
                    hinglish -> "\"$title\" calendar mein add karun? Haan kahiye."
                    else -> "Add \"$title\" to your calendar? Say yes to add."
                }
            }

            ActionType.SHELL -> {
                val command = step.param.trim().take(60)
                when {
                    hindi -> "यह कमांड चलाऊँ: $command ? हाँ कहिए।"
                    hinglish -> "Yeh command chalaun: $command ? Haan kahiye."
                    else -> "Run \"$command\"? Say yes to run it."
                }
            }

            else -> when {
                hindi -> "कर दूँ? हाँ कहिए।"
                hinglish -> "Kar dun? Haan kahiye."
                else -> "Shall I do that? Say yes."
            }
        }
    }
}
