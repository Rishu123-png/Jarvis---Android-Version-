package com.jarvis.assistant.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.jarvis.assistant.voice.Lang
import java.util.Locale as JLocale

/**
 * What he knows about making a reel land.
 *
 * This is not a model making things up: it is a short, written-down set of platform rules — the
 * caption preview cut, the hard limits, what the ranking signals actually are, why a wall of generic
 * hashtags stopped helping, and what a hook has to do in the first second. The numbers here are the
 * kind that change, so they live in one file, in one place, and every one of them is named.
 *
 * Two rules of his own sit on top:
 *
 *  * **He drafts, he does not publish.** There is no posting path in this app, and there will not be
 *    one that fakes it. A caption is handed over as text — copied to the clipboard — and the operator
 *    posts it. Anything else would either need the official Graph API (a business account and app
 *    review) or an unofficial endpoint that breaks on someone else's schedule.
 *  * **He checks his own work.** [lint] runs over every caption he writes, and contradicts him out
 *    loud: a hook that gets cut off, a hashtag wall, no call to action, a caption that reads like a
 *    press release.
 *
 * All of the writing and checking is pure, so it is covered by tests without a phone.
 */
object Creator {

    // ---------------------------------------------------------------------------------------------
    // The rules, in one place
    // ---------------------------------------------------------------------------------------------

    /** How many characters Instagram shows before "more". Everything that matters goes before it. */
    const val PREVIEW_CHARS = 125

    /** The hard ceiling on a caption. */
    const val MAX_CAPTION_CHARS = 2_200

    /** The ceiling on hashtags. 30 is the limit; a wall of 30 stopped working years ago. */
    const val MAX_HASHTAGS = 30

    const val RECOMMENDED_HASHTAGS_LOW = 3
    const val RECOMMENDED_HASHTAGS_HIGH = 8

    /** One idea, stated as a rule a person could argue with, with the reason attached. */
    data class Rule(val title: String, val detail: String)

    val RULES: List<Rule> = listOf(
        Rule(
            "The first line is the whole caption",
            "Only about the first $PREVIEW_CHARS characters show before \u201Cmore\u201D. If the hook " +
                "does not land there, the rest is never read.",
        ),
        Rule(
            "Watch time is the signal",
            "For a reel, how long people watch and whether they watch it twice outweighs likes. " +
                "A short reel that loops beats a long one that gets skipped.",
        ),
        Rule(
            "Sends and saves beat likes",
            "A share to a friend or a save is worth far more than a like, because both mean the " +
                "person wanted it later. Write something worth forwarding.",
        ),
        Rule(
            "Hashtags are indexing, not magic",
            "They help the post be found by topic. Thirty generic tags do not add reach; a handful " +
                "of specific ones do. $RECOMMENDED_HASHTAGS_LOW\u2013$RECOMMENDED_HASHTAGS_HIGH is the honest range.",
        ),
        Rule(
            "The caption is searchable",
            "The words in the caption and the on-screen text are read as keywords, so say what the " +
                "reel is actually about instead of writing only emoji.",
        ),
        Rule(
            "Sound is a discovery surface",
            "A trending track can put a reel in front of people who do not follow you; original " +
                "audio helps the people who do. Pick one on purpose.",
        ),
        Rule(
            "Watermarks cost reach",
            "A reel carrying another app's watermark reads as a repost, and reposts are ranked lower.",
        ),
        Rule(
            "Answer every comment in the first hour",
            "Early comments are engagement while the post is being tested. Replying counts too.",
        ),
        Rule(
            "Consistency beats bursts",
            "Three posts a week, reliably, outperforms twelve in one day and then silence \u2014 the " +
                "audience is a habit, not a spike.",
        ),
    )

    /** Phrasings that get a post quietly down-ranked, and which a person would not say to a friend. */
    private val RISKY_PHRASES = listOf(
        "follow for follow", "f4f", "l4l", "like for like", "dm for promo", "dm for collab",
        "buy followers", "grow fast", "guaranteed reach", "link in bio for free",
    )

