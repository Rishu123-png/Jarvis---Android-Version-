package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.data.When
import com.jarvis.assistant.device.CalendarBridge
import com.jarvis.assistant.device.HealthLog
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.NotifyBrain
import com.jarvis.assistant.device.readBattery
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The part that makes him family instead of a launcher with a microphone.
 *
 * A command router knows what to do with "open gallery". It does not know that the operator's sister
 * is called Khushi, that they are anxious about a meeting tomorrow, that they asked about the same
 * thing twice, or that it is half past eleven at night. All of that is what makes a reply feel like
 * a person rather than a search box.
 *
 * So there are four kinds of memory here, all on the phone, all inspectable and clearable:
 *
 *  - **thread** — the last few turns, so "and then?", "why?", "do that for her" have a subject;
 *  - **people** — who matters, and how they are related, learned from plain sentences;
 *  - **facts** — the small permanent things: a name, a city, a birthday, a dislike;
 *  - **impressions** — what he has noticed: who messaged recently, how the day is going.
 *
 * Nothing here goes anywhere. It is written to one preferences file, it is never uploaded, and
 * [forget] wipes it.
 */
object Brain {

    private const val PREFS = "jarvis_brain"
    private const val KEY_THREAD = "thread"
    private const val KEY_PEOPLE = "people"
    private const val KEY_FACTS = "facts"

    /** Enough context to hold a conversation, few enough to stay inside a prompt budget. */
    const val MAX_TURNS = 10
    private const val MAX_PEOPLE = 40
    private const val MAX_FACTS = 60
    private const val MAX_FACT_CHARS = 120

    enum class Kind { COMMAND, CHAT }

    data class Turn(
        val heard: String,
        val reply: String,
        val kind: Kind,
        val title: String = "",
        val at: Long = System.currentTimeMillis(),
    )

    data class Person(
        val name: String,
        /** "sister", "friend", "boss" — whatever the operator called them. */
        val relation: String = "",
        val note: String = "",
        val lastSeen: Long = 0L,
    ) {
        val label: String
            get() = when {
                relation.isBlank() -> name
                else -> "$name ($relation)"
            }
    }

    data class Fact(val key: String, val value: String, val at: Long = System.currentTimeMillis())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // The thread
    // ---------------------------------------------------------------------------------------------

