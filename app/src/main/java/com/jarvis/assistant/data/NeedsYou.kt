package com.jarvis.assistant.data

import com.jarvis.assistant.voice.Lang
import java.util.Locale

/**
 * What is waiting on you, as rows.
 *
 * JARVIS already knows all of this — [com.jarvis.assistant.device.Nudges] composes the same facts
 * into a sentence and says it once. That is the wrong shape for the one question people ask their
 * phone most often: *is anything waiting on me?* A sentence disappears; a row stays.
 *
 * Three rules, all of them about staying trustworthy:
 *
 *  - **Never pad.** Fewer than one real row means no card at all. "All clear!" every time you look
 *    is noise, and an invented row is worse.
 *  - **Order by what happens next**, not by which check ran first.
 *  - **Say when a source is off.** If alert access is missing, the row says so instead of showing a
 *    confident zero — that number would be a guess dressed as a measurement.
 *
 * Pure Kotlin; `NeedsYouTest` covers the ordering, the silence and the missing-access case.
 */
object NeedsYou {

    /** Everything the card may draw on. Every field is optional on purpose. */
    data class Facts(
        val unreadMessages: Int = 0,
        /** People waiting, newest first — used for "3 waiting · Khushi, Amit". */
        val senders: List<String> = emptyList(),
        /** False when notification access is off, so the card can say so. */
        val alertsAvailable: Boolean = true,
        val nextEventTitle: String? = null,
        val nextEventMillis: Long = 0L,
        val batteryPercent: Int = 100,
        val charging: Boolean = false,
        /** Null when this phone cannot count steps or the permission is off. */
        val steps: Int? = null,
    )

    enum class Kind { EVENT, BATTERY, MESSAGES, ACCESS }

    data class Row(
        val kind: Kind,
        /** The headline: "Dentist in 40 min". */
        val title: String,
        /** The second line: "10:00 · calendar". May be blank. */
        val detail: String = "",
        /** Higher wins when more than three rows compete for the card. */
        val priority: Int = 0,
    )

    /** One row of the NEXT UP agenda. */
    data class AgendaRow(val title: String, val whenLabel: String, val allDay: Boolean = false)

    /** How many rows the card shows. Three fits above the fold; four does not. */
    const val MAX_ROWS = 3

    /** An event this close is worth a row; further out it belongs in NEXT UP, not here. */
    private val EVENT_WINDOW_MS = 2 * 60 * 60 * 1000L

    /** Below this the phone will not last the day, which is the only battery fact worth a row. */
    private const val BATTERY_FLOOR = 25

    /**
     * The card's rows, best first, at most [MAX_ROWS]. Empty means show nothing at all.
     *
     * [now] is passed in rather than read here so the whole function is deterministic and testable.
     */
    fun rows(
        facts: Facts,
        now: Long = System.currentTimeMillis(),
        lang: Lang = Lang.EN,
    ): List<Row> {
        val candidates = mutableListOf<Row>()

        // Alerts that cannot be read are reported, not silently zeroed.
        if (!facts.alertsAvailable) {
            candidates.add(
                Row(
                    kind = Kind.ACCESS,
                    title = when (lang.replyLang) {
                        Lang.HI -> "अलर्ट की अनुमति बंद है"
                        Lang.HINGLISH -> "Alert ki permission band hai"
                        else -> "Alert access is off"
                    },
                    detail = when (lang.replyLang) {
                        Lang.HI -> "जार्विस को नहीं दिखता कि कौन इंतज़ार कर रहा है"
                        Lang.HINGLISH -> "JARVIS ko nahi dikhta kaun wait kar raha hai"
                        else -> "JARVIS cannot see who is waiting"
                    },
                    priority = 30,
                ),
            )
        }

        val event = facts.nextEventTitle?.trim().orEmpty()
        if (event.isNotBlank() && facts.nextEventMillis > 0L) {
            val inMillis = facts.nextEventMillis - now
            if (inMillis in 0..EVENT_WINDOW_MS) {
                val soon = inMillis <= 30 * 60 * 1000L
                val label = When.untilLabel(facts.nextEventMillis, now)
                candidates.add(
                    Row(
                        kind = Kind.EVENT,
                        title = when {
                            soon -> "$event ${label.lowercase(Locale.US)}"
                            lang.replyLang == Lang.HI -> "$event बाद में"
                            lang.replyLang == Lang.HINGLISH -> "$event baad mein"
                            else -> "$event later"
                        },
                        detail = label,
                        priority = if (soon) 100 else 60,
                    ),
                )
            }
        }

        if (!facts.charging && facts.batteryPercent <= BATTERY_FLOOR) {
            candidates.add(
                Row(
                    kind = Kind.BATTERY,
                    title = when (lang.replyLang) {
                        Lang.HI -> "${facts.batteryPercent}% बची है"
                        Lang.HINGLISH -> "${facts.batteryPercent}% bachi hai"
                        else -> "${facts.batteryPercent}% left"
                    },
                    detail = when {
                        facts.batteryPercent > 10 && lang.replyLang == Lang.HI -> "चार्जर नहीं लगा है"
                        facts.batteryPercent > 10 && lang.replyLang == Lang.HINGLISH -> "charger nahi laga hai"
                        facts.batteryPercent > 10 -> "no charger connected"
                        lang.replyLang == Lang.HI -> "अभी चार्ज कीजिए"
                        lang.replyLang == Lang.HINGLISH -> "abhi charge kijiye"
                        else -> "charge it now"
                    },
                    priority = if (facts.batteryPercent <= 10) 95 else 70,
                ),
            )
        }

        if (facts.alertsAvailable && facts.unreadMessages > 0) {
            val who = facts.senders
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)
            candidates.add(
                Row(
                    kind = Kind.MESSAGES,
                    title = when {
                        lang.replyLang == Lang.HI && facts.unreadMessages == 1 -> "1 संदेश बाकी है"
                        lang.replyLang == Lang.HI -> "${facts.unreadMessages} संदेश बाकी हैं"
                        lang.replyLang == Lang.HINGLISH && facts.unreadMessages == 1 ->
                            "1 message baaki hai"
                        lang.replyLang == Lang.HINGLISH -> "${facts.unreadMessages} messages baaki hain"
                        facts.unreadMessages == 1 -> "1 message waiting"
                        else -> "${facts.unreadMessages} messages waiting"
                    },
                    detail = who.joinToString(", "),
                    priority = if (facts.unreadMessages >= 5) 85 else 50,
                ),
            )
        }

        return candidates.sortedByDescending { it.priority }.take(MAX_ROWS)
    }

    /** The single line for the focus home, where there is no room for a card. */
    fun chip(
        facts: Facts,
        now: Long = System.currentTimeMillis(),
        lang: Lang = Lang.EN,
    ): String? = rows(facts, now, lang).firstOrNull()?.title
}
