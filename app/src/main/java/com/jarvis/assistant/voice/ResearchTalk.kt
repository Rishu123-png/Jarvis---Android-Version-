package com.jarvis.assistant.voice

import com.jarvis.assistant.data.ResearchDesk
import java.util.Locale

/**
 * The words around a lookup: what was asked, whether the answer was yes, and how to say what came
 * back.
 *
 * Splitting this out of [IntentRouter] is not tidiness. Reading a question is a *pure* function of a
 * sentence, so it lives here where a JVM test can hammer it with the forty ways people phrase the
 * same question, in three languages, without a phone in sight.
 *
 * Two kinds of request are separated on purpose:
 *
 *  - **explicit** — "look up X", "google X", "search for X". The person asked him to go and find
 *    out, so this wins over everything else, even when an AI key is stored.
 *  - **implicit** — "what is X", "X kya hai". Those are questions, and a question is only worth a
 *    lookup when nothing else can answer it, so [IntentRouter] tries these *last*.
 */
object ResearchTalk {

    /** Lowercase, letters and digits only. Keeps Devanagari, unlike the command cleaner. */
    fun normal(raw: String): String = raw
        .lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}\\p{M} ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Verbs that mean "go and find out", not "do something on this phone". */
    private val EXPLICIT = listOf(
        Regex("^(?:jarvis )?(?:please |zara )?(?:can you |could you |would you )?(?:look up|lookup|look into|search for|search the web for|google|research|find out about|find out|check up on) (?:about |for |on |the web for |the internet for )?(.+)$"),
        Regex("^(?:jarvis )?(?:please )?(?:what do you know about|what do you know of|tell me about|tell me more about|tell me something about) (.+)$"),
        Regex("^(.+?) (?:ke baare mein (?:batao|bata do|jankari do)|ke bare mein (?:batao|bata do|jankari do)|search karo|google karo)$"),
        Regex("^(.+?) (?:के बारे में बताओ|के बारे में बताइए|की जानकारी दो)$"),
    )

    /** Question shapes a person uses when they expect an answer, not an action. */
    private val IMPLICIT = listOf(
        Regex("^(?:ok |so )?(?:what|who|which|where|when|why|how) (?:is|are|was|were|does|do|did|means) (?:a |an |the )?(.+)$"),
        Regex("^(?:do|did) you know (?:about |anything about |who is |what is )?(.+)$"),
        Regex("^define (.+)$"),
        Regex("^definition of (.+)$"),
        Regex("^(.+?) (?:kya hai|kya hain|kya hota hai|kya hoti hai|kaun hai|kaun hain|kaun tha|kya matlab hai)$"),
        Regex("^(.+?) (?:क्या है|क्या हैं|क्या होता है|क्या होती है|कौन है|कौन हैं|कौन था|का मतलब क्या है)$"),
    )

    /**
     * Things that belong to the person, not to an encyclopedia. "What is my name" is a memory
     * question, and looking it up would be both wrong and a small betrayal.
     */
    private val PERSONAL = listOf(
        "my ", "your ", "our ", "his ", "her ", "their ", "this ", "that ", "these ", "those ",
        "mera ", "meri ", "mere ", "mujhe ", "aapka ", "aapki ", "tumhara ", "tumhari ",
        "मेरा ", "मेरी ", "मेरे ", "मुझे ", "आपका ", "आपकी ", "तुम्हारा ", "तुम्हारी ",
    )

    /** Topics that are never a lookup: they are either a joke, a clock, or the phone itself. */
    private val REFUSED = setOf(
        "it", "this", "that", "you", "me", "the time", "time", "the date", "date", "today",
        "tomorrow", "yesterday", "going on", "happening", "the matter", "up", "the news",
        "kya", "yeh", "woh", "time kya", "samay", "the day",
    )

    /**
     * A topic containing one of these is not a topic: it is a question about the person, about him,
     * or about a pronoun. "How old am I" must go to memory, not to an encyclopedia.
     */
    private val REFUSED_WORDS = setOf(
        "i", "me", "mine", "myself", "am", "you", "your", "yours", "yourself", "we", "us",
        "myself", "mujhe", "main", "mai", "hum", "tum", "aap", "aapko", "khud",
    )

    private const val MIN_TOPIC = 2
    private const val MAX_TOPIC = 80
    private const val MAX_WORDS = 10

    // ---------------------------------------------------------------------------------------------
    // Reading the request
    // ---------------------------------------------------------------------------------------------

    /** A look-up the person asked for by name, or null. */
    fun explicitTopicOf(raw: String): String? = firstTopic(raw, EXPLICIT)

    /** A question worth a lookup, or null. Used only after every local answer has failed. */
    fun topicOf(raw: String): String? = firstTopic(raw, IMPLICIT)

    private fun firstTopic(raw: String, patterns: List<Regex>): String? {
        val text = normal(raw)
        if (text.isBlank()) return null
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val topic = tidyTopic(match.groupValues.getOrNull(1).orEmpty())
            if (topic != null) return topic
        }
        return null
    }

    private fun tidyTopic(raw: String): String? {
        val topic = raw.trim()
            .trim('.', ',', '!', '?', '"', '\'', ':', ';')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (topic.length < MIN_TOPIC || topic.length > MAX_TOPIC) return null
        if (topic.split(' ').size > MAX_WORDS) return null
        if (topic.none { it.isLetter() }) return null
        if (PERSONAL.any { topic.startsWith(it) }) return null
        if (topic in REFUSED) return null
        if (topic.split(' ').any { it in REFUSED_WORDS }) return null
        return topic
    }

    /** True for a word that grants the pending request. */
    fun isYes(raw: String): Boolean {
        val text = normal(raw)
        if (text.isBlank()) return false
        if (text in YES or text.replace(".", "").trim() in YES) return true
        // "yes, go ahead" and "haan kar do" start with the word that decides it.
        return YES_PREFIX.any { text == it || text.startsWith("$it ") }
    }

    /** True for a word that withdraws it. Checked after [isYes] so "haan nahi" is a yes. */
    fun isNo(raw: String): Boolean {
        val text = normal(raw)
        if (text.isBlank()) return false
        if (text in NO) return true
        return NO_PREFIX.any { text == it || text.startsWith("$it ") }
    }

    /** "what have you learned" — he should be able to show the shelf out loud. */
    fun asksWhatHeKnows(raw: String): Boolean {
        val text = normal(raw)
        if (text.isBlank()) return false
        return text in KNOWS ||
            text.contains("what have you learned") ||
            text.contains("what did you learn") ||
            text.contains("what do you know") ||
            text.contains("kya seekha") ||
            text.contains("kya sikha") ||
            text.contains("kya jaanta") ||
            text.contains("kya janta") ||
            text.contains("क्या सीखा") ||
            text.contains("क्या जानते")
    }

    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "haan", "han", "ha", "haanji",
        "bilkul", "theek hai", "thik hai", "chalega", "karo", "kar do", "go ahead", "do it",
        "look it up", "find it", "hmm", "हाँ", "हां", "ठीक है", "करो", "कर दो", "बिल्कुल",
    )

    private val YES_PREFIX = setOf(
        "yes", "yeah", "sure", "ok", "okay", "haan", "han", "haanji", "theek hai", "thik hai",
        "go ahead", "kar do", "karo", "batao", "बताओ", "हाँ", "हां", "करो",
    )

    private val NO = setOf(
        "no", "nope", "nah", "nahi", "nahin", "nai", "na", "not now", "leave it", "skip",
        "skip it", "cancel", "chhodo", "chodo", "rehne do", "mat karo", "dont", "do not",
        "नहीं", "ना", "छोड़ो", "रहने दो", "मत करो",
    )

    private val NO_PREFIX = setOf(
        "no", "nope", "nahi", "nahin", "not now", "leave it", "chhodo", "chodo", "rehne do",
        "don t", "do not", "नहीं", "छोड़ो",
    )

    private val KNOWS = setOf(
        "knowledge", "knowledge shelf", "what you know", "show knowledge", "your knowledge",
        "kya jaanta hai", "kya janta hai", "kya seekha", "kya sikha",
    )

    // ---------------------------------------------------------------------------------------------
    // Saying it
    // ---------------------------------------------------------------------------------------------

    fun wikiOf(lang: Lang): String = if (lang.replyLang == Lang.HI) "hi" else "en"

    private fun pick(lang: Lang, en: String, hi: String, hinglish: String): String =
        when (lang.replyLang) {
            Lang.HI -> hi
            Lang.HINGLISH -> hinglish
            else -> en
        }

    /** The permission question. Short, plain, and clear about what leaves the phone. */
    fun ask(lang: Lang, topic: String): String = pick(
        lang,
        "Shall I look up $topic on the internet?",
        "$topic के बारे में इंटरनेट पर देख लूँ?",
        "$topic ke baare mein internet par dekh loon?",
    )

    /** The answer, attributed. Wikipedia's licence asks for the source, and it is also honest. */
    fun answered(lang: Lang, summary: String, source: String): String {
        val named = when {
            source.equals("WIKIPEDIA", ignoreCase = true) -> pick(
                lang,
                "Wikipedia says: $summary",
                "विकिपीडिया के अनुसार: $summary",
                "Wikipedia ke hisaab se: $summary",
            )
            source.equals("DICTIONARY", ignoreCase = true) -> pick(
                lang,
                "The dictionary says: $summary",
                "शब्दकोश के अनुसार: $summary",
                "Dictionary ke hisaab se: $summary",
            )
            else -> summary
        }
        return named
    }

    fun notFound(lang: Lang, topic: String): String = pick(
        lang,
        "Nothing solid came back for $topic. Say it another way and I will try again.",
        "$topic पर कुछ पक्का नहीं मिला। दूसरे शब्दों में कहिए, फिर कोशिश करूँगा।",
        "$topic par kuch pakka nahi mila. Doosre shabdon mein kahiye, phir koshish karunga.",
    )

    fun offline(lang: Lang): String = pick(
        lang,
        "I cannot reach the internet right now. What I already know, I can still answer.",
        "अभी इंटरनेट नहीं मिल रहा। जो मुझे पहले से पता है, वह बता सकता हूँ।",
        "Abhi internet nahi mil raha. Jo mujhe pehle se pata hai, woh bata sakta hoon.",
    )

    fun declined(lang: Lang): String = pick(
        lang,
        "Leaving it.",
        "छोड़ दिया।",
        "Chhod diya.",
    )

    fun noTopic(lang: Lang): String = pick(
        lang,
        "What should I look up?",
        "किस बारे में देखूँ?",
        "Kis baare mein dekhun?",
    )

    fun shelfEmpty(lang: Lang): String = pick(
        lang,
        "Nothing yet. Ask me something — \u201cwhat is a black hole\u201d.",
        "अभी कुछ नहीं सीखा। कुछ पूछिए — \u201cब्लैक होल क्या है\u201d।",
        "Abhi kuch nahi seekha. Kuch puchiye — \u201cblack hole kya hai\u201d.",
    )

    fun shelfList(lang: Lang, count: Int, topics: List<String>): String {
        val list = topics.joinToString(", ")
        return pick(
            lang,
            "I have learned $count things. $list.",
            "मैंने $count चीज़ें सीखी हैं। $list।",
            "Maine $count cheezein seekhi hain. $list.",
        )
    }

    /** The desk's own words, used by the settings screen. */
    fun dialCaption(mode: ResearchDesk.Mode): String = mode.caption
}
