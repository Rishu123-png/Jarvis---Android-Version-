package com.jarvis.assistant.device

import android.content.Context
import com.jarvis.assistant.automation.SpeechOut
import com.jarvis.assistant.voice.SpokenLanguage

/**
 * The brief.
 *
 * A family member does not wait to be asked "is anything coming up" — they say the useful thing when
 * you walk in the door, and nothing at all when there is nothing to say. That is the whole rule here:
 *
 *  - **one brief per `BRIEF_GAP_MS`**, so opening and closing the app is not a monologue,
 *  - **only facts worth acting on**: the next event within the window, a battery that will not make
 *    it, messages from actual people, and a nudge if nothing has moved all day,
 *  - **silent when the answer is "nothing"**, and silent during the operator's quiet hours,
 *  - **never repeats a line it has already said today**.
 *
 * [compose] is pure: it takes the facts and returns the sentence, so the wording is testable and the
 * numbers always come from the same place the dashboard reads them.
 */
object Nudges {

    private const val PREFS = "jarvis_nudges"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST = "last_at"
    private const val KEY_LAST_LINE = "last_line"

    /** At most one brief every three hours: enough to be useful, not enough to be noise. */
    const val BRIEF_GAP_MS = 3 * 60 * 60 * 1000L

    /** An event this close is worth mentioning. */
    private const val EVENT_WINDOW_MS = 90 * 60 * 1000L

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    data class Facts(
        val nextEventTitle: String? = null,
        val nextEventMillis: Long = 0L,
        val batteryPercent: Int = 100,
        val charging: Boolean = false,
        val unreadMessages: Int = 0,
        val steps: Int? = null,
    )

    /**
     * The sentence to say, or null when there is nothing worth saying.
     *
     * Order is by urgency: something starting soon first, then a phone that will die, then people
     * waiting for an answer, then a whole day with no movement.
     */
    fun composeIn(facts: Facts, lang: com.jarvis.assistant.voice.Lang): String? {
        val parts = mutableListOf<String>()

        val event = facts.nextEventTitle
        if (!event.isNullOrBlank() && facts.nextEventMillis > 0 &&
            facts.nextEventMillis - System.currentTimeMillis() in 0..EVENT_WINDOW_MS
        ) {
            val when_ = com.jarvis.assistant.data.When.untilLabel(facts.nextEventMillis)
            parts.add(
                when (lang.replyLang) {
                    com.jarvis.assistant.voice.Lang.HI -> "$event $when_ शुरू होगा।"
                    com.jarvis.assistant.voice.Lang.HINGLISH -> "$event $when_ shuru hoga."
                    else -> "$event starts $when_."
                },
            )
        }

        if (!facts.charging && facts.batteryPercent in 1..15) {
            parts.add(
                when (lang.replyLang) {
                    com.jarvis.assistant.voice.Lang.HI -> "बैटरी ${facts.batteryPercent} प्रतिशत है।"
                    com.jarvis.assistant.voice.Lang.HINGLISH -> "Battery ${facts.batteryPercent} percent hai."
                    else -> "Battery is at ${facts.batteryPercent} percent."
                },
            )
        }

        if (facts.unreadMessages in 1..30) {
            parts.add(
                when (lang.replyLang) {
                    com.jarvis.assistant.voice.Lang.HI -> "${facts.unreadMessages} संदेश बिना पढ़े हैं।"
                    com.jarvis.assistant.voice.Lang.HINGLISH -> "${facts.unreadMessages} message unread hain."
                    else -> "${facts.unreadMessages} messages are waiting."
                },
            )
        }

        if (parts.isEmpty()) {
            val steps = facts.steps
            if (steps != null && steps < 300 && java.util.Calendar.getInstance()
                    .get(java.util.Calendar.HOUR_OF_DAY) in 15..21
            ) {
                parts.add(
                    when (lang.replyLang) {
                        com.jarvis.assistant.voice.Lang.HI -> "आज अभी तक बहुत कम चले हैं। थोड़ा टहल लीजिए।"
                        com.jarvis.assistant.voice.Lang.HINGLISH -> "Aaj abhi tak bohot kam chale hain. Thoda chal lijiye."
                        else -> "Not many steps today. A short walk would help."
                    },
                )
            }
        }

        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    /**
     * Says the brief if it is due. Called when the app comes to the front, after unlocking.
     *
     * Returns the line that was spoken, or null. Deliberately swallows every failure: a brief that
     * cannot be composed must never interfere with the thing the operator actually asked for.
     */
    suspend fun greet(context: Context): String? {
        if (!isEnabled(context)) return null
        if (!NotifyBrain.isQuietHour(
                NotifyBrain.currentHour(),
                NotifyBrain.quietFrom(context),
                NotifyBrain.quietTo(context),
            )
        ) {
            return null
        }

        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAt = store.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - lastAt < BRIEF_GAP_MS) return null

        val battery = try {
            readBattery(context)
        } catch (t: Throwable) {
            null
        }
        val event = try {
            CalendarBridge.next(context)
        } catch (t: Throwable) {
            null
        }
        val steps = try {
            HealthLog.stepsToday(context)
        } catch (t: Throwable) {
            null
        }

        val facts = Facts(
            nextEventTitle = event?.title,
            nextEventMillis = event?.startMillis ?: 0L,
            batteryPercent = battery?.percent ?: 100,
            charging = battery?.isCharging ?: true,
            unreadMessages = if (NotificationRepository.hasAccess(context)) {
                NotifyBrain.countUnreadMessages(context)
            } else {
                0
            },
            steps = steps,
        )

        val lang = SpokenLanguage.last(context)
        val line = composeIn(facts, lang) ?: return null
        if (line == store.getString(KEY_LAST_LINE, null)) return null

        store.edit()
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putString(KEY_LAST_LINE, line)
            .apply()

        try {
            SpeechOut.speak(context, line, timeoutMillis = 20_000L)
        } catch (t: Throwable) {
            // Best effort: speaking is never allowed to be a failure.
        }
        return line
    }

    /** The same brief as text, for the card, so sight and sound never disagree. */
    suspend fun briefText(context: Context): String? {
        val event = try {
            CalendarBridge.next(context)
        } catch (t: Throwable) {
            null
        }
        val battery = try {
            readBattery(context)
        } catch (t: Throwable) {
            null
        }
        return composeIn(
            Facts(
                nextEventTitle = event?.title,
                nextEventMillis = event?.startMillis ?: 0L,
                batteryPercent = battery?.percent ?: 100,
                charging = battery?.isCharging ?: true,
                unreadMessages = if (NotificationRepository.hasAccess(context)) {
                    NotifyBrain.countUnreadMessages(context)
                } else {
                    0
                },
                steps = try {
                    HealthLog.stepsToday(context)
                } catch (t: Throwable) {
                    null
                },
            ),
            SpokenLanguage.last(context),
        )
    }
}
