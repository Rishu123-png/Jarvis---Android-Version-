package com.jarvis.assistant.ai

/**
 * Turning a stream of tokens into sentences worth starting to speak.
 *
 * The assistant speaks two different things: a conversation, which is prose and can be read out as it
 * is written, and a routine, which is a JSON object and must be whole before anything happens to it.
 * One stream, two behaviours — and the model does not announce which it is about to write.
 *
 * So the first thing worth reading decides. A brace, a bracket or a fence means a plan: nothing is
 * spoken, the text is collected, and the old parser takes it from there. Anything else is a
 * conversation, and each finished sentence goes to the voice the moment its full stop arrives — which
 * is how the reply starts in a few hundred milliseconds instead of after the whole paragraph.
 *
 * Pure, so the rules about what counts as a sentence can be tested without a network or a phone.
 */
class StreamingReply(private val maxSentence: Int = 220) {

    enum class Mode {
        /** Nothing readable yet — a stream that has only sent whitespace, or nothing at all. */
        UNKNOWN,

        /** Prose. Sentences are handed out as they finish. */
        PROSE,

        /** A routine, or a fenced block. Collected, never spoken. */
        PLAN,
    }

    private val whole = StringBuilder()
    private var pending = StringBuilder()

    var mode: Mode = Mode.UNKNOWN
        private set

    /** Everything received so far, exactly as it arrived. */
    val text: String get() = whole.toString()

    /** True when the reply looked like prose — the caller speaks [text] rather than parsing it. */
    val isProse: Boolean get() = mode == Mode.PROSE

    /**
     * Adds one delta and returns the sentences that are now complete.
     *
     * Usually empty: deltas arrive several per sentence. The list is only non-empty at a boundary, or
     * when a very long sentence is cut at a space so the first words are not held hostage by the rest.
     */
    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        whole.append(delta)
        if (mode == Mode.UNKNOWN) decide()
        if (mode != Mode.PROSE) return emptyList()

        // Only the part that has not been handed out yet is scanned; what was already spoken is gone.
        pending.append(delta)
        return drain()
    }

    /**
     * Whatever is left when the stream ends.
     *
     * A reply with no final full stop is still a reply — "done" or "on it" would otherwise be
     * collected and never spoken.
     */
    fun finish(): List<String> {
        if (mode != Mode.PROSE) return emptyList()
        val rest = pending.toString().trim()
        pending = StringBuilder()
        return if (rest.isEmpty()) emptyList() else listOf(rest)
    }

    /**
     * Decides what kind of reply this is, from the first character that is not whitespace.
     *
     * Every delta before this one was whitespace, so nothing is lost by not having added it to
     * [pending]: a stream that has only sent indentation has not said anything yet.
     */
    private fun decide() {
        val first = whole.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return
        mode = when (whole[first]) {
            '{', '[', '`' -> Mode.PLAN
            else -> Mode.PROSE
        }
    }

    private fun drain(): List<String> {
        val out = ArrayList<String>(2)
        while (true) {
            val cut = boundary(pending) ?: break
            val sentence = pending.substring(0, cut).trim()
            pending.delete(0, cut)
            // A boundary at the very start would make an empty sentence; skip it, keep scanning.
            if (sentence.isNotEmpty()) out.add(sentence)
        }
        while (pending.length > maxSentence) {
            val head = forceCut(pending) ?: break
            out.add(head)
            pending.delete(0, head.length)
            while (pending.isNotEmpty() && pending[0] == ' ') pending.deleteCharAt(0)
        }
        return out
    }

    /**
     * The index just past the first sentence boundary, or null when the buffer holds no full stop yet.
     *
     * The awkward cases are the ones that make a naive splitter read "three" and "five" as two
     * sentences: decimals, and the handful of abbreviations that end in a full stop. Both are checked
     * before a dot is believed.
     */
    private fun boundary(buffer: StringBuilder): Int? {
        for (index in buffer.indices) {
            val char = buffer[index]
            when {
                char == '\n' -> {
                    // A newline ends a line of prose, but not when the line is a list marker.
                    if (buffer.substring(0, index).trim().length >= 2) return index + 1
                }

                // A dot ends a sentence when what follows it is the end of the stream or a space,
                // and when it is not part of a decimal or an abbreviation. The "end of the stream"
                // case matters: a reply whose last delta is its final full stop would otherwise be
                // collected and never spoken.
                char == '.' -> {
                    val next = buffer.getOrNull(index + 1)
                    val previous = buffer.getOrNull(index - 1)
                    val clean = when {
                        next == '.' || next?.isDigit() == true -> false // ellipsis, or 3.5
                        next != null && !next.isWhitespace() -> false    // mid-word, e.g. "e.g"
                        previous?.isDigit() == true -> false             // a decimal split by a delta
                        abbreviation(buffer, index) -> false
                        else -> true
                    }
                    if (clean) return index + 1
                }

                char == '!' || char == '?' || char == '।' || char == '…' -> {
                    val next = buffer.getOrNull(index + 1)
                    if (next == null || next.isWhitespace()) return index + 1
                }
            }
        }
        return null
    }

    /** "Mr.", "e.g.", "3 p.m." — a dot that ends an abbreviation is not the end of a sentence. */
    private fun abbreviation(buffer: StringBuilder, dot: Int): Boolean {
        var start = dot - 1
        while (start >= 0 && !buffer[start].isWhitespace()) start--
        val word = buffer.substring(start + 1, dot).lowercase()
        if (word.isEmpty()) return false
        if (word in ABBREVIATIONS) return true
        // "e.g" and "i.e" arrive as two dots; the second one is the one that would split.
        return word.length == 1 && buffer.getOrNull(dot - 1) == '.'
    }

    /** A very long sentence is cut at the last space before the limit, so speech can begin. */
    private fun forceCut(buffer: StringBuilder): String? {
        val window = buffer.substring(0, maxSentence)
        val space = window.lastIndexOf(' ')
        if (space <= 0) return null
        return window.substring(0, space).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
            "e.g", "i.e", "no", "approx", "fig", "p", "pp",
        )
    }
}