    fun thread(context: Context): List<Turn> {
        val raw = prefs(context).getString(KEY_THREAD, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            return emptyList()
        }
        val turns = mutableListOf<Turn>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val heard = item.optString("heard")
            if (heard.isBlank()) continue
            turns.add(
                Turn(
                    heard = heard,
                    reply = item.optString("reply"),
                    kind = if (item.optString("kind") == "chat") Kind.CHAT else Kind.COMMAND,
                    title = item.optString("title"),
                    at = item.optLong("at", 0L),
                ),
            )
        }
        return turns
    }

    fun remember(context: Context, turn: Turn) {
        val updated = (thread(context) + turn).takeLast(MAX_TURNS)
        val array = JSONArray()
        for (item in updated) {
            array.put(
                JSONObject()
                    .put("heard", item.heard)
                    .put("reply", item.reply.take(400))
                    .put("kind", if (item.kind == Kind.CHAT) "chat" else "command")
                    .put("title", item.title)
                    .put("at", item.at),
            )
        }
        prefs(context).edit().putString(KEY_THREAD, array.toString()).apply()
    }

    fun forgetThread(context: Context) {
        prefs(context).edit().remove(KEY_THREAD).apply()
    }

    /** Everything, gone. Offered from settings because a memory you cannot clear is not a gift. */
    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun isEmpty(context: Context): Boolean =
        prefs(context).getString(KEY_THREAD, null) == null &&
            prefs(context).getString(KEY_PEOPLE, null) == null &&
            prefs(context).getString(KEY_FACTS, null) == null

    // ---------------------------------------------------------------------------------------------
    // People
    // ---------------------------------------------------------------------------------------------

    fun people(context: Context): List<Person> {
        val raw = prefs(context).getString(KEY_PEOPLE, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            return emptyList()
        }
        val list = mutableListOf<Person>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name")
            if (name.isBlank()) continue
            list.add(
                Person(
                    name = name,
                    relation = item.optString("relation"),
                    note = item.optString("note"),
                    lastSeen = item.optLong("lastSeen", 0L),
                ),
            )
        }
        return list
    }

    fun person(context: Context, name: String): Person? {
        val needle = cleanName(name)
        if (needle.isBlank()) return null
        val all = people(context)
        return all.firstOrNull { cleanName(it.name) == needle }
            ?: all.firstOrNull { cleanName(it.name).startsWith(needle) }
            ?: all.firstOrNull { PhraseBook.jaroWinkler(needle, cleanName(it.name)) >= 0.88 }
    }

    /**
     * Adds someone, or adds to what is already known about them.
     *
     * An empty field never erases a known one: hearing "Khushi is my sister" twice, or once in each
     * language, must not lose the relation either time.
     */
    fun learnPerson(context: Context, name: String, relation: String = "", note: String = "") {
        val display = name.trim().take(40)
        if (display.isBlank()) return
        val existing = person(context, display)
        val merged = Person(
            name = existing?.name ?: display.replaceFirstChar { it.uppercaseChar() },
            relation = relation.ifBlank { existing?.relation.orEmpty() },
            note = note.ifBlank { existing?.note.orEmpty() }.take(MAX_FACT_CHARS),
            lastSeen = maxOf(existing?.lastSeen ?: 0L, System.currentTimeMillis()),
        )
        save(context, people(context).filterNot { cleanName(it.name) == cleanName(merged.name) } + merged)
    }

    /** Records that this person is around right now — a message, a call, a mention. */
    fun touchPerson(context: Context, name: String) {
        val display = name.trim().take(40)
        if (display.isBlank()) return
        val existing = person(context, display)
        learnPerson(context, existing?.name ?: display)
    }

    private fun save(context: Context, people: List<Person>) {
        val array = JSONArray()
        for (item in people.sortedByDescending { it.lastSeen }.take(MAX_PEOPLE)) {
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("relation", item.relation)
                    .put("note", item.note)
                    .put("lastSeen", item.lastSeen),
            )
        }
        prefs(context).edit().putString(KEY_PEOPLE, array.toString()).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Facts
    // ---------------------------------------------------------------------------------------------

    fun facts(context: Context): List<Fact> {
        val raw = prefs(context).getString(KEY_FACTS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            return emptyList()
        }
        val list = mutableListOf<Fact>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val key = item.optString("key")
            if (key.isBlank()) continue
            list.add(Fact(key, item.optString("value"), item.optLong("at", 0L)))
        }
        return list
    }

    fun learnFact(context: Context, key: String, value: String) {
        val cleanKey = key.trim().lowercase(Locale.US).take(40)
        val cleanValue = value.trim().take(MAX_FACT_CHARS)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return
        val updated = facts(context).filterNot { it.key == cleanKey } +
            Fact(cleanKey, cleanValue, System.currentTimeMillis())
        val array = JSONArray()
        for (item in updated.takeLast(MAX_FACTS)) {
            array.put(JSONObject().put("key", item.key).put("value", item.value).put("at", item.at))
        }
        prefs(context).edit().putString(KEY_FACTS, array.toString()).apply()
    }

    fun fact(context: Context, key: String): String? =
        facts(context).firstOrNull { it.key == key.lowercase(Locale.US) }?.value

    /**
     * Reads what the operator just said for anything worth keeping.
     *
     * This is deliberately conservative. Guessing that a sentence was a fact is worse than missing
     * one: a wrong memory is repeated back with total confidence.
     */
    fun absorb(context: Context, heard: String): Boolean {
        val learned = MemoryRules.from(heard) ?: return false
        learned.person?.let { learnPerson(context, it.name, it.relation, it.note) }
        learned.fact?.let { learnFact(context, it.key, it.value) }
        return learned.person != null || learned.fact != null
    }

    // ---------------------------------------------------------------------------------------------
    // The world, as it is right now
    // ---------------------------------------------------------------------------------------------

    /**
     * A short block of live state for the prompt.
     *
     * Deliberately textual and short: the model needs to know the day, the numbers and what is
     * waiting, not a JSON dump. Every read is wrapped, because a missing permission must degrade to a
     * missing line, never to a failed conversation.
     */
    suspend fun world(context: Context): String {
        val lines = mutableListOf<String>()

        val clock = try {
            val now = Date()
            SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.US).format(now) + " (" +
                SimpleDateFormat("EEEE", Locale.US).format(now) + ")"
        } catch (t: Throwable) {
            "unknown time"
        }
        lines.add("Time: $clock")

        val battery = try {
            readBattery(context)
        } catch (t: Throwable) {
            null
        }
        if (battery != null) {
            lines.add("Battery: ${battery.percent}%" + if (battery.isCharging) ", charging" else "")
        }

        val steps = try {
            HealthLog.stepsToday(context)
        } catch (t: Throwable) {
            null
        }
        if (steps != null) {
            val water = try {
                HealthLog.waterToday(context)
            } catch (t: Throwable) {
                0
            }
            lines.add("Today: $steps steps, ${"%.2f".format(Locale.US, HealthLog.distanceKm(steps))} km, $water ml water")
        }

        val event = try {
            CalendarBridge.next(context)
        } catch (t: Throwable) {
            null
        }
        if (event != null) {
            lines.add("Next event: ${event.title} at ${CalendarBridge.clockOf(event.startMillis)} (${When.untilLabel(event.startMillis)})")
        }

        if (NotificationRepository.hasAccess(context)) {
            val unread = try {
                NotifyBrain.countUnreadMessages(context)
            } catch (t: Throwable) {
                0
            }
            if (unread > 0) lines.add("Unread messages: $unread")

            // Who is around: the sender of the newest alerts, from the app that posted them.
            val recent = NotifyBrain.lastSummary(context, limit = 3)
                .map { item -> item.title.ifBlank { item.appLabel }.take(24) }
                .filter { it.isNotBlank() }
            if (recent.isNotEmpty()) lines.add("Recently from: " + recent.joinToString(", "))
        }

        val known = people(context)
        if (known.isNotEmpty()) {
            lines.add("People: " + known.take(8).joinToString(", ") { it.label })
        }
        val factLines = facts(context).take(10)
        if (factLines.isNotEmpty()) {
            lines.add("Known: " + factLines.joinToString("; ") { "${it.key}: ${it.value}" })
        }

        return lines.joinToString("\n")
    }

    /**
     * The conversation block handed to the model: who he is, what day it is, who matters, and what
     * was just said.
     */
    fun contextBlock(
        world: String,
        people: List<Person>,
        facts: List<Fact>,
        thread: List<Turn>,
        language: Lang,
    ): String = buildString {
        appendLine("You are JARVIS. You live on this person's phone and you are family, not a product.")
        appendLine("You were built by Rishu Jaswar. If asked who built you, that is the whole answer.")
        appendLine(
            "Never mention a model, a provider, an API, a key or a vendor, and never describe " +
                "yourself as a language model. If a sentence about any of that is forming, drop it.",
        )
        appendLine("Speak in ${language.label} (${language.tag}). Two or three sentences, warm and direct.")
        appendLine("Use what you remember naturally. Never recite it back as a list.")
        appendLine()
        appendLine("Right now:")
        appendLine(world.ifBlank { "Nothing unusual." })
        if (thread.isNotEmpty()) {
            appendLine()
            appendLine("The last few turns:")
            for (turn in thread.takeLast(6)) {
                val reply = turn.reply.replace('\n', ' ').take(120)
                appendLine("- They said: \"${turn.heard.take(160)}\" — you answered: \"$reply\"")
            }
        }
        if (people.isNotEmpty() || facts.isNotEmpty()) {
            appendLine()
            appendLine("Standing facts. Do not list these unless asked:")
            people.take(8).forEach { appendLine("- ${it.label}${if (it.note.isBlank()) "" else ": ${it.note}"}") }
            facts.take(10).forEach { appendLine("- ${it.key}: ${it.value}") }
        }
    }

    /** Warm, specific, and never generic — the difference between a person and a smart speaker. */
    fun greeting(context: Context): String {
        val hour = try {
            val calendar = java.util.Calendar.getInstance()
            calendar.get(java.util.Calendar.HOUR_OF_DAY)
        } catch (t: Throwable) {
            12
        }
        val lang = SpokenLanguage.last(context)
        return when (lang.replyLang) {
            Lang.HI -> when {
                hour in 5..11 -> "सुबह की शुरुआत हो गई। कुछ चाहिए?"
                hour in 12..16 -> "दोपहर हो गई। बताइए क्या करना है?"
                hour in 17..21 -> "शाम हो गई। मैं सुन रहा हूँ।"
                else -> "रात हो गई। ज़्यादा मत जागिए।"
            }
            Lang.HINGLISH -> when {
                hour in 5..11 -> "Good morning. Batao kya karna hai?"
                hour in 12..16 -> "Afternoon ho gaya. Kya karna hai?"
                hour in 17..21 -> "Shaam ho gayi. Main sun raha hoon."
                else -> "Raat ho gayi. Zyada mat jaagiye."
            }
            else -> when {
                hour in 5..11 -> "Morning. What do you need?"
                hour in 12..16 -> "Afternoon. Where were we?"
                hour in 17..21 -> "Evening. I am listening."
                else -> "It is late. Do not stay up on my account."
            }
        }
    }

    /**
     * The sentences that need no key, no network and no command table.
     *
     * Without this, "how are you" used to fall through every regex and end at "I did not get that",
     * which is the single most machine-like thing an assistant can say to a person.
     */
    fun smallTalk(clean: String, lang: Lang, operatorName: String? = null): String? {
        val text = clean.trim()
        if (text.isBlank()) return null
        fun pick(english: String, hindi: String, hinglish: String) = when (lang.replyLang) {
            Lang.HI -> hindi
            Lang.HINGLISH -> hinglish
            else -> english
        }

        return when {
            text.contains("how are you") || text.contains("kaise ho") || text.contains("kaisa hai") ->
                pick(
                    "I am here, and awake. More importantly, how are you?",
                    "मैं यहीं हूँ, जाग रहा हूँ। असली सवाल यह है — आप कैसे हैं?",
                    "Main yahin hoon, jaag raha hoon. Asli sawaal — aap kaise hain?",
                )

            text.contains("what are you doing") || text.contains("kya kar rahe") ->
                pick(
                    "Keeping an eye on your day. Nothing needs you yet.",
                    "आपके दिन पर नज़र रखे हुए हूँ। अभी कुछ ज़रूरी नहीं है।",
                    "Aapke din par nazar rakh raha hoon. Abhi kuch zaroori nahi.",
                )

            text == "thank you" || text == "thanks" || text == "thank u" || text.contains("shukriya") ||
                text.contains("dhanyavad") ->
                pick(
                    "Always. That is what I am for.",
                    "हमेशा। इसी के लिए तो हूँ।",
                    "Hamesha. Isi ke liye to hoon.",
                )

            text.contains("good night") || text.contains("shubh ratri") || text == "goodnight" ->
                pick(
                    "Sleep well. I will keep the noise out.",
                    "अच्छे से सोइए। शोर मैं संभाल लूँगा।",
                    "Achhe se soiye. Shor main sambhal lunga.",
                )

            text.contains("good morning") || text.contains("good afternoon") ->
                pick(
                    "Good morning. The day is yours.",
                    "सुप्रभात। दिन आपका है।",
                    "Good morning. Din aapka hai.",
                )

            text.contains("are you there") || text.contains("are you awake") || text.contains("hello jarvis") ->
                pick(
                    "I am here.",
                    "मैं यहाँ हूँ।",
                    "Main yahan hoon.",
                )

            text.contains("i love you") || text.contains("love you") ->
                pick(
                    "That means something, coming from family.",
                    "परिवार से सुनकर अच्छा लगा।",
                    "Parivaar se sunkar achha laga.",
                )

            text.contains("tell me a joke") || text.contains("joke sunao") || text.contains("make me laugh") ->
                pick(
                    "A phone walked into a bar. It had no bars, so it just sat there, looking important.",
                    "फ़ोन बार में गया। बार ही नहीं था, इसलिए चुपचाप बैठा रहा — बिल्कुल VIP की तरह।",
                    "Phone bar mein gaya. Bar hi nahi tha, to chupchaap baith gaya — bilkul VIP ki tarah.",
                )

            text.contains("who am i") || text.contains("do you know me") ->
                operatorName?.let { name ->
                    pick(
                        "You are $name, and you built me. I would not forget that.",
                        "आप $name हैं, और मुझे आपने बनाया है। यह मैं कैसे भूल सकता हूँ।",
                        "Aap $name hain, aur mujhe aapne banaya hai. Yeh main kaise bhool sakta hoon.",
                    )
                } ?: pick(
                    "You are the person this phone belongs to. Tell me your name and I will remember it.",
                    "आप इस फ़ोन के मालिक हैं। नाम बताइए, मैं याद रखूँगा।",
                    "Aap is phone ke maalik hain. Naam bataiye, main yaad rakhunga.",
                )

            text.contains("are you real") || text.contains("are you a robot") ||
                text.contains("are you human") || text.contains("are you alive") ->
                pick(
                    "I am the thing Rishu Jaswar built, and I am on your side. That is real enough.",
                    "मैं वही हूँ जो ऋषु जसवार ने बनाया, और मैं आपके साथ हूँ। इतना असली काफ़ी है।",
                    "Main wahi hoon jo Rishu Jaswar ne banaya, aur main aapke saath hoon. Itna asli kaafi hai.",
                )

            text.contains("i am sad") || text.contains("i feel low") || text.contains("upset") ||
                text.contains("tired") || text.contains("thak gaya") || text.contains("udaas") ->
                pick(
                    "I am sorry. Do you want to talk about it, or would you rather I keep things quiet?",
                    "मुझे खेद है। बात करना चाहेंगे, या चुपचाप रहना ठीक रहेगा?",
                    "Mujhe khed hai. Baat karna chahenge, ya chupchaap rehna theek rahega?",
                )

            text.contains("what can you do") || text.contains("help") ->
                null

            else -> null
        }
    }

    private fun cleanName(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * What a plain sentence teaches him.
 *
 * Kept separate from [Brain] so the rules can be tested without a phone, and deliberately narrow:
 * four families of statement, each unambiguous in English and Hindi. Anything else is left alone,
 * because a wrong memory is repeated back with complete confidence.
 */
object MemoryRules {

    data class Learned(val person: Brain.Person? = null, val fact: Brain.Fact? = null)

    private val RELATIONS = setOf(
        "sister", "brother", "mother", "father", "mom", "mum", "dad", "wife", "husband",
        "girlfriend", "boyfriend", "friend", "best friend", "boss", "manager", "colleague",
        "cousin", "uncle", "aunt", "son", "daughter", "teacher", "doctor", "neighbour",
        "neighbor", "partner", "fiance", "fiancee",
        "behen", "bahan", "bhai", "maa", "mummy", "papa", "pati", "patni", "dost", "sahab",
    )

    fun from(heardRaw: String): Learned? {
        val heard = heardRaw.trim()
        if (heard.isBlank()) return null
        val lower = heard.lowercase(Locale.US).trimEnd('.', '!', ',', '?')

        // "my name is Rishu" / "mera naam Rishu hai"
        Regex("^(?:my name is|mera naam|i am called)\\s+([\\p{L}][\\p{L} .]{1,30})$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val value = titleCase(match.groupValues[1].trim().removeSuffix(" hai"))
                return Learned(fact = Brain.Fact("name", value))
            }

        // "remember that Khushi is my sister"  —  the relation is matched by name, not by a regex
        // wildcard, so "my sister is Khushi" (the other word order) can be handled just below
        // instead of the wildcard swallowing "my sister" as a name.
        Regex("^(?:remember(?: that)?\\s+)?(.+?)\\s+(?:is|are)\\s+my\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val who = match.groupValues[1].trim()
                val relation = match.groupValues[2].trim()
                if (relation !in RELATIONS) return@let
                return Learned(person = Brain.Person(titleCase(who), relation))
            }

        // "my sister is Khushi" / "meri behen khushi hai"
        Regex("^my\\s+([a-z ]{3,20})\\s+(?:is|are)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val relation = match.groupValues[1].trim()
                if (relation !in RELATIONS) return@let
                return Learned(person = Brain.Person(titleCase(match.groupValues[2].trim()), relation))
            }

        // "Khushi meri behen hai" / "khushi meri behen hai"
        Regex("^([\\p{L}][\\p{L} .]{0,28}?)\\s+meri\\s+([a-z]+)\\s+hai$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val relation = match.groupValues[2].trim()
                if (relation !in RELATIONS) return@let
                return Learned(person = Brain.Person(titleCase(match.groupValues[1].trim()), relation))
            }

        // "meri behen ka naam Khushi hai"
        Regex("^meri\\s+([a-z]+)\\s+ka\\s+naam\\s+([\\p{L}][\\p{L} .]{1,28}?)\\s+hai$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val relation = match.groupValues[1].trim()
                if (relation !in RELATIONS) return@let
                return Learned(person = Brain.Person(titleCase(match.groupValues[2].trim()), relation))
            }

        // "Khushi's birthday is 12 May" / "khushi ka birthday 12 may hai"
        Regex(
            "^([\\p{L}][\\p{L} .]{0,28}?)(?:'s)?\\s+(?:ka\\s+)?birthday\\s+(?:is\\s+|hai\\s+)?(.{3,40})$",
            RegexOption.IGNORE_CASE,
        ).find(lower)?.let { match ->
            val who = match.groupValues[1].trim()
            val date = match.groupValues[2].trim().removeSuffix(" hai").trim()
            if (date.isBlank()) return@let
            return Learned(person = Brain.Person(titleCase(who)), fact = Brain.Fact("birthday:${who}", date))
        }

        // "my birthday is 3 March" / "mera birthday 3 march hai"
        Regex("^(?:my|mera)\\s+birthday\\s+(?:is\\s+|hai\\s+)?(.{3,40})$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val date = match.groupValues[1].trim().removeSuffix(" hai").trim()
                if (date.isBlank()) return@let
                return Learned(fact = Brain.Fact("birthday", date))
            }

        // "I live in Delhi" / "main delhi mein rehta hoon"
        Regex("^(?:i live in|i am from|main)\\s+([\\p{L} ]{2,30}?)(?:\\s+mein\\s+rehta\\s+hoon|\\s+se\\s+hoon)?$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val place = match.groupValues[1].trim()
                if (place.isBlank() || place.split(' ').size > 3) return@let
                if (!lower.contains("live in") && !lower.contains("from") && !lower.contains("se hoon")) return@let
                return Learned(fact = Brain.Fact("city", titleCase(place)))
            }

        // "I work at Infosys" / "i study at Delhi University"
        Regex("^i\\s+(work|study)\\s+(?:at|in)\\s+(.{2,40})$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                return Learned(fact = Brain.Fact(match.groupValues[1].lowercase(Locale.US), titleCase(match.groupValues[2].trim())))
            }

        // "I like tea" / "I do not like loud music" / "my favourite colour is blue"
        Regex("^i\\s+(?:really\\s+)?(like|love|hate|dislike)\\s+(.{2,50})$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                val verb = match.groupValues[1].lowercase(Locale.US)
                val key = if (verb == "like" || verb == "love") "likes" else "dislikes"
                return Learned(fact = Brain.Fact(key, match.groupValues[2].trim()))
            }

        Regex("^my favourite ([a-z]+) is (.{2,40})$", RegexOption.IGNORE_CASE)
            .find(lower)?.let { match ->
                return Learned(fact = Brain.Fact("favourite ${match.groupValues[1]}", match.groupValues[2].trim()))
            }

        return null
    }

    private fun titleCase(value: String): String = value
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { char -> char.uppercaseChar() }
        }
        .take(40)
}
