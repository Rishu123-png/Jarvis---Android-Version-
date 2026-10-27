package com.jarvis.assistant.voice

import android.content.Context
import java.util.Locale
import org.json.JSONObject

/**
 * "do that for Amit", "and tomorrow?", "same but at 5".
 *
 * Real conversations are not one sentence each: people leave out everything already said. An
 * assistant that forgets turns into a form to fill in. So the last turn is remembered — what was
 * asked, what it resolved to, and the words that carried the moving parts — and a short follow-up is
 * rewritten into a complete sentence before the router sees it.
 *
 * [rewritten] is pure, so the grammar of forgetting is testable without a phone.
 */
object FollowUp {

    private const val PREFS = "jarvis_followup"
    private const val KEY = "last"

    data class Turn(
        val heard: String,
        val title: String,
        val said: String,
        /** Message text or place name from the last turn, when there was one. */
        val slot: String = "",
    )

    fun remember(context: Context, turn: Turn) {
        try {
            val json = JSONObject()
                .put("heard", turn.heard)
                .put("title", turn.title)
                .put("said", turn.said)
                .put("slot", turn.slot)
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.toString()).apply()
        } catch (t: Throwable) {
            // Losing one turn is acceptable; a crash is not.
        }
    }

    fun last(context: Context): Turn? = try {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        val json = JSONObject(raw)
        Turn(
            heard = json.optString("heard"),
            title = json.optString("title"),
            said = json.optString("said"),
            slot = json.optString("slot"),
        )
    } catch (t: Throwable) {
        null
    }

    fun forget(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    /**
     * Turns a short follow-up into something the router can act on, or null when the sentence is
     * already complete.
     *
     * Only patterns whose meaning is unambiguous are rewritten. Anything else is left alone, because
     * inventing a subject is worse than asking again.
     */
    /**
     * Lowercased and tidy, but **keeping colons and digits**: a time like "8:30" must survive, and
     * `PhraseBook.clean` turns it into "8 30", which is not a time any more.
     */
    private fun loose(text: String): String = text.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N} :+\\-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun rewritten(last: Turn?, heard: String): String? {
        if (last == null || last.heard.isBlank()) return null
        val clean = loose(heard)
        if (clean.isBlank()) return null

        // "for Amit" / "amit ko bhi" — same request, different person.
        val forMatch = Regex("^(?:and\\s+)?(?:do\\s+that\\s+)?for\\s+(.+)$").find(clean)
            ?: Regex("^(.+?)\\s+ko\\s+bhi$").find(clean)
        val person = forMatch?.groupValues?.getOrNull(1)?.trim()

        if (!person.isNullOrBlank()) {
            // Keep the name exactly as it was spoken: "Khushi" is a name, not a keyword.
            val spokenName = Regex("(?i)(?:for\\s+(\\S+)|(\\S+)\\s+ko\\s+bhi)")
                .find(heard)?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }
                ?: heard.trim().removePrefix("for ").trim()
            val name = spokenName.ifBlank { person }
            val body = messageBodyOf(last.heard)
            if (last.title.startsWith("MESSAGE") || body != null) {
                val text = body ?: last.slot
                return if (text.isBlank()) "message $name" else "message $name $text"
            }
            return null
        }

        // "and tomorrow" / "kal bhi" — same thing, next day.
        if (clean in setOf("and tomorrow", "tomorrow", "tomorrow too", "kal", "kal bhi", "and tomorrow too")) {
            return when {
                last.title.startsWith("ALARM") -> null
                last.title.startsWith("CALENDAR") || last.title.startsWith("ADD") ->
                    last.heard.replace(Regex("(?i)\btoday\b|\baaj\b"), "tomorrow") + " tomorrow"

                last.title.startsWith("REMINDER") -> last.heard + " tomorrow"
                else -> null
            }
        }

        // "make it 5" / "at 6 instead" — same subject, new time. Only when a time was involved.
        val newTime = Regex("^(?:make it|at|change (?:it )?to)\\s+(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)$")
            .find(clean)?.groupValues?.getOrNull(1)?.trim()
        if (!newTime.isNullOrBlank() &&
            (last.title.startsWith("ALARM") || last.title.startsWith("REMINDER") ||
                last.title.startsWith("CALENDAR") || last.title.startsWith("ADD"))
        ) {
            return "set alarm $newTime"
        }

        return null
    }

    /**
     * Splits a message command into the person and the words to send.
     *
     * Two word orders have to work, because people use both: English puts the body after a marker
     * ("message amit saying I will be late"), Hindi puts it *before* the verb ("message amit hi
     * bolo"). The case of the body and the name is preserved — it is going to be sent as typed.
     */
    private fun messageParts(heard: String): Pair<String?, String> {
        val prefix = Regex(
            "^(?i)(?:open\\s+)?(?:send\\s+)?(?:message|msg|text|whatsapp|telegram|sms|write\\s+to|chat\\s+with)\\s+",
        )
        val rest = heard.trim().replace(prefix, "").trim()
        if (rest.isBlank()) return null to ""

        val marker = Regex("(?i)\\b(saying|say|that|bolo|bol|ki|likh)\\b")
        val found = marker.find(rest)
        if (found == null) {
            return rest.substringBefore(' ').trim().takeIf { it.isNotBlank() } to ""
        }

        val before = rest.substring(0, found.range.first).trim().removeSuffix("ko").trim()
        val after = rest.substring(found.range.last + 1).trim()
        val person = before.substringBefore(' ').trim().takeIf { it.isNotBlank() }
        val leftover = before.substringAfter(' ', "").trim()
        val body = if (after.isNotBlank()) after else leftover
        return person to body
    }

    /** The message text out of "message amit saying hi", if the last turn was a message. */
    private fun messageBodyOf(heard: String): String? =
        messageParts(heard).second.takeIf { it.isNotBlank() }

    /** The name a message command was addressed to, for "reply the same thing". */
    fun personOf(heard: String): String? = messageParts(heard).first
}
