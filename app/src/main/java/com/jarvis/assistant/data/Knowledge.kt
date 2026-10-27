package com.jarvis.assistant.data

import android.content.Context
import java.util.Locale

/**
 * The knowledge shelf: what he has looked up, kept on the phone.
 *
 * A lookup costs a request and, more importantly, permission. Learning the same thing twice is
 * both wasteful and disrespectful, so every answer that came back from a real source is written
 * down — topic, the two sentences that mattered, where they came from, and when. Next time the
 * question is answered instantly, offline, and without asking anybody for anything.
 *
 * The file is a preference, not a database, and the format is five fields in a row: the whole
 * store is a string, [encode] and [decode] are pure, and the shelf can be wiped from settings in
 * one tap. Cards are capped so a shelf left running for a year does not grow without limit.
 */
object Knowledge {

    data class Card(
        val topic: String,
        val summary: String,
        val source: String = "",
        val url: String = "",
        val at: Long = 0L,
    )

    private const val PREFS = "jarvis_knowledge"
    private const val KEY = "cards"

    /** Newest kept, oldest dropped. ~200 cards is a few hundred kilobytes at most. */
    const val MAX_CARDS = 200

    /** Unit and record separators: never appear in a summary, so no escaping is needed. */
    private const val FIELD = "\u001F"
    private const val ROW = "\u001E"

    private const val MAX_TOPIC = 80
    private const val MAX_SUMMARY = 600

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // Pure encoding
    // ---------------------------------------------------------------------------------------------

    /** The shape a topic is compared in: lowercase, punctuation folded, leading article dropped. */
    fun key(raw: String): String {
        val base = raw.lowercase(Locale.US)
            .replace(Regex("[^\\p{L}\\p{N}\\p{M} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            base.startsWith("the ") -> base.removePrefix("the ")
            base.startsWith("a ") -> base.removePrefix("a ")
            base.startsWith("an ") -> base.removePrefix("an ")
            else -> base
        }.trim()
    }

    private fun clean(value: String): String = value
        .replace(FIELD, " ")
        .replace(ROW, " ")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    fun encode(cards: List<Card>): String = cards.joinToString(ROW) { card ->
        listOf(
            clean(card.topic).take(MAX_TOPIC),
            clean(card.summary).take(MAX_SUMMARY),
            clean(card.source).take(40),
            clean(card.url).take(300),
            card.at.toString(),
        ).joinToString(FIELD)
    }

    fun decode(raw: String?): List<Card> {
        if (raw.isNullOrBlank()) return emptyList()
        val cards = mutableListOf<Card>()
        for (row in raw.split(ROW)) {
            if (row.isBlank()) continue
            val parts = row.split(FIELD)
            if (parts.size < 2) continue
            val topic = parts[0].trim()
            if (topic.isBlank()) continue
            cards.add(
                Card(
                    topic = topic,
                    summary = parts.getOrElse(1) { "" }.trim(),
                    source = parts.getOrElse(2) { "" }.trim(),
                    url = parts.getOrElse(3) { "" }.trim(),
                    at = parts.getOrNull(4)?.trim()?.toLongOrNull() ?: 0L,
                ),
            )
        }
        return cards
    }

    // ---------------------------------------------------------------------------------------------
    // The shelf
    // ---------------------------------------------------------------------------------------------

    /** Oldest first, newest last — the order the HUD reverses for display. */
    fun cards(context: Context): List<Card> = decode(prefs(context).getString(KEY, null))

    fun count(context: Context): Int = cards(context).size

    fun isEmpty(context: Context): Boolean = cards(context).isEmpty()

    /**
     * What he already knows about [topic], or null.
     *
     * Exact first, then containment ("black holes" finds "black hole"), then a forgiving similarity
     * so a mis-heard word still lands on the card that was saved an hour ago.
     */
    fun find(context: Context, topic: String): Card? {
        val needle = key(topic)
        if (needle.isBlank()) return null
        val all = cards(context)
        return all.lastOrNull { key(it.topic) == needle }
            ?: all.lastOrNull { existing ->
                val other = key(existing.topic)
                other.length >= 4 && needle.length >= 4 &&
                    (other.contains(needle) || needle.contains(other))
            }
            ?: all.lastOrNull {
                PhraseBook.jaroWinkler(needle, key(it.topic)) >= 0.90
            }
    }

    /** Adds a card, or replaces the one it supersedes. Returns the shelf as it now stands. */
    fun save(context: Context, card: Card): List<Card> {
        val topic = clean(card.topic).take(MAX_TOPIC)
        if (topic.isBlank() || card.summary.isBlank()) return cards(context)
        val stored = card.copy(
            topic = topic,
            summary = clean(card.summary).take(MAX_SUMMARY),
            source = clean(card.source).take(40),
            url = clean(card.url).take(300),
            at = if (card.at > 0L) card.at else System.currentTimeMillis(),
        )
        val updated = (cards(context).filterNot { key(it.topic) == key(topic) } + stored)
            .takeLast(MAX_CARDS)
        prefs(context).edit().putString(KEY, encode(updated)).apply()
        return updated
    }

    fun forget(context: Context, topic: String) {
        val needle = key(topic)
        if (needle.isBlank()) return
        val updated = cards(context).filterNot { key(it.topic) == needle }
        prefs(context).edit().putString(KEY, encode(updated)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
