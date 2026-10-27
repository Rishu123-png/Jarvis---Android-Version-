package com.jarvis.assistant.voice

/**
 * Deciding when a sentence is asking him to *look* rather than to *fetch*.
 *
 * Pure and testable, like [ResearchTalk], because the distinction is what keeps the feature honest:
 * "what is this" with a camera pointed at a page is a sight question, while "what is a black hole"
 * is a lookup and "what is my name" is neither. Getting that wrong either wastes a photo of the
 * operator's wall or answers a spoken question with a picture.
 */
object Sight {

    /** Ways of saying "use your eyes", in three languages. */
    private val PHRASES = listOf(
        "look at this", "look at that", "look here", "have a look", "take a look",
        "can you see this", "do you see this", "see this", "read this", "read it",
        "read this for me", "what is this", "what is that", "what am i looking at",
        "what do you see", "describe this", "describe what you see", "what does this say",
        "translate this", "yeh kya hai", "ye kya hai", "yeh dekho", "ye dekho",
        "yeh padho", "ye padho", "kya dikh raha hai", "इसे देखो", "यह क्या है",
        "ये क्या है", "इसे पढ़ो", "क्या दिख रहा है",
    )

    /** Shorter forms that are only a sight request on their own. */
    private val BARE = setOf("look", "see", "read", "scan", "dekho", "padho", "देखो", "पढ़ो")

    private fun clean(raw: String): String = raw
        .lowercase(java.util.Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}\\p{M} ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** True when the sentence asks him to use the camera. */
    fun looksLikeLookAt(raw: String): Boolean {
        val text = clean(raw)
        if (text.isBlank()) return false
        if (text in BARE) return true
        return PHRASES.any { phrase ->
            text == phrase || text.startsWith("$phrase ") || text.endsWith(" $phrase") ||
                text.contains(" $phrase ")
        }
    }

    /**
     * What to ask about the picture.
     *
     * The operator's own words are the question when they said one, because "read this" and "is
     * this a healthy snack" want very different answers. A bare "look" becomes a plain description.
     */
    fun question(raw: String, lang: Lang): String {
        val text = clean(raw)
        return when {
            text.isEmpty() || text in BARE -> describe(lang)
            else -> raw.trim()
        }
    }

    private fun describe(lang: Lang): String = when (lang.replyLang) {
        Lang.HI -> "इस तस्वीर में क्या है, थोड़े में बताइए।"
        Lang.HINGLISH -> "Is tasveer mein kya hai, thoda sa batao."
        else -> "What am I looking at? Answer in two sentences."
    }

    /**
     * The instruction handed to the whatever model is configured. It forbids guessing: a blurry
     * photo of a medicine strip must produce "I cannot read that", not a plausible dosage.
     */
    fun prompt(lang: Lang): String = buildString {
        appendLine("You are JARVIS. You live on this person's phone and you are family, not a product.")
        appendLine("You were built by Rishu Jaswar. If asked who built you, that is the whole answer.")
        appendLine(
            "Never mention a model, a provider, an API, a key or a vendor, and never describe " +
                "yourself as a language model.",
        )
        appendLine("The operator just pointed a camera at something and spoke. Answer what the picture shows.")
        appendLine(
            "Never invent text you cannot read, a number you cannot see, or a name that is not " +
                "clearly there. If the photo is too blurry or too dark, say so in one sentence and " +
                "ask them to take another.",
        )
        appendLine("Two or three sentences, spoken aloud, no lists, no markdown.")
        appendLine("Speak in ${lang.label} (${lang.tag}).")
    }
}
