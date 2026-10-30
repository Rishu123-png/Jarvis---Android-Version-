package com.jarvis.assistant.wake

/**
 * The phrase and the matcher, kept free of Android so the tests can run on the JVM.
 *
 * Vosk is given a grammar of exactly two alternatives: the phrase, and the engine's own "unknown"
 * token. Everything that is not the phrase falls into the unknown token, which is what keeps this
 * from firing on ordinary speech.
 */
object WakePhrase {

    const val PHRASE = "hey jarvis"

    /** Grammar handed to the recogniser. Quoted JSON, as Vosk expects. */
    val GRAMMAR: String = "[\"$PHRASE\", \"[unk]\"]"

    private val FIELD = Regex("\"(partial|text)\"\\s*:\\s*\"([^\"]*)\"")

    /**
     * True when a recogniser hypothesis contains the phrase. Accepts Vosk's JSON
     * (`{"partial" : "..."}` or `{"text" : "..."}`) and plain text, and ignores case, punctuation and
     * extra spaces, so "Hey, Jarvis!" counts.
     */
    fun heard(hypothesis: String?): Boolean {
        if (hypothesis.isNullOrBlank()) return false
        val spoken = FIELD.find(hypothesis)?.groupValues?.get(2) ?: hypothesis
        return normalize(spoken).contains(PHRASE)
    }

    /**
     * The command without the wake phrase in front of it.
     *
     * The audio handed over after detection starts slightly *before* the phrase ended, so its
     * transcript begins with "hey jarvis". Left in, it would be planned as part of the request.
     * Only a leading occurrence is removed, so "open the jarvis notes" is untouched.
     */
    private val LEADING_PHRASE = Regex(
        "^\\s*(?:(?:hey|hay|hi|ok|okay)\\s*,?\\s*)?jarvis\\b[\\s,.:;!?\\-]*",
        RegexOption.IGNORE_CASE,
    )

    fun strip(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace(LEADING_PHRASE, "").trim()
    }

    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
