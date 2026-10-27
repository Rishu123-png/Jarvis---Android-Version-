package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.data.PhraseBook
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
    fun rewritten(last: Turn?, heard: String): String? {
        if (last == null || last.heard.isBlank()) return null
        val clean = PhraseBook.clean(heard)
        if (clean.isBlank()) return null

        // "for Amit" / "amit ko bhi" — same request, different person.
        val forMatch = Regex("^(?:and\\s+)?(?:do\\s+that\\s+)?for\\s+(.+)$").find(clean)
            ?: Regex("^(.+?)\\s+ko\\s+bhi$").find(clean)
        val person = forMatch?.groupValues?.getOrNull(1)?.trim()

        if (!person.isNullOrBlank()) {
            val body = messageBodyOf(last.heard)
            if (last.title.startsWith("MESSAGE") || body != null) {
                val text = body ?: last.slot
                return if (text.isBlank()) "message $person" else "message $person $text"
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

    /** The message text out of "message amit saying hi", if the last turn was a message. */
    private fun messageBodyOf(heard: String): String? {
        val clean = PhraseBook.clean(heard)
        val marker = Regex("\\b(?:saying|say|that|bolo|ki|with)\\b")
        val parts = marker.split(clean, limit = 2)
        if (parts.size < 2) return null
        val body = parts[1].trim()
        return body.ifBlank { null } ?: null
    }

    /** The name a message command was addressed to, for "reply the same thing". */
    fun personOf(heard: String): String? {
        val clean = PhraseBook.clean(heard)
        val match = Regex("^(?:message|msg|text|whatsapp|send message|write to)\\s+(.+?)(?:\\s+(?:saying|say|that|bolo)\\b|$)")
            .find(clean) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
