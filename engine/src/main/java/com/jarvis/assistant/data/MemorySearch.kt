package com.jarvis.assistant.data

/**
 * Finding the one memory that matters, instead of sending all of them.
 *
 * The brain kept a fixed tail: the last [com.jarvis.assistant.voice.Brain.MAX_TURNS] turns, forty
 * people, sixty facts, oldest dropped first. That is a queue, not a memory. The operator's sister's
 * name, mentioned eight months ago, fell off the end; the fact that they hate being called after ten
 * still got sent in every prompt for a year.
 *
 * This is retrieval first and nothing fancy: BM25's idea without its statistics, on a corpus that is
 * never large enough to need them. Exact word matches count, prefixes count for less, a phrase hit
 * counts more, and a long entry has to earn its place by matching proportionally.
 *
 * No model, no vectors, no download. When AppSearch is wired in for the full transcript, this stays
 * the ranker that decides what the search results mean — and it works in Hindi and Hinglish because
 * it splits on letters, not on spaces.
 */
object MemorySearch {

    /**
     * One candidate.
     *
     * [kind] is what the memory *is* — `fact`, `person`, `turn`. It is not scored; it is kept so the
     * prompt can label what it is about to say, and so a caller can weight kinds itself if it ever
     * wants to (facts outranking small talk is a preference, not a law).
     */
    data class Entry(val kind: String, val text: String)

    /** Words worth matching. Two letters or more, letters only, lowercased. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>(8)
        val current = StringBuilder()
        for (char in text) {
            if (char.isLetterOrDigit()) {
                current.append(char.lowercaseChar())
            } else if (current.isNotEmpty()) {
                if (current.length >= 2) out.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.length >= 2) out.add(current.toString())
        return out
    }

    /**
     * How well one entry answers one query.
     *
     * Exact token match 1.0, prefix match 0.6, the whole query as a substring +2. Divided by the
     * square root of the entry's length, so a one-line fact is not buried by a five-hundred-word
     * transcript that happens to contain the same name once.
     */
    fun score(query: List<String>, entryText: String): Double {
        if (query.isEmpty()) return 0.0
        val entry = tokens(entryText)
        if (entry.isEmpty()) return 0.0

        var total = 0.0
        for (word in query) {
            var best = 0.0
            for (candidate in entry) {
                val match = when {
                    candidate == word -> 1.0
                    candidate.startsWith(word) && word.length >= 3 -> 0.6
                    else -> 0.0
                }
                if (match > best) best = match
                if (best == 1.0) break
            }
            total += best
        }
        if (total == 0.0) return 0.0

        val lowered = entryText.lowercase()
        if (lowered.contains(query.joinToString(" "))) total += 2.0

        return total / Math.sqrt(entry.size.toDouble())
    }

    /** The best [limit] entries for a query, best first. Empty query, empty answer. */
    fun rank(query: String, entries: List<Entry>, limit: Int): List<Entry> {
        if (limit <= 0) return emptyList()
        val words = tokens(query)
        if (words.isEmpty()) return emptyList()
        return entries
            .map { it to score(words, it.text) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * A prompt block for the retrieved memories.
     *
     * Labelled by kind, joined with newlines, nothing invented. A retrieval that returns nothing
     * returns nothing rather than a heading over an empty list — an empty section in a prompt is a
     * token bill and a hint that something is missing.
     */
    fun render(entries: List<Entry>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { "- (${it.kind}) ${it.text.trim()}" }
    }
}
