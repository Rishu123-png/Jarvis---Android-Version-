package com.jarvis.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * An action plan waiting for a yes.
 *
 * Calls, messages, writes, destructive commands, and screen-agent tasks wait for an explicit yes.
 * JARVIS asks once, stores the full plan and original request here, and the next sentence decides.
 * Anything other than a yes throws the plan away, so a later confirmation cannot fire an old action.
 *
 * The window is short on purpose: a yes given a minute later is an answer to something else.
 */
object PendingConfirm {

    /** A step, small enough to store between two utterances. */
    data class Step(val action: String, val param: String, val label: String)

    data class Pending(
        val question: String,
        val title: String,
        val subtitle: String,
        val say: String,
        val steps: List<Step>,
        val screenGoal: String? = null,
        /** Original command, retained so confirmation does not overwrite history with just "yes". */
        val heard: String = "",
        val at: Long = System.currentTimeMillis(),
    ) {
        val isFresh: Boolean get() = System.currentTimeMillis() - at < WINDOW_MS
    }

    /** 90 seconds: long enough to think, short enough to still be about this sentence. */
    const val WINDOW_MS = 90_000L

    private const val PREFS = "jarvis_confirm"
    private const val KEY = "pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ask(context: Context, pending: Pending) {
        prefs(context).edit().putString(KEY, encode(pending)).apply()
    }

    /** The waiting question, or null when there is none or it has gone stale. */
    fun get(context: Context): Pending? {
        val pending = decode(prefs(context).getString(KEY, null)) ?: return null
        if (!pending.isFresh) {
            clear(context)
            return null
        }
        return pending
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    fun encode(pending: Pending): String = JSONObject().apply {
        put("q", pending.question)
        put("t", pending.title)
        put("s", pending.subtitle)
        put("say", pending.say)
        put("heard", pending.heard)
        put("at", pending.at)
        if (pending.screenGoal != null) put("goal", pending.screenGoal)
        put("steps", JSONArray().apply {
            pending.steps.forEach { step ->
                put(JSONObject().apply {
                    put("a", step.action)
                    put("p", step.param)
                    put("l", step.label)
                })
            }
        })
    }.toString()

    fun decode(raw: String?): Pending? = try {
        if (raw.isNullOrBlank()) {
            null
        } else {
            val json = JSONObject(raw)
            val array = json.optJSONArray("steps") ?: JSONArray()
            val steps = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val action = item.optString("a")
                    if (action.isBlank()) continue
                    add(Step(action, item.optString("p"), item.optString("l")))
                }
            }
            val goal = json.optString("goal").takeIf { it.isNotBlank() }
            if (steps.isEmpty() && goal == null) {
                null
            } else {
                Pending(
                    question = json.optString("q"),
                    title = json.optString("t"),
                    subtitle = json.optString("s"),
                    say = json.optString("say"),
                    steps = steps,
                    screenGoal = goal,
                    heard = json.optString("heard"),
                    at = json.optLong("at", 0L),
                )
            }
        }
    } catch (t: Throwable) {
        null
    }
}