    /** Generic tags that do nothing but fill the cap. */
    private val GENERIC_TAGS = setOf(
        "love", "instagood", "follow", "like", "instadaily", "photooftheday", "picoftheday",
        "happy", "fun", "life", "lifestyle", "trending", "viral", "explore", "explorepage",
        "reels", "reelsindia", "instamood", "tbt", "followme", "comment", "share",
    )

    // ---------------------------------------------------------------------------------------------
    // A caption
    // ---------------------------------------------------------------------------------------------

    data class Caption(
        val hook: String,
        val body: List<String>,
        val cta: String,
        val hashtags: List<String>,
    ) {
        /** The text that would actually be posted. */
        val rendered: String
            get() = buildString {
                append(hook.trim())
                if (body.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    body.forEachIndexed { index, line ->
                        append(line.trim())
                        if (index != body.lastIndex) appendLine()
                    }
                }
                if (cta.isNotBlank()) {
                    appendLine()
                    appendLine()
                    append(cta.trim())
                }
                if (hashtags.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    append(hashtags.joinToString(" ") { "#$it" })
                }
            }.trim()

        val length: Int get() = rendered.length

        /** True when the hook survives the "more" fold. */
        val hookFits: Boolean get() = hook.length <= PREVIEW_CHARS
    }

    enum class Severity { BLOCKER, WARNING, NICE }

    data class Issue(val severity: Severity, val message: String)

    /**
     * Reads the caption back the way the platform will.
     *
     * Blockers are things that would actually break the post (over the character cap, over the
     * hashtag cap). Warnings are things that will cost reach. Nice-to-haves are polish.
     */
    fun lint(caption: Caption): List<Issue> {
        val issues = mutableListOf<Issue>()
        val text = caption.rendered

        if (text.length > MAX_CAPTION_CHARS) {
            issues.add(Issue(Severity.BLOCKER, "Over the ${MAX_CAPTION_CHARS} character limit \u2014 it will be cut."))
        }
        if (caption.hashtags.size > MAX_HASHTAGS) {
            issues.add(Issue(Severity.BLOCKER, "More than $MAX_HASHTAGS hashtags \u2014 the post drops the extras."))
        }
        if (!caption.hookFits) {
            issues.add(
                Issue(
                    Severity.WARNING,
                    "The hook is ${caption.hook.length} characters, so it gets cut before " +
                        "\u201Cmore\u201D at $PREVIEW_CHARS.",
                ),
            )
        }
        if (caption.hook.isBlank()) {
            issues.add(Issue(Severity.BLOCKER, "No first line \u2014 the fold has nothing to show."))
        }
        if (caption.cta.isBlank()) {
            issues.add(Issue(Severity.WARNING, "No call to action. Saves and shares need somewhere to go."))
        }
        if (caption.hashtags.size < RECOMMENDED_HASHTAGS_LOW) {
            issues.add(
                Issue(
                    Severity.WARNING,
                    "Only ${caption.hashtags.size} hashtags \u2014 the post is harder to file by topic.",
                ),
            )
        }
        val generic = caption.hashtags.count { it.lowercase(JLocale.US) in GENERIC_TAGS }
        if (caption.hashtags.isNotEmpty() && generic > caption.hashtags.size / 2) {
            issues.add(
                Issue(
                    Severity.WARNING,
                    "$generic of ${caption.hashtags.size} hashtags are generic. Specific ones index better.",
                ),
            )
        }
        val risky = RISKY_PHRASES.filter { text.lowercase(JLocale.US).contains(it) }
        if (risky.isNotEmpty()) {
            issues.add(Issue(Severity.BLOCKER, "Contains \u201C${risky.first()}\u201D, which gets posts down-ranked."))
        }
        val emoji = text.count { Character.getType(it) == Character.OTHER_SYMBOL.toInt() }
        if (emoji > 8) {
            issues.add(Issue(Severity.NICE, "$emoji symbols is a lot \u2014 it reads as noise."))
        }
        // Measured over the written words only: hashtags are always lowercase, so including them
        // silently diluted the ratio and let a shouted caption pass.
        val written = (listOf(caption.hook) + caption.body + listOf(caption.cta)).joinToString(" ")
        val letters = written.count { it.isLetter() }
        val caps = written.count { it.isLetter() && it.isUpperCase() }
        if (letters >= 20 && caps.toDouble() / letters > 0.5) {
            issues.add(Issue(Severity.WARNING, "Mostly capitals \u2014 it reads as shouting."))
        }
        if (text.isNotBlank() && !text.any { it.isLetter() }) {
            issues.add(Issue(Severity.WARNING, "No words at all, so there is nothing to index."))
        }
        return issues
    }

