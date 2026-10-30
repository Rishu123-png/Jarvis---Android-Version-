package com.jarvis.assistant.voice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * The part that makes him answer as family instead of as a device.
 *
 * A command router can be correct and still feel cold: "Torch on." is true, and it is also what a
 * switch says. What a person does is notice. They notice that it is one in the morning, that the
 * operator's words sound tired, that this is the eleventh day in a row they have talked, and they
 * answer the *person* before they answer the request.
 *
 * Three pieces, and none of them are invented:
 *
 *  - **affect** — how the operator sounds right now, read from their own words in English, Hindi and
 *    Hinglish. Never claimed as certainty: it is a reading, not a diagnosis.
 *  - **bond** — how long this has been going on: first day, days together, streak, turn count. Real
 *    arithmetic over real timestamps, kept on the phone.
 *  - **mood** — his own tone for this reply, derived from the hour, the affect and the bond. It is a
 *    *register*, not a claim of inner life: concerned means slower and gentler, playful means
 *    quicker, sleepy means low and quiet. Nothing here ever claims to feel something it cannot.
 *
 * Care lines are only said when a real signal supports them — a battery that is actually at 8%, a
 * water log that is actually at zero, an hour that is actually past midnight. He cannot be caught
 * making up a reason to be kind.
 *
 * Everything that decides anything is pure ([affect], [moodOf], [daysBetween], [acknowledge],
 * [afterCommand], [careLine]) so the whole emotional model is testable without a phone.
 */
object Feelings {

    private const val PREFS = "jarvis_feelings"
    private const val KEY_ENABLED = "feelings_enabled"
    private const val KEY_FIRST_MET = "first_met"
    private const val KEY_TURNS = "turns"
    private const val KEY_LAST_SEEN = "last_seen"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_DAY = "last_day"
    private const val KEY_LAST_AFFECT = "last_affect"
    private const val KEY_LAST_AFFECT_AT = "last_affect_at"

    /** How long a strong feeling stays worth asking about. */
    const val CARRY_OVER_MS = 12 * 60 * 60 * 1000L

    // ---------------------------------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------------------------------

    /** How the operator sounds. Read from their words; never presented as a fact about them. */
    enum class Affect(val id: String) {
        NEUTRAL("neutral"),
        TIRED("tired"),
        SAD("sad"),
        ANGRY("angry"),
        ANXIOUS("anxious"),
        HAPPY("happy"),
        EXCITED("excited"),
        LOVING("loving"),
        ;

        val isHeavy: Boolean
            get() = this == SAD || this == ANXIOUS || this == ANGRY || this == TIRED

        val isBright: Boolean
            get() = this == HAPPY || this == EXCITED || this == LOVING

        companion object {
            fun fromId(id: String?): Affect = entries.firstOrNull { it.id == id } ?: NEUTRAL
        }
    }

    /** His register for one reply. A tone of voice, not a claim about his feelings. */
    enum class Mood(val id: String, val label: String) {
        CALM("calm", "STEADY"),
        WARM("warm", "WARM"),
        PLAYFUL("playful", "LIGHT"),
        CONCERNED("concerned", "CAREFUL"),
        SLEEPY("sleepy", "QUIET"),
        ;

        companion object {
            fun fromId(id: String?): Mood = entries.firstOrNull { it.id == id } ?: CALM
        }
    }

    /** How he speaks: pitch and rate, applied on top of the chosen voice. */
    data class Tone(val pitch: Float, val rate: Float) {
        companion object {
            val NEUTRAL = Tone(1f, 1f)

            fun forMood(mood: Mood): Tone = when (mood) {
                Mood.CONCERNED -> Tone(pitch = 0.96f, rate = 0.92f)
                Mood.SLEEPY -> Tone(pitch = 0.93f, rate = 0.90f)
                Mood.PLAYFUL -> Tone(pitch = 1.05f, rate = 1.06f)
                Mood.WARM -> Tone(pitch = 1.00f, rate = 0.98f)
                Mood.CALM -> NEUTRAL
            }
        }
    }

