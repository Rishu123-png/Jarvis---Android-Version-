package com.jarvis.assistant.voice

import android.content.Context
import java.util.Locale

/**
 * The language the operator is speaking.
 *
 * Detected from the script of the words, not from a setting, because the same person mixes
 * languages inside one sentence ("gallery kholo", "मेरी gallery खोलो"). Detection is deliberately
 * cheap and offline: no model, no network, no key.
 *
 * [templates] marks the languages JARVIS can answer in **without** an AI key. Every other language
 * still works when a key is stored, because the model writes the reply and TTS speaks it; without a
 * key those replies fall back to English rather than pretending to know the language.
 */
enum class Lang(
    val tag: String,
    val label: String,
    val templates: Boolean = false,
) {
    EN("en-IN", "ENGLISH", templates = true),
    HI("hi-IN", "HINDI", templates = true),

    /** Hindi written in Latin letters: "kitna door hai". Spoken with the Hindi voice. */
    HINGLISH("hi-IN", "HINGLISH", templates = true),

    /** Devanagari languages other than Hindi use the same voice and the English templates. */
    MARATHI("mr-IN", "MARATHI"),
    BENGALI("bn-IN", "BENGALI"),
    TAMIL("ta-IN", "TAMIL"),
    TELUGU("te-IN", "TELUGU"),
    KANNADA("kn-IN", "KANNADA"),
    MALAYALAM("ml-IN", "MALAYALAM"),
    GUJARATI("gu-IN", "GUJARATI"),
    PUNJABI("pa-IN", "PUNJABI"),
    URDU("ur-IN", "URDU"),
    ARABIC("ar", "ARABIC"),
    RUSSIAN("ru", "RUSSIAN"),
    CHINESE("zh", "CHINESE"),
    JAPANESE("ja", "JAPANESE"),
    KOREAN("ko", "KOREAN"),
    THAI("th", "THAI"),
    FRENCH("fr", "FRENCH"),
    SPANISH("es", "SPANISH"),
    GERMAN("de", "GERMAN"),
    PORTUGUESE("pt", "PORTUGUESE"),
    INDONESIAN("id", "INDONESIAN"),
    TURKISH("tr", "TURKISH"),
    OTHER("en-IN", "OTHER");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    /** The language a canned reply will actually be spoken in. */
    val replyLang: Lang
        get() = when {
            templates -> this
            // A Devanagari speaker understands the Hindi templates far better than English ones.
            this == MARATHI || this == URDU -> HI
            else -> EN
        }

    companion object {
        fun fromTag(tag: String?): Lang =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: EN
    }
}

/**
 * Detection, plus the canned replies.
 *
 * Canned replies matter more than they look: they are what JARVIS says when there is **no** AI key,
 * which is the state most first-time users are in. They exist for English, Hindi and Hinglish. Every
 * other language is handled by the model (see `IntentRouter.systemPrompt`), and TTS speaks it.
 */
object Language {

    /** Words that decide the language on their own: verbs and question words. */
    private val HINGLISH_CORE = setOf(
        "kholo", "khol", "karo", "bhejo", "bhej", "bolo", "sunao", "kitna", "kitni", "kitne",
        "kaise", "kahan", "kya", "mera", "meri", "mere", "mujhe", "band", "chalu", "chala",
        "dikhao", "dikha", "dhundo", "dhoondo", "batao", "bata", "chahiye", "chaiye", "bajao",
        "baje", "awaaz", "gaana", "kadam", "doori", "neend", "dhadkan", "sehat", "paani",
        "raasta", "jawab", "samajh", "matlab", "banao", "banaya", "gilaas", "bottle",
    )

    /** Words that colour a sentence but do not decide it: one is not enough. */
    private val HINGLISH_FILLER = setOf(
        "yaar", "bhai", "abhi", "kal", "aaj", "nahi", "haan", "hai", "hain", "tum", "aap",
        "dost", "papa", "mummy", "bhaiya", "didi", "please", "zara", "thoda", "accha", "bekar",
    )

    /**
     * Which language this sentence is in.
     *
     * Script first — a Devanagari sentence is never English — then a word vote. A Hindi *verb or
     * question word* settles it ("kitne steps"), because the sentence cannot be English with it. A
     * Hindi *filler* does not: "open the gallery yaar" stays English, and is answered in English.
     */
    fun detect(text: String): Lang {
        val script = scriptOf(text)
        if (script != Lang.OTHER && script != Lang.EN) return script

        val words = text.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return Lang.EN

        if (words.any { it in HINGLISH_CORE }) return Lang.HINGLISH
        val fillers = words.count { it in HINGLISH_FILLER }
        return if (fillers >= 2) Lang.HINGLISH else Lang.EN
    }