    /** Only the issues that would actually break the post. */
    fun blockers(caption: Caption): List<Issue> = lint(caption).filter { it.severity == Severity.BLOCKER }

    // ---------------------------------------------------------------------------------------------
    // Writing one
    // ---------------------------------------------------------------------------------------------

    /** What the reel is about, in the operator's own words. */
    data class Brief(
        val topic: String,
        val notes: List<String> = emptyList(),
        val lang: Lang = Lang.HINGLISH,
        val niche: String = "",
    )

    /**
     * A caption built on the house rules, without a model.
     *
     * Deliberately plain: a hook that fits the fold, the operator's own points as the body, one
     * question as the call to action, and a hashtag mix that is specific first. It is a starting
     * point to edit, and it says so — inventing a story about a reel nobody has described yet would
     * be worse than being boring.
     */
    fun draft(brief: Brief, seed: Int = 0): Caption {
        val topic = brief.topic.trim().ifBlank { "aaj ka reel" }
        val hooks = when (brief.lang.replyLang) {
            Lang.HINGLISH -> listOf(
                "$topic \u2014 jo maine seekha, 30 second mein",
                "$topic ke 3 cheezein jo koi nahi batata",
                "Ye reel $topic ke baare mein hai, aur ye worth hai",
            )
            Lang.HI -> listOf(
                "$topic \u2014 मैंने जो सीखा, 30 सेकंड में",
                "$topic की 3 बातें जो कोई नहीं बताता",
            )
            else -> listOf(
                "$topic \u2014 what I actually learned, in 30 seconds",
                "Three things about $topic nobody tells you",
                "This reel is about $topic, and it is worth the minute",
            )
        }
        val ctas = when (brief.lang.replyLang) {
            Lang.HINGLISH -> listOf(
                "Tumhara experience kya hai? Comment mein batao.",
                "Kis point pe sehmat ho? Neeche likho.",
                "Agar kaam ki lagi to save kar lo, baad mein kaam aayegi.",
            )
            Lang.HI -> listOf(
                "आपका अनुभव क्या है? कमेंट में बताइए।",
                "काम की लगी तो सेव कर लीजिए।",
            )
            else -> listOf(
                "What has been your experience? Tell me below.",
                "Save this if it was useful \u2014 you will want it later.",
                "Which part did you disagree with? Say it in the comments.",
            )
        }
        val hook = hooks[seed.mod(hooks.size)].take(PREVIEW_CHARS)
        val cta = ctas[seed.mod(ctas.size)]
        val body = brief.notes.map { it.trim() }.filter { it.isNotBlank() }.take(4)

        val caption = Caption(
            hook = hook,
            body = body,
            cta = cta,
            hashtags = hashtagsFor(topic, brief.niche, seed),
        )
        // A draft must never ship with a blocker, so it trims itself until it does not.
        return enforce(caption)
    }

    /** Trims until nothing would break the post. The caption is a proposal, not a gamble. */
    fun enforce(caption: Caption): Caption {
        var current = caption.copy(hashtags = caption.hashtags.map { tag(it) }.filter { it.isNotBlank() }.distinct())
        if (current.hashtags.size > MAX_HASHTAGS) {
            current = current.copy(hashtags = current.hashtags.take(MAX_HASHTAGS))
        }
        if (current.hook.length > PREVIEW_CHARS) {
            current = current.copy(hook = current.hook.take(PREVIEW_CHARS).trimEnd())
        }
        if (current.length > MAX_CAPTION_CHARS) {
            // Drop body lines from the end first: the hook and the call to action are the parts that
            // do the work.
            var body = current.body
            while (current.copy(body = body).length > MAX_CAPTION_CHARS && body.isNotEmpty()) {
                body = body.dropLast(1)
            }
            current = current.copy(body = body)
        }
        return current
    }

