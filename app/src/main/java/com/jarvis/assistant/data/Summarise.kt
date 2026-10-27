package com.jarvis.assistant.data

import java.util.Locale

/**
 * A summariser with no model behind it.
 *
 * When JARVIS looks something up, a public encyclopedia hands back a paragraph written for a
 * reader with a screen — three or four sentences of context, a pronunciation note, a footnote
 * marker. Spoken out loud, that is a lecture. So the paragraph is cut down here, on the phone, with
 * counting instead of inference: split into sentences, score each one by how many of the question's
 * words it contains, give the opening sentence a bonus because prose puts its subject first, and
 * keep the best two.
 *
 * It is deliberately dumb. A real summariser needs a model, a model needs a key or a download, and
 * the whole point of this path is that it works with neither. Counting gets the useful sentence
 * most of the time, and when it does not the person still gets a true sentence from a named source
 * rather than a confident invention.
 *
 * Pure Kotlin, no Android: every rule here is covered by `SummariseTest` in CI.
 */
object Summarise {

    /** Words that carry no meaning for scoring. Kept short on purpose — a long list hides topics. */
    private val FILLER = setOf(
        "the", "and", "for", "with", "that", "this", "these", "those", "from", "was", "were",
        "are", "has", "have", "had", "his", "her", "its", "their", "they", "them", "she", "him",
        "but", "not", "you", "your", "our", "who", "whom", "whose", "what", "which", "when",
        "where", "why", "how", "did", "does", "doing", "done", "been", "being", "also", "such",
        "into", "onto", "about", "over", "under", "after", "before", "then", "than", "there",
        "here", "some", "any", "all", "one", "two", "can", "could", "would", "should", "will",
        "may", "might", "must", "very", "more", "most", "other", "each", "many", "much", "its",
        "tell", "know", "please", "jarvis", "kya", "hai", "hain", "ka", "ki", "ke", "mein",
        "batao", "bata", "karo", "aur", "ek", "yeh", "woh", "iska", "uska",
    )

    /** Sentence break: after terminal punctuation, including the Devanagari danda. */
    private val BREAK = Regex("(?<=[.!?।])\\s+")

    private val NOISE = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val SPACES = Regex("\\s+")
    private val CITATION = Regex("\\[[0-9]{1,3}]")

    /** Strips footnote markers and collapses whitespace. The only cleaning a quote needs. */
    fun tidy(raw: String): String = raw
        .replace(CITATION, "")
        .replace(SPACES, " ")
        .trim()

    /** The sentences worth considering, in their original order. */
    fun sentences(raw: String): List<String> = tidy(raw)
        .split(BREAK)
        .map { it.trim() }
        .filter { it.length >= 20 }

    /** Content words in a phrase, lowercased. Short words and filler are dropped. */
    fun keywords(raw: String): Set<String> = tidy(raw)
        .lowercase(Locale.US)
        .split(NOISE)
        .filter { it.length >= 3 && it !in FILLER }
        .toSet()

    /**
     * Cuts at a word boundary, never mid-word, and marks that something was cut.
     *
     * Speech is the reason: a sentence that stops mid-word sounds like a dropped call.
     */
    fun trim(raw: String, limit: Int): String {
        val text = tidy(raw)
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val window = text.take(limit)
        val cut = window.lastIndexOf(' ')
        val body = (if (cut > limit / 2) window.take(cut) else window).trimEnd().trimEnd(',', ';', ':')
        return "$body…"
    }

    /**
     * The best [maxSentences] sentences of [raw] for [query], in the order they appeared.
     *
     * Falls back to the opening sentences when nothing overlaps, because a subject's first sentence
     * is the one that defines it — which is exactly the fallback Hindi needs, since the keyword
     * list above is English.
     */
    fun best(
        query: String,
        raw: String,
        maxSentences: Int = 2,
        maxChars: Int = 280,
    ): String {
        if (maxSentences <= 0) return ""
        val all = sentences(raw)
        if (all.isEmpty()) return trim(raw, maxChars)

        val wanted = keywords(query)
        val scored = all.mapIndexed { index, sentence ->
            val words = keywords(sentence)
            var score = words.count { it in wanted }.toDouble()
            // Position: the first sentence names the thing, the second qualifies it.
            score += when (index) {
                0 -> 1.4
                1 -> 0.5
                2 -> 0.2
                else -> 0.0
            }
            // A sentence that is a list of names is rarely the answer, and a colon promises more.
            if (sentence.endsWith(":")) score -= 0.8
            if (sentence.length > 260) score -= 0.4
            index to score
        }

        val chosen = scored
            .sortedByDescending { it.second }
            .take(maxSentences)
            .map { it.first }
            .sorted()

        return trim(chosen.joinToString(" ") { all[it] }, maxChars)
    }
}