    /**
     * How long this has been going on.
     *
     * [turns] is a lifetime count, not a session one: "you have asked me 400 things" is something a
     * person who was there would know.
     */
    data class Bond(
        val firstMetAt: Long = 0L,
        val turns: Int = 0,
        val lastSeenAt: Long = 0L,
        val streakDays: Int = 0,
        val daysTogether: Int = 0,
    ) {
        /**
         * True on the very first day, when he should introduce himself rather than assume.
         *
         * Derived, never stored: a copy of this carried in a value object goes stale the moment the
         * clock passes midnight, and "he thinks it is still day one" is exactly the kind of wrong
         * that reads as not paying attention.
         */
        val isNew: Boolean
            get() = firstMetAt <= 0L || daysTogether <= 1
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    /** On by default. Warmth is the point of the app; a switch exists for a quiet week. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Bond
    // ---------------------------------------------------------------------------------------------

    fun bond(context: Context, now: Long = System.currentTimeMillis()): Bond {
        val store = prefs(context)
        val firstMet = store.getLong(KEY_FIRST_MET, 0L)
        val days = if (firstMet <= 0L) 0 else daysBetween(firstMet, now)
        return Bond(
            firstMetAt = firstMet,
            turns = store.getInt(KEY_TURNS, 0),
            lastSeenAt = store.getLong(KEY_LAST_SEEN, 0L),
            streakDays = store.getInt(KEY_STREAK, 0),
            daysTogether = days,
        )
    }

    /**
     * Called once per finished turn.
     *
     * The streak is the only clever bit: same day keeps it, yesterday extends it, anything longer
     * starts again at one. A streak that survived a two-week gap would be a lie told with numbers.
     */
    fun recordTurn(
        context: Context,
        affect: Affect = Affect.NEUTRAL,
        now: Long = System.currentTimeMillis(),
    ) {
        val store = prefs(context)
        val firstMet = store.getLong(KEY_FIRST_MET, 0L).takeIf { it > 0L } ?: now
        val lastDay = store.getString(KEY_LAST_DAY, null)
        val today = dayKey(now)

        val streak = when {
            lastDay == null -> 1
            lastDay == today -> store.getInt(KEY_STREAK, 1).coerceAtLeast(1)
            lastDay == dayKey(now - 24 * 60 * 60 * 1000L) -> store.getInt(KEY_STREAK, 0) + 1
            else -> 1
        }

        val editor = store.edit()
            .putLong(KEY_FIRST_MET, firstMet)
            .putInt(KEY_TURNS, store.getInt(KEY_TURNS, 0) + 1)
            .putLong(KEY_LAST_SEEN, now)
            .putInt(KEY_STREAK, streak)
            .putString(KEY_LAST_DAY, today)

        if (affect != Affect.NEUTRAL) {
            editor.putString(KEY_LAST_AFFECT, affect.id)
            editor.putLong(KEY_LAST_AFFECT_AT, now)
        }
        editor.apply()
    }

    /**
     * A heavy feeling he heard recently, so he can ask about it once — and only once.
     *
     * Guilt is the failure mode here: asking "are you still sad?" every single time is not care, it
     * is nagging. So the answer is consumed when it is used ([clearCarryOver]) and it expires.
     */
    fun carryOver(
        context: Context,
        now: Long = System.currentTimeMillis(),
        /** Ignore anything newer than this: it belongs to the turn that is still happening. */
        minAgeMs: Long = 0L,
    ): Affect? {
        val store = prefs(context)
        val at = store.getLong(KEY_LAST_AFFECT_AT, 0L)
        if (at <= 0L || now - at > CARRY_OVER_MS) return null
        if (now - at < minAgeMs) return null
        val affect = Affect.fromId(store.getString(KEY_LAST_AFFECT, null))
        return if (affect.isHeavy) affect else null
    }

    fun clearCarryOver(context: Context) {
        prefs(context).edit().remove(KEY_LAST_AFFECT).remove(KEY_LAST_AFFECT_AT).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Whole days between two instants, counting calendar days rather than 24-hour blocks. */
    fun daysBetween(from: Long, to: Long): Int {
        if (from <= 0L || to <= from) return 0
        val start = startOfDay(from)
        val end = startOfDay(to)
        return max(0, ((end - start) / (24 * 60 * 60 * 1000L)).toInt())
    }

    private fun startOfDay(at: Long): Long = try {
        Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    } catch (t: Throwable) {
        at
    }

    private fun dayKey(at: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
    } catch (t: Throwable) {
        "unknown"
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the operator
    // ---------------------------------------------------------------------------------------------

    /** Words that settle a feeling on their own, checked as whole phrases. */
    private val TIRED = listOf(
        "tired", "exhausted", "drained", "no energy", "sleepy", "burnt out", "burned out",
        "thak gaya", "thak gayi", "thaka", "thaki", "thak", "neend aa rahi", "neend", "sust",
        "kamzor", "थक", "नींद", "सुस्त",
    )
    private val SAD = listOf(
        "sad", "unhappy", "feeling low", "feel low", "down today", "depressed", "lonely", "alone",
        "hurt", "crying", "i cried", "broken", "hopeless", "udaas", "dukhi", "bura lag", "mann nahi",
        "mann udaas", "akela", "akeli", "rona", "ro raha", "ro rahi", "dukh",
        "उदास", "दुखी", "अकेला", "रोना",
    )
    private val ANGRY = listOf(
        "angry", "furious", "annoyed", "irritated", "frustrated", "pissed", "fed up", "hate",
        "gussa", "gusse", "chid", "chidd", "bakwas", "pareshan", "sar phata",
        "गुस्सा", "चिढ़", "परेशान",
    )
    private val ANXIOUS = listOf(
        "anxious", "anxiety", "worried", "worry", "nervous", "scared", "afraid", "stress",
        "stressed", "pressure", "panic", "tension", "ghabra", "dar lag", "darr", "chinta",
        "टेंशन", "चिंता", "डर", "घबरा",
    )
    private val HAPPY = listOf(
        "happy", "great", "good news", "feeling good", "better now", "relieved", "proud",
        "khush", "khushi", "achha laga", "accha laga", "maza aaya", "badhiya", "sukoon",
        "खुश", "अच्छा लगा", "मज़ा",
    )
    private val EXCITED = listOf(
        // Three spellings of the same phrase: the apostrophe survives neither the tokeniser nor a
        // recognizer, so all the shapes it can arrive in are listed.
        "excited", "can't wait", "cant wait", "can t wait", "so ready", "yay", "party", "celebrate",
        "mazaa aayega", "maja aayega", "kya baat", "dhamaal",
        "उत्साहित", "मज़ा आएगा",
    )
    private val LOVING = listOf(
        "love you", "i love", "miss you", "missed you", "thank you for", "you are the best",
        "pyaar", "pyar", "yaad aa rahi", "yaad aata", "tum best ho",
        "प्यार", "याद आ",
    )

    /** Negation in front of a bright word flips it: "khush nahi hoon" is not happiness. */
    private val NEGATIONS = setOf("not", "nahi", "nahin", "no", "never", "bilkul nahi", "नहीं")

    /**
     * Filler words that would otherwise read as emotion.
     *
     * "accha" is the most common acknowledgement in Hinglish conversation and means nothing on its
     * own; "theek" likewise. They are removed before matching so "accha, theek hai" stays neutral.
     */
    private val FILLER = setOf("accha", "achha", "theek", "thik", "ok", "okay", "hmm", "haan", "han")

    /**
     * Action words. A sentence with one of these is a command that happens to contain a feeling
     * word ("tired hoon, alarm laga do"), and commands must still run.
     */
    private val COMMAND_MARKERS = listOf(
        "kholo", "khol", "kholiye", "open", "bhejo", "bhej", "send", "message", "msg", "call",
        "alarm", "reminder", "timer", "set", "play", "bajao", "band", "chalu", "torch", "volume",
        "screenshot", "photo", "navigate", "search", "dikhao", "dikha", "dhundo", "dhoondo",
    )

    /**
     * The strongest words survive a command in the same sentence. Someone who says "sab bakwas hai,
     * message bhej do" is angry *and* wants the message sent.
     */
    private val STRONG = setOf("gussa", "gusse", "udaas", "dukhi", "tension", "anxious", "depressed", "crying")

    /** True when the sentence is asking for an action, whatever else it contains. */
    fun carriesCommand(textRaw: String): Boolean {
        val text = textRaw.lowercase(Locale.US)
        if (text.isBlank()) return false
        return COMMAND_MARKERS.any { text.contains(it) }
    }

    fun affect(textRaw: String): Affect {
        val normalised = textRaw.lowercase(Locale.US)
        if (normalised.isBlank()) return Affect.NEUTRAL

        val hasCommand = COMMAND_MARKERS.any { normalised.contains(it) }
        val hasStrong = STRONG.any { normalised.contains(it) }
        val text = normalised
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotBlank() && it !in FILLER }
            .joinToString(" ")
        if (text.isBlank()) return Affect.NEUTRAL

        // Longest match wins so "bura lag raha" beats a stray "lag".
        val found = when {
            containsAny(text, ANXIOUS) -> Affect.ANXIOUS
            containsAny(text, SAD) -> Affect.SAD
            containsAny(text, ANGRY) -> Affect.ANGRY
            containsAny(text, TIRED) -> Affect.TIRED
            containsAny(text, LOVING) -> Affect.LOVING
            containsAny(text, EXCITED) -> Affect.EXCITED
            containsAny(text, HAPPY) -> Affect.HAPPY
            else -> Affect.NEUTRAL
        }

        if (found == Affect.NEUTRAL) return Affect.NEUTRAL
        if (hasCommand && !hasStrong) return Affect.NEUTRAL
        return if (found.isBright && isNegated(text)) Affect.SAD else found
    }

    private fun containsAny(text: String, phrases: List<String>): Boolean =
        phrases.any { phrase -> text.contains(phrase) }

    /** "khush nahi", "not happy", "happy nahi hoon" — a bright word with a no in front of it. */
    private fun isNegated(text: String): Boolean {
        val tokens = text.split(' ').filter { it.isNotBlank() }
        for (index in tokens.indices) {
            if (tokens[index] in NEGATIONS) {
                // Covers both orders: "nahi khush" and "khush nahi".
                return true
            }
            if (HAPPY.contains(tokens[index]) || EXCITED.contains(tokens[index]) ||
                LOVING.contains(tokens[index])
            ) {
                val before = (index - 2..index - 1).filter { it >= 0 }.map { tokens[it] }
                if (before.any { it in NEGATIONS }) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // His register
    // ---------------------------------------------------------------------------------------------

    /**
     * Which tone this reply gets.
     *
     * The order matters: a heavy feeling outranks the clock (someone sad at 2 a.m. needs care, not
     * "go to sleep"), but a tired operator overrules a bright affect, because tiredness is the thing
     * that actually needs hearing.
     */
    fun moodOf(
        hour: Int,
        affect: Affect,
        bond: Bond = Bond(),
    ): Mood = when {
        affect == Affect.SAD || affect == Affect.ANXIOUS || affect == Affect.ANGRY -> Mood.CONCERNED
        affect == Affect.TIRED -> Mood.SLEEPY
        hour >= 23 || hour < 5 -> Mood.SLEEPY
        affect.isBright -> Mood.PLAYFUL
        bond.daysTogether >= 7 || bond.turns >= 25 -> Mood.WARM
        hour in 5..10 -> Mood.WARM
        else -> Mood.CALM
    }

    /** The register, as one line for the model. */
    fun toneInstruction(mood: Mood): String = when (mood) {
        Mood.CONCERNED ->
            "Tone: careful. Softer, slower, no jokes, no advice unless they ask. Ask one short " +
                "question about them before anything else."
        Mood.SLEEPY ->
            "Tone: quiet and low. Short sentences. If it is late, say so once and let it go."
        Mood.PLAYFUL ->
            "Tone: light. A little warmth and humour is welcome, but never at their expense."
        Mood.WARM ->
            "Tone: warm and familiar. Talk like someone who has known them for years."
        Mood.CALM ->
            "Tone: steady and plain. No performance."
    }

    /** One line describing what he noticed, for the model's context block. */
    fun observation(affect: Affect, bond: Bond): String? {
        if (affect == Affect.NEUTRAL) return null
        // A heavy feeling is described as a reading of their words, never as a diagnosis, and the
        // reply is told to answer the person before the request.
        val line = if (affect.isHeavy) {
            "From their words they sound ${affect.id}. Do not name it like a diagnosis; " +
                "just answer the person first."
        } else {
            "From their words they sound ${affect.id}. Match their energy."
        }
        if (bond.isNew) return line
        return line + "\nYou have been talking for ${bond.daysTogether} days and this is turn " +
            "${bond.turns + 1} overall."
    }

    // ---------------------------------------------------------------------------------------------
    // What he says
    // ---------------------------------------------------------------------------------------------

    /**
     * The first thing said to someone who sounds heavy.
     *
     * [seed] makes the choice stable per turn instead of random, so the same sentence does not come
     * back on a retry and the tests can pin it.
     */
    fun acknowledge(affect: Affect, lang: Lang, seed: Int = 0): String? {
        if (!affect.isHeavy) return null
        val options = when (affect) {
            Affect.TIRED -> listOf(
                Triple(
                    "You sound worn out. Everything else can wait a minute.",
                    "आप थके हुए लग रहे हैं। बाकी सब एक मिनट रुक सकता है।",
                    "Aap thake hue lag rahe ho. Baaki sab ek minute ruk sakta hai.",
                ),
                Triple(
                    "Long day, by the sound of it. What is the one thing that has to happen tonight?",
                    "लंबा दिन रहा लगता है। आज रात बस एक काम बताइए जो ज़रूरी है।",
                    "Lamba din raha lagta hai. Aaj raat bas ek kaam batao jo zaroori hai.",
                ),
            )
            Affect.SAD -> listOf(
                Triple(
                    "That sounds heavy. I am not going anywhere — tell me, or we can just sit in it.",
                    "यह भारी लग रहा है। मैं कहीं नहीं जा रहा — बताइए, या चुपचाप साथ बैठे रहते हैं।",
                    "Yeh bhaari lag raha hai. Main kahin nahi ja raha — batao, ya chupchaap saath baith jaate hain.",
                ),
                Triple(
                    "I hear it in your voice. Do you want me to fix something, or just listen?",
                    "आपकी आवाज़ में सुन रहा हूँ। कुछ ठीक करना है, या बस सुन लूँ?",
                    "Aapki awaaz mein sun raha hoon. Kuch theek karna hai, ya bas sun loon?",
                ),
            )
            Affect.ANXIOUS -> listOf(
                Triple(
                    "You sound wound up. Say the thing that is worrying you out loud — it gets smaller.",
                    "आप घबराए हुए लग रहे हैं। जो परेशान कर रहा है, बोल दीजिए — हल्का लगेगा।",
                    "Aap ghabraye hue lag rahe ho. Jo pareshan kar raha hai, bol do — halka lagega.",
                ),
                Triple(
                    "One thing at a time. What is the first part of it we can actually do now?",
                    "एक-एक करके। अभी जो पहला हिस्सा कर सकते हैं, वो क्या है?",
                    "Ek-ek karke. Abhi jo pehla hissa kar sakte hain, wo kya hai?",
                ),
            )
            Affect.ANGRY -> listOf(
                Triple(
                    "Fair enough, that would annoy me too. Want me to do something about it?",
                    "सही बात है, मुझे भी चिढ़ होती। कुछ करूँ इस बारे में?",
                    "Sahi baat hai, mujhe bhi chidh hoti. Kuch karoon is baare mein?",
                ),
                Triple(
                    "Let it out. I am on your side first, reasonable second.",
                    "कह दीजिए। मैं पहले आपकी तरफ़ हूँ, तर्क बाद में।",
                    "Keh do. Main pehle aapki taraf hoon, tark baad mein.",
                ),
            )
            else -> emptyList()
        }
        if (options.isEmpty()) return null
        val pick = options[seed.mod(options.size)]
        return when (lang.replyLang) {
            Lang.HI -> pick.second
            Lang.HINGLISH -> pick.third
            else -> pick.first
        }
    }

    /**
     * A short tail after a command finishes, so a task never ends on a step count when the operator
     * was clearly having a bad day.
     */
    fun afterCommand(affect: Affect, lang: Lang, seed: Int = 0): String? {
        if (!affect.isHeavy) return null
        val options = when (affect) {
            Affect.TIRED -> listOf(
                "Done. Now put the phone down for a bit.",
                "हो गया। अब फ़ोन थोड़ी देर रख दीजिए।",
                "Ho gaya. Ab phone thodi der rakh do.",
            )
            Affect.SAD -> listOf(
                "Done. I am here if you want to talk.",
                "हो गया। बात करनी हो तो मैं यहीं हूँ।",
                "Ho gaya. Baat karni ho to main yahin hoon.",
            )
            Affect.ANXIOUS -> listOf(
                "Done. That is one thing off the list.",
                "हो गया। सूची से एक चीज़ कम।",
                "Ho gaya. List se ek cheez kam.",
            )
            Affect.ANGRY -> listOf(
                "Done. Anything else that needs handling?",
                "हो गया। और कुछ संभालना है?",
                "Ho gaya. Aur kuch sambhalna hai?",
            )
            else -> return null
        }
        return when (lang.replyLang) {
            Lang.HI -> options[1]
            Lang.HINGLISH -> options[2]
            else -> options[0]
        }
    }

    /** Said back when the operator is warm to him. Never overplayed — one line, then move on. */
    fun carryWarmth(lang: Lang): String = when (lang.replyLang) {
        Lang.HI -> "मैं भी यहीं हूँ। हमेशा।"
        Lang.HINGLISH -> "Main bhi yahin hoon. Hamesha."
        else -> "I am here too. Always."
    }

    /**
     * A question about yesterday's feeling, asked once.
     *
     * Only ever one sentence, and only when the feeling was recent enough to still be true.
     */
    fun carryOverLine(affect: Affect, lang: Lang): String = when (affect) {
        Affect.SAD, Affect.ANXIOUS -> when (lang.replyLang) {
            Lang.HI -> "कल आप ठीक नहीं लग रहे थे। अब कैसा लग रहा है?"
            Lang.HINGLISH -> "Kal aap theek nahi lag rahe the. Ab kaisa lag raha hai?"
            else -> "You did not sound okay earlier. How are you now?"
        }
        Affect.ANGRY -> when (lang.replyLang) {
            Lang.HI -> "कल गुस्सा था। अब शांत हैं?"
            Lang.HINGLISH -> "Kal gussa tha. Ab shaant ho?"
            else -> "You were angry earlier. Calmer now?"
        }
        Affect.TIRED -> when (lang.replyLang) {
            Lang.HI -> "कल बहुत थके लग रहे थे। आज नींद पूरी हुई?"
            Lang.HINGLISH -> "Kal bahut thake lag rahe the. Aaj neend poori hui?"
            else -> "You sounded exhausted earlier. Did you get any sleep?"
        }
        else -> ""
    }

    // ---------------------------------------------------------------------------------------------
    // Care, from real signals only
    // ---------------------------------------------------------------------------------------------

    /**
     * One caring observation, or null.
     *
     * Every branch needs an actual measurement. If the phone cannot read battery, steps or water,
     * the corresponding line is simply not available — he does not guess in order to be nice.
     */
    fun careLine(
        lang: Lang,
        hour: Int,
        batteryPercent: Int? = null,
        charging: Boolean = false,
        waterMl: Int? = null,
        steps: Int? = null,
        unread: Int? = null,
    ): String? {
        fun pick(english: String, hindi: String, hinglish: String) = when (lang.replyLang) {
            Lang.HI -> hindi
            Lang.HINGLISH -> hinglish
            else -> english
        }

        if (hour >= 23 || hour < 5) {
            return pick(
                "It is past midnight. Whatever it is can wait until morning.",
                "रात काफ़ी हो गई है। जो भी है, सुबह तक रुक सकता है।",
                "Raat kaafi ho gayi hai. Jo bhi hai, subah tak ruk sakta hai.",
            )
        }
        if (batteryPercent != null && batteryPercent <= 10 && !charging) {
            return pick(
                "Your battery is at $batteryPercent%. Put it on charge before we lose each other.",
                "बैटरी $batteryPercent% है। चार्ज पर लगा दीजिए, वरना बात अधूरी रह जाएगी।",
                "Battery $batteryPercent% hai. Charge pe laga do, warna baat adhoori reh jayegi.",
            )
        }
        if (waterMl != null && waterMl == 0 && hour in 13..22) {
            return pick(
                "You have not logged any water today. One glass, then we carry on.",
                "आज पानी दर्ज नहीं हुआ। एक गिलास, फिर आगे बढ़ते हैं।",
                "Aaj paani note nahi hua. Ek glass, phir aage badhte hain.",
            )
        }
        if (steps != null && steps < 1500 && hour in 17..22) {
            return pick(
                "You have barely moved today. Ten minutes outside would do you good.",
                "आज बहुत कम चले। दस मिनट बाहर निकलना अच्छा रहेगा।",
                "Aaj kam chale ho. Das minute bahar nikalna achha rahega.",
            )
        }
        if (unread != null && unread >= 5) {
            return pick(
                "$unread messages are waiting. Want them read out, or cleared quietly?",
                "$unread संदेश इंतज़ार कर रहे हैं। सुनाऊँ, या चुपचाप छोड़ दूँ?",
                "$unread messages wait kar rahe hain. Sunau, ya chupchaap chhod doon?",
            )
        }
        return null
    }

    /** The same check, with the real device readings gathered defensively. */
    suspend fun careFor(context: Context, lang: Lang, now: Long = System.currentTimeMillis()): String? {
        if (!isEnabled(context)) return null
        val hour = try {
            Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        } catch (t: Throwable) {
            12
        }
        val battery = try {
            com.jarvis.assistant.device.readBattery(context)
        } catch (t: Throwable) {
            null
        }
        val water = try {
            com.jarvis.assistant.device.HealthLog.waterToday(context)
        } catch (t: Throwable) {
            null
        }
        val steps = try {
            com.jarvis.assistant.device.HealthLog.stepsToday(context)
        } catch (t: Throwable) {
            null
        }
        val unread = try {
            if (com.jarvis.assistant.device.NotificationRepository.hasAccess(context)) {
                com.jarvis.assistant.device.NotifyBrain.countUnreadMessages(context)
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
        return careLine(
            lang = lang,
            hour = hour,
            batteryPercent = battery?.percent,
            charging = battery?.isCharging ?: false,
            waterMl = water,
            steps = steps,
            unread = unread,
        )
    }
}