    /** The script a text is written in, mapped to the language that owns it. */
    fun scriptOf(text: String): Lang {
        for (ch in text) {
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.DEVANAGARI -> return Lang.HI
                Character.UnicodeBlock.BENGALI -> return Lang.BENGALI
                Character.UnicodeBlock.TAMIL -> return Lang.TAMIL
                Character.UnicodeBlock.TELUGU -> return Lang.TELUGU
                Character.UnicodeBlock.KANNADA -> return Lang.KANNADA
                Character.UnicodeBlock.MALAYALAM -> return Lang.MALAYALAM
                Character.UnicodeBlock.GUJARATI -> return Lang.GUJARATI
                Character.UnicodeBlock.GURMUKHI -> return Lang.PUNJABI
                Character.UnicodeBlock.ARABIC -> return Lang.URDU
                Character.UnicodeBlock.CYRILLIC -> return Lang.RUSSIAN
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> return Lang.CHINESE
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                -> return Lang.JAPANESE
                Character.UnicodeBlock.HANGUL_SYLLABLES -> return Lang.KOREAN
                Character.UnicodeBlock.THAI -> return Lang.THAI
                else -> Unit
            }
        }
        return Lang.OTHER
    }

    /**
     * A canned sentence in [lang].
     *
     * `%s` / `%d` are filled from [args]. Unknown keys fall back to the key itself so a missing
     * string shows up in testing instead of vanishing.
     */
    fun say(lang: Lang, key: String, vararg args: Any): String {
        val template = template(lang.replyLang, key) ?: template(Lang.EN, key) ?: key
        return try {
            String.format(Locale.US, template, *args)
        } catch (t: Throwable) {
            template
        }
    }

    private fun template(lang: Lang, key: String): String? = when (lang) {
        Lang.HI -> HINDI[key]
        Lang.HINGLISH -> HINGLISH[key]
        else -> ENGLISH[key]
    }

    // ---------------------------------------------------------------------------------------------
    // Templates. Kept flat and short on purpose: every one of these is spoken, and a long sentence
    // from a machine is tiring to hear.
    // ---------------------------------------------------------------------------------------------

    private val ENGLISH = mapOf(
        "opening" to "Opening %s.",
        "open_failed" to "I could not open %s.",
        "not_found_app" to "I do not see an app called %s. You have %s.",
        "which_app" to "Did you mean %s?",
        "torch_on" to "Torch on.",
        "torch_off" to "Torch off.",
        "volume" to "Volume %d.",
        "dnd_on" to "Do not disturb on.",
        "dnd_off" to "Interruptions are back.",
        "screenshot" to "Screen captured.",
        "photo" to "Photo taken.",
        "no_screen" to "I could not read that screen.",
        "steps" to "You have walked %d steps today.",
        "steps_none" to "I cannot count steps on this phone.",
        "water" to "Water logged. %d millilitres today.",
        "distance_road" to "%s is %s by road.",
        "distance_line" to "%s is %s away in a straight line.",
        "distance_unknown" to "I could not find %s.",
        "calc" to "That is %s.",
        "file_found" to "I found %d. Opening %s.",
        "file_none" to "I could not find a file called %s.",
        "file_permission" to "I need file access to search. Turn it on in Advanced.",
        "who_reply" to "Which %s? You have %s.",
        "reply_sent" to "Replied to %s.",
        "reply_failed" to "I could not reply to %s.",
        "messages_none" to "Nothing new worth telling you.",
        "missed" to "You have %d unread messages.",
        "identity" to "I was built by Rishu Jaswar. I am his JARVIS.",
        "calling" to "Calling %s.",
        "message" to "Writing to %s.",
        "alarm" to "Alarm set for %s.",
        "timer" to "Timer set.",
        "reminder" to "I will remind you.",
        "location_permission" to "I need location access for that.",
        "sight_needs_key" to "Looking at a photo needs the brain switched on — add a key in " +
            "Advanced → AI, and I will tell you what I see.",
        "sight_needs_camera" to "Camera access is off, so I cannot see anything. Turn it on " +
            "and say it again.",
        "dont_know" to "I did not get that. Try “open gallery”, “how many steps”, or “what did I miss”.",
        "permission" to "That needs %s. I have put the switch in Advanced.",
        "locked" to "Locked.",
        "unlocked" to "Unlocked.",
        "sleep" to "You slept about %s last night. That is my estimate, not a measurement.",
        "heart_none" to "This phone has no heart rate sensor.",
        "heart" to "Your heart rate is %d.",
        "battery" to "Battery is %d percent.",
        "time" to "It is %s.",
        "calendar_none" to "Nothing in the calendar%s.",
        "calendar_next" to "Next is %s at %s.",
        "calendar_list" to "%s",
        "calendar_permission" to "Calendar access is off. Allow it in Advanced and I can read your schedule.",
        "calendar_added" to "Added %s.",
        "calendar_no_time" to "I did not catch the time for that. Say it like \"tomorrow at 4 pm\".",
        "contact_saved" to "Saved %s.",
        "contact_failed" to "I could not save that contact.",
        "note_recording" to "Recording for %d seconds.",
        "note_saved" to "Voice note saved in your Music folder.",
        "note_failed" to "The recording stopped early, so nothing was saved.",
        "nudge_none" to "",
    )

    private val HINDI = mapOf(
        "opening" to "%s खोल रहा हूँ।",
        "open_failed" to "%s नहीं खुल सका।",
        "not_found_app" to "%s नाम का ऐप नहीं मिला। आपके पास %s है।",
        "which_app" to "क्या आपका मतलब %s था?",
        "torch_on" to "टॉर्च चालू।",
        "torch_off" to "टॉर्च बंद।",
        "volume" to "आवाज़ %d।",
        "dnd_on" to "डू नॉट डिस्टर्ब चालू।",
        "dnd_off" to "सूचनाएँ फिर चालू।",
        "screenshot" to "स्क्रीनशॉट ले लिया।",
        "photo" to "फ़ोटो खींच ली।",
        "no_screen" to "यह स्क्रीन पढ़ नहीं पाया।",
        "steps" to "आज आपने %d कदम चले हैं।",
        "steps_none" to "इस फ़ोन में कदम गिनने का सेंसर नहीं है।",
        "water" to "पानी दर्ज कर लिया। आज %d मिलीलीटर।",
        "distance_road" to "%s सड़क से %s दूर है।",
        "distance_line" to "%s सीधी दूरी से %s दूर है।",
        "distance_unknown" to "%s नहीं मिला।",
        "calc" to "उत्तर %s है।",
        "file_found" to "%d मिली। %s खोल रहा हूँ।",
        "file_none" to "%s नाम की फ़ाइल नहीं मिली।",
        "file_permission" to "फ़ाइल खोजने के लिए अनुमति चाहिए। एडवांस्ड में चालू कीजिए।",
        "who_reply" to "कौन से %s? आपके पास %s हैं।",
        "reply_sent" to "%s को जवाब भेज दिया।",
        "reply_failed" to "%s को जवाब नहीं भेज पाया।",
        "messages_none" to "बताने लायक कुछ नया नहीं है।",
        "missed" to "आपके %d बिना पढ़े संदेश हैं।",
        "identity" to "मुझे ऋषु जसवार ने बनाया है। मैं उनका जार्विस हूँ।",
        "calling" to "%s को कॉल कर रहा हूँ।",
        "message" to "%s को लिख रहा हूँ।",
        "alarm" to "%s का अलार्म लगा दिया।",
        "timer" to "टाइमर लगा दिया।",
        "reminder" to "मैं याद दिला दूँगा।",
        "location_permission" to "इसके लिए लोकेशन की अनुमति चाहिए।",
        "sight_needs_key" to "तस्वीर देखने के लिए दिमाग़ चालू होना चाहिए — एडवांस्ड → AI में चाबी " +
            "डालिए, फिर बताऊँगा कि क्या दिख रहा है।",
        "sight_needs_camera" to "कैमरा बंद है, इसलिए कुछ दिख नहीं रहा। चालू कीजिए और फिर कहिए।",
        "dont_know" to "समझ नहीं पाया। “गैलरी खोलो”, “कितने कदम”, या “क्या मिस किया” कहिए।",
        "permission" to "इसके लिए %s चाहिए। एडवांस्ड में स्विच रखा है।",
        "locked" to "लॉक हो गया।",
        "unlocked" to "खुल गया।",
        "sleep" to "पिछली रात आप लगभग %s सोए। यह मेरा अंदाज़ा है।",
        "heart_none" to "इस फ़ोन में हार्ट रेट सेंसर नहीं है।",
        "heart" to "आपकी धड़कन %d है।",
        "battery" to "बैटरी %d प्रतिशत है।",
        "time" to "अभी %s बजे हैं।",
        "calendar_none" to "कैलेंडर में कुछ नहीं है%s।",
        "calendar_next" to "अगला %s, %s बजे।",
        "calendar_list" to "%s",
        "calendar_permission" to "कैलेंडर की अनुमति बंद है। एडवांस्ड में चालू कीजिए।",
        "calendar_added" to "%s जोड़ दिया।",
        "calendar_no_time" to "समय समझ नहीं आया। ऐसे कहिए: \"कल शाम 4 बजे\"।",
        "contact_saved" to "%s सेव कर लिया।",
        "contact_failed" to "वह संपर्क सेव नहीं हो पाया।",
        "note_recording" to "%d सेकंड रिकॉर्ड कर रहा हूँ।",
        "note_saved" to "वॉइस नोट म्यूज़िक फ़ोल्डर में सेव हो गया।",
        "note_failed" to "रिकॉर्डिंग बीच में रुक गई, कुछ सेव नहीं हुआ।",
        "nudge_none" to "",
    )

    private val HINGLISH = mapOf(
        "opening" to "%s khol raha hoon.",
        "open_failed" to "%s nahi khul paya.",
        "not_found_app" to "%s naam ka app nahi mila. Aapke paas %s hai.",
        "which_app" to "Aapka matlab %s tha?",
        "torch_on" to "Torch on.",
        "torch_off" to "Torch off.",
        "volume" to "Awaaz %d.",
        "dnd_on" to "Do not disturb on.",
        "dnd_off" to "Notifications wapas on.",
        "screenshot" to "Screenshot le liya.",
        "photo" to "Photo khinch li.",
        "no_screen" to "Yeh screen padh nahi paya.",
        "steps" to "Aaj aapne %d kadam chale hain.",
        "steps_none" to "Is phone mein step sensor nahi hai.",
        "water" to "Paani note kar liya. Aaj %d ml.",
        "distance_road" to "%s road se %s door hai.",
        "distance_line" to "%s seedhi doori se %s hai.",
        "distance_unknown" to "%s nahi mila.",
        "calc" to "Jawab %s hai.",
        "file_found" to "%d mili. %s khol raha hoon.",
        "file_none" to "%s naam ki file nahi mili.",
        "file_permission" to "File dhundhne ke liye access chahiye. Advanced mein on kar dijiye.",
        "who_reply" to "Kaunse %s? Aapke paas %s hain.",
        "reply_sent" to "%s ko reply bhej diya.",
        "reply_failed" to "%s ko reply nahi bhej paya.",
        "messages_none" to "Batane layak kuch naya nahi hai.",
        "missed" to "Aapke %d unread messages hain.",
        "identity" to "Mujhe Rishu Jaswar ne banaya hai. Main unka JARVIS hoon.",
        "calling" to "%s ko call kar raha hoon.",
        "message" to "%s ko likh raha hoon.",
        "alarm" to "%s ka alarm laga diya.",
        "timer" to "Timer laga diya.",
        "reminder" to "Main yaad dila dunga.",
        "location_permission" to "Iske liye location chahiye.",
        "sight_needs_key" to "Tasveer dekhne ke liye dimaag chalu hona chahiye — Advanced → AI " +
            "mein chaabi daaliye, phir bataunga kya dikh raha hai.",
        "sight_needs_camera" to "Camera band hai, isliye kuch dikh nahi raha. Chalu kijiye aur " +
            "phir kahiye.",
        "dont_know" to "Samajh nahi paya. “Gallery kholo”, “kitne steps”, ya “kya miss kiya” boliye.",
        "permission" to "Iske liye %s chahiye. Advanced mein switch rakha hai.",
        "locked" to "Lock ho gaya.",
        "unlocked" to "Khul gaya.",
        "sleep" to "Kal raat aap lagbhag %s soye. Yeh mera andaza hai.",
        "heart_none" to "Is phone mein heart rate sensor nahi hai.",
        "heart" to "Aapki dhadkan %d hai.",
        "battery" to "Battery %d percent hai.",
        "time" to "Abhi %s baje hain.",
        "calendar_none" to "Calendar mein kuch nahi hai%s.",
        "calendar_next" to "Agla %s, %s baje.",
        "calendar_list" to "%s",
        "calendar_permission" to "Calendar ki permission off hai. Advanced mein on kar dijiye.",
        "calendar_added" to "%s add kar diya.",
        "calendar_no_time" to "Time samajh nahi aaya. Aise boliye: \"kal shaam 4 baje\".",
        "contact_saved" to "%s save kar liya.",
        "contact_failed" to "Woh contact save nahi ho paya.",
        "note_recording" to "%d second record kar raha hoon.",
        "note_saved" to "Voice note Music folder mein save ho gaya.",
        "note_failed" to "Recording beech mein ruk gayi, kuch save nahi hua.",
        "nudge_none" to "",
    )
}

/**
 * Remembers the language of the last thing the operator said.
 *
 * Steps run in a service and a coroutine, far away from the microphone, so the answer they produce
 * has to be spoken in the language of the question that caused them. One value in prefs is enough,
 * and it means "kitne steps" is answered in Hindi even though the step sensor knows nothing about
 * language.
 */
object SpokenLanguage {

    private const val PREFS = "jarvis_language"
    private const val KEY = "last"

    fun remember(context: Context, lang: Lang) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang.tag).apply()
    }

    fun last(context: Context): Lang = Lang.fromTag(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null),
    )
}