    /**
     * A hashtag mix: two or three from the topic, a couple from the niche, one broad.
     *
     * Built from the words already spoken, so it needs no network and cannot drift into a random
     * wall of tags. Generic tags are allowed but capped, because they index nothing on their own.
     */
    fun hashtagsFor(topic: String, niche: String = "", seed: Int = 0): List<String> {
        val topicTags = tagify(topic).filter { it !in GENERIC_TAGS }.take(3)
        val nicheTags = tagify(niche).filter { it !in GENERIC_TAGS }.take(2)
        val broad = listOf("reels", "reelsindia", "explore", "trending")
        val oneBroad = broad[seed.mod(broad.size)]
        val combined = (topicTags + nicheTags + listOf(oneBroad) + listOf("reelkarofeelkaro"))
            .map { tag(it) }
            .filter { it.isNotBlank() }
            .distinct()
        return combined.take(RECOMMENDED_HASHTAGS_HIGH)
    }

    /** One hashtag: letters and digits only, no spaces, no punctuation, no leading hash. */
    fun tag(raw: String): String = raw.trim()
        .removePrefix("#")
        .lowercase(JLocale.US)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
        .take(30)

    /** The words of a phrase, each fit to be a tag. */
    fun tagify(phrase: String): List<String> {
        val words = phrase.lowercase(JLocale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
        if (words.isEmpty()) return emptyList()
        val tags = mutableListOf(words.take(3).joinToString(""))
        // The whole phrase is usually the better tag ("reel edit" -> #reeledit), and the individual
        // words are useful when the phrase is too long to be one.
        words.filter { it.length >= 4 }.take(2).forEach { tags.add(it) }
        return tags.map { tag(it) }.filter { it.isNotBlank() }.distinct()
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "kya", "hai", "ka", "ki", "ke", "ko",
        "mein", "main", "mera", "meri", "aaj", "kal", "par", "tha", "thi", "ho", "hain", "se",
    )

    // ---------------------------------------------------------------------------------------------
    // Beyond the caption: what makes the reel itself work
    // ---------------------------------------------------------------------------------------------

    /** The first-second checklist, in the order a viewer experiences it. */
    fun reelChecklist(lang: Lang = Lang.HINGLISH): List<String> = when (lang.replyLang) {
        Lang.HINGLISH -> listOf(
            "Pehle 1\u20133 second mein hook \u2014 text aur awaaz dono",
            "7\u201315 second sabse safe length, loop pe kaam kare",
            "On-screen text chhota, ek line mein ek baat",
            "Trending ya original audio \u2014 jaan-boojhkar chuno",
            "Koi aur app ka watermark nahi",
            "Aakhir mein ek hi CTA \u2014 save, share, ya comment",
            "Pehle ghante ke comments ka jawab do",
        )
        Lang.HI -> listOf(
            "पहले 1–3 सेकंड में हुक — टेक्स्ट और आवाज़ दोनों",
            "7–15 सेकंड सबसे सुरक्षित लंबाई",
            "ऑन-स्क्रीन टेक्स्ट छोटा, एक पंक्ति में एक बात",
            "किसी और ऐप का वॉटरमार्क नहीं",
            "अंत में एक ही CTA",
        )
        else -> listOf(
            "Hook inside the first 1\u20133 seconds, in text and in sound",
            "7\u201315 seconds is the safe length, and it should loop",
            "Short on-screen text \u2014 one thought per line",
            "Trending or original audio, chosen on purpose",
            "No other app's watermark",
            "One call to action at the end",
            "Answer the first hour of comments",
        )
    }

    /**
     * When to post, from the operator's own numbers rather than a blog post.
     *
     * Nulls are honest: with nothing to read, he says nothing instead of suggesting "9 AM because
     * the internet said so". That is the state of this build — the account connections were removed
     * on purpose, so no caller feeds this today; the shape is kept because it is what a timing line
     * has to look like when real numbers exist.
     */
    fun bestWindowLine(day: String?, hour: Int?, lang: Lang): String? {
        if (day.isNullOrBlank() && hour == null) return null
        val whenText = listOfNotNull(
            day?.takeIf { it.isNotBlank() }?.let { it.lowercase(JLocale.US).replaceFirstChar { c -> c.uppercase() } },
            hour?.let { formatHour(it) },
        ).joinToString(", ")
        if (whenText.isBlank()) return null
        return when (lang.replyLang) {
            Lang.HINGLISH -> "Tumhare apne data ke hisaab se sabse accha time: $whenText."
            Lang.HI -> "आपके अपने डेटा के हिसाब से सबसे अच्छा समय: $whenText।"
            else -> "Your own numbers say the best time is $whenText."
        }
    }

    private fun formatHour(hour: Int): String {
        val h = ((hour % 24) + 24) % 24
        val suffix = if (h < 12) "AM" else "PM"
        val display = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$display $suffix"
    }

    // ---------------------------------------------------------------------------------------------
    // Publishing, honestly
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether he can post it himself. He cannot, and the reason is not a limitation of this code.
     *
     * Instagram has no public way for an ordinary app to publish on someone's behalf. The official
     * route is the Graph API with a business or creator account, an app registered to a developer
     * account, and a review; the unofficial route is the private endpoint the app itself uses, which
     * breaks whenever Meta changes anything and can get an account restricted. Pretending otherwise
     * would be the one thing this app does not do.
     */
    fun canPost(): Boolean = false

    fun publishLine(lang: Lang): String = when (lang.replyLang) {
        Lang.HINGLISH ->
            "Caption aur hashtags taiyaar hain, clipboard mein copy kar diya. Post karna tumhe padega " +
                "\u2014 Instagram kisi app ko apni taraf se post karne nahi deta, aur private API se " +
                "account restrict ho sakta hai."
        Lang.HI ->
            "कैप्शन और हैशटैग तैयार हैं, क्लिपबोर्ड में कॉपी कर दिया। पोस्ट आपको करना होगा \u2014 " +
                "Instagram किसी ऐप को अपनी तरफ़ से पोस्ट करने नहीं देता।"
        else ->
            "Caption and hashtags are ready and copied to the clipboard. You will have to post it \u2014 " +
                "Instagram does not let an ordinary app publish on your behalf, and the unofficial " +
                "route can get an account restricted."
    }

    /** What he needs from the operator before the draft is any good. */
    fun missingInfo(brief: Brief): List<String> {
        val out = mutableListOf<String>()
        if (brief.topic.isBlank()) out.add("What the reel is about")
        if (brief.notes.isEmpty()) out.add("The three points it actually makes")
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Handing it over
    // ---------------------------------------------------------------------------------------------

    /**
     * Puts the caption on the clipboard.
     *
     * This is the whole publication path: the text leaves through the operator's own hands. Returns
     * false when the platform refuses, so the reply can say "copy it yourself" instead of claiming
     * something that did not happen.
     */
    fun copyToClipboard(context: Context, text: String, label: String = "JARVIS caption"): Boolean {
        if (text.isBlank()) return false
        return try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Everything he would tell the model about a caption, so its draft follows the same rules. */
    fun promptBlock(): String = buildString {
        appendLine("CAPTION RULES (from the platform, not from taste):")
        appendLine("- The first $PREVIEW_CHARS characters are all that show before \u201Cmore\u201D. The hook goes there.")
        appendLine("- Ceiling: $MAX_CAPTION_CHARS characters, $MAX_HASHTAGS hashtags.")
        appendLine("- Hashtags are indexing: $RECOMMENDED_HASHTAGS_LOW\u2013$RECOMMENDED_HASHTAGS_HIGH specific ones beat a wall of generic ones.")
        appendLine("- Say what the reel is about in words, because the caption is read as keywords.")
        appendLine("- One call to action, and it should ask for a save, a share or a comment.")
        appendLine("- Never write \u201Cfollow for follow\u201D, \u201Cdm for promo\u201D or guarantee reach.")
        appendLine("- Write in the operator's language and register: Hinglish stays Roman, never Devanagari.")
        appendLine("- Do not invent facts about a reel nobody described. Ask, or stay general.")
    }

    /** True when the sentence is asking for caption/hashtag work rather than a device action. */
    fun wantsCaption(clean: String): Boolean {
        val text = clean.lowercase(JLocale.US)
        if (text.isBlank()) return false
        val noun = text.contains("caption") || text.contains("hashtag") || text.contains("hashtags") ||
            text.contains("caption idea") || text.contains("reel ka") || text.contains("reel ke")
        val verb = text.contains("banao") || text.contains("bana do") || text.contains("likho") ||
            text.contains("write") || text.contains("draft") || text.contains("suggest") ||
            text.contains("chahiye") || text.contains("help") || text.contains("check")
        return noun && (verb || text.contains("caption") || text.contains("hashtag"))
    }

    /** True when the sentence asks him to publish, which he will not do. */
    fun wantsPublish(clean: String): Boolean {
        val text = clean.lowercase(JLocale.US)
        return (text.contains("post") || text.contains("publish") || text.contains("upload") ||
            text.contains("daal do") || text.contains("daaldo")) &&
            (text.contains("instagram") || text.contains("insta") || text.contains("reel"))
    }

    /** True when the sentence names work that belongs to another app's own screens. */
    fun wantsForeignApp(clean: String): Boolean {
        val text = clean.lowercase(JLocale.US)
        val foreign = text.contains("arena") || text.contains("canva") || text.contains("influesh") ||
            text.contains("inflush") || text.contains("premiere") || text.contains("capcut") ||
            text.contains("browser") || text.contains("chrome") || text.contains("website")
        val action = text.contains("open") || text.contains("kholo") || text.contains("clear") ||
            text.contains("workspace") || text.contains("history") || text.contains("trending")
        return foreign && action
    }

    /** The topic out of "caption banao about X" / "X ka caption likho". */
    fun topicOf(clean: String): String {
        val patterns = listOf(
            Regex("(?:caption|hashtags?)\\s+(?:for|about|on|ke liye|ka)\\s+(.+)$"),
            Regex("(.+?)\\s+(?:ka|ke|ki)\\s+(?:caption|hashtag)"),
            Regex("(?:reel|post)\\s+(?:on|about|ke baare mein|par)\\s+(.+)$"),
            Regex("(?:for|about)\\s+(.+)$"),
        )
        for (pattern in patterns) {
            val match = pattern.find(clean) ?: continue
            val value = match.groupValues[1]
                .replace(Regex("(?i)\\b(banao|bana do|likho|likh do|write|draft|suggest|chahiye|please)\\b"), "")
                .trim()
            if (value.isNotBlank()) return value.take(80)
        }
        return ""
    }

    /** A short label for the HUD card. */
    fun titleFor(caption: Caption): String =
        "CAPTION \u00B7 ${caption.hashtags.size} TAGS".uppercase(JLocale.US)

    /**
     * Reads a caption back into its parts, for checking work he did not compose himself.
     *
     * A model's draft arrives as one blob; the hashtags are at the end, the hook is the first line,
     * and the last non-hashtag line is usually the call to action. Approximate on purpose — it is
     * only used to run [lint] over it, and being wrong costs a warning, not a post.
     */
    fun parse(text: String): Caption {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Caption("", emptyList(), "", emptyList())

        val tagLine = lines.lastOrNull { line -> line.split(' ').all { it.startsWith("#") && it.length > 1 } }
        val withoutTags = lines.filter { it != tagLine }
        val tags = tagLine?.split(' ')?.map { tag(it) }?.filter { it.isNotBlank() } ?: emptyList()

        val hook = withoutTags.firstOrNull().orEmpty()
        val cta = if (withoutTags.size > 2) withoutTags.last() else ""
        val body = withoutTags.drop(1).dropLast(if (cta.isBlank()) 0 else 1)
        return Caption(hook = hook, body = body, cta = cta, hashtags = tags)
    }
}
