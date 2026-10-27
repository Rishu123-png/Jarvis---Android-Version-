package com.jarvis.assistant.data

import android.content.Context

/**
 * The permission desk in front of the internet.
 *
 * Looking something up is the only thing this app does that leaves the phone without being asked
 * twice: it reveals a question to a public encyclopedia and it costs data. So it is gated, and the
 * gate has three positions rather than an on/off switch, because "ask me every time" and "never
 * bother me" are both reasonable and neither should be the only choice.
 *
 * A pending request is how the gate actually works in conversation: he asks one short question,
 * stores the topic here, and the next word decides. Nothing is fetched until that word is yes, and
 * the pending question expires rather than waiting forever.
 */
object ResearchDesk {

    enum class Mode(val label: String, val caption: String) {
        /** Ask before every lookup, even one he has done before. */
        ASK_ALWAYS(
            "ASK EVERYTHING",
            "He asks before every single lookup. Nothing leaves the phone without a yes.",
        ),

        /** Ask the first time. What is already on the shelf answers instantly and silently. */
        ASK_NEW(
            "ASK ONCE PER TOPIC",
            "He asks the first time he looks something up. After that he remembers it and answers " +
                "straight away, offline.",
        ),

        /** Look it up, then say what was found. Still only reads; never posts anywhere. */
        ASK_NEVER(
            "DON'T ASK",
            "He looks it up and tells you what he found. Still read-only — he cannot send, post or " +
                "sign in to anything.",
        ),
    }

    data class Pending(val topic: String, val at: Long = System.currentTimeMillis()) {
        val isFresh: Boolean
            get() = System.currentTimeMillis() - at < WINDOW_MS
    }

    private const val PREFS = "jarvis_research"
    private const val KEY_MODE = "mode"
    private const val KEY_TOPIC = "pending_topic"
    private const val KEY_AT = "pending_at"

    /** A question he asked out loud goes stale; he should not act on a yes given tomorrow. */
    private const val WINDOW_MS = 10 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode {
        val stored = prefs(context).getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.name == stored } ?: Mode.ASK_NEW
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** True when the next lookup of something not already known needs a spoken yes. */
    fun shouldAsk(context: Context): Boolean = mode(context) != Mode.ASK_NEVER

    fun pending(context: Context): Pending? {
        val topic = prefs(context).getString(KEY_TOPIC, null)?.trim().orEmpty()
        if (topic.isBlank()) return null
        val pending = Pending(topic, prefs(context).getLong(KEY_AT, 0L))
        if (!pending.isFresh) {
            clearPending(context)
            return null
        }
        return pending
    }

    fun setPending(context: Context, topic: String) {
        val clean = topic.trim().take(80)
        if (clean.isBlank()) return
        prefs(context).edit()
            .putString(KEY_TOPIC, clean)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_TOPIC).remove(KEY_AT).apply()
    }
}
