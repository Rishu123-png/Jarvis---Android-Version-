package com.jarvis.assistant.data

import java.util.Locale

/**
 * Every way a person might ask for the same thing.
 *
 * This exists because the previous router was a chain of regexes, so "open gallery" worked and
 * "gallery kholo", "show my photos" and "album" did not. People do not memorise commands — they say
 * it differently every day, mix Hindi in, and half the time forget the exact word. So each intent
 * carries a list of phrasings, and matching is forgiving: exact, then prefix, then word-set, then
 * fuzzy (Jaro-Winkler), which catches typos and mis-hearings like "galery" or "watsapp".
 *
 * Pure Kotlin, no Android: every rule here is covered by `PhrasesTest` in CI.
 */
enum class PhraseIntent(val id: String) {
    TORCH, DND, SCREENSHOT, PHOTO, LAST_PHOTO, GALLERY, LAUNCHER, OPEN_APP,
    FILES_SEARCH, OPEN_FILE, DISTANCE, NAVIGATE, WHERE_AM_I,
    STEPS, WATER, HEART, SLEEP, HEALTH,
    CALCULATE, NOTIF_MISSED, NOTIF_READ, REPLY,
    MEDIA_PLAY, MEDIA_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, VOLUME_SET,
    LOCK_SCREEN, HOME, BACK, RECENTS, SHADE, QUICK_SETTINGS,
    BATTERY, STORAGE, NETWORK, TIME, DATE,
    IDENTITY, HELP, STOP, REPEAT,
    ALARM, TIMER, REMINDER, TRANSLATE, DOWNLOAD, SYSTEM_SETTINGS, CALL, MESSAGE,
}

data class PhraseHit(val intent: PhraseIntent, val alias: String, val score: Double)

object PhraseBook {

    /** Below this Jaro-Winkler score a fuzzy guess is a coin flip, so it is not offered. */
    const val FUZZY_FLOOR = 0.87

    private val ALIASES: Map<PhraseIntent, List<String>> = mapOf(
        PhraseIntent.TORCH to listOf(
            "torch", "flashlight", "torch on", "torch off", "turn on the torch", "turn off the torch",
            "light on", "light off", "flashlight on", "flashlight off", "torch chalu", "torch band",
            "torch jalao", "light jalao", "batti on", "batti off",
        ),
        PhraseIntent.DND to listOf(
            "dnd", "do not disturb", "dont disturb", "silent mode", "silent", "disturb mat karo",
            "do not disturb on", "do not disturb off", "phone silent",
        ),
        PhraseIntent.SCREENSHOT to listOf(
            "screenshot", "screen shot", "capture screen", "screen capture", "take a screenshot",
            "screenshot lo", "screenshot le lo", "grab the screen",
        ),
        PhraseIntent.PHOTO to listOf(
            "take a photo", "take photo", "click a photo", "click photo", "take a picture",
            "photo kheencho", "photo lo", "selfie", "capture photo", "camera se photo",
        ),
        PhraseIntent.LAST_PHOTO to listOf(
            "last photo", "show my last photo", "newest photo", "recent photo", "latest photo",
            "last photo dikhao", "show the last picture", "open the last photo",
        ),
        PhraseIntent.GALLERY to listOf(
            "gallery", "open gallery", "gallery kholo", "photos", "my photos", "show my photos",
            "pictures", "album", "photo gallery", "open photos", "meri gallery",
        ),
        PhraseIntent.LAUNCHER to listOf(
            "app drawer", "all apps", "show all apps", "open app drawer", "apps list",
        ),
        PhraseIntent.OPEN_APP to listOf(
            "open", "launch", "start", "run", "kholo", "khol do", "open up",
        ),
        PhraseIntent.FILES_SEARCH to listOf(
            "find file", "search file", "find a file", "where is the file", "locate file",
            "file dhundo", "file dhoondo", "kahan hai file", "search my files", "find my file",
        ),
        PhraseIntent.OPEN_FILE to listOf(
            "open file", "open the file", "open download", "open the newest download",
            "last download", "open last download", "file kholo",
        ),
        PhraseIntent.DISTANCE to listOf(
            "distance", "how far", "how far is", "distance to", "kitna door", "kitni doori",
            "doori", "how far away", "distance from here",
        ),
        PhraseIntent.NAVIGATE to listOf(
            "navigate", "navigate to", "take me to", "directions to", "guide me to", "raasta",
            "le chalo", "how do i get to",
        ),
        PhraseIntent.WHERE_AM_I to listOf(
            "where am i", "my location", "where is my location", "kahan hoon", "current location",
        ),
        PhraseIntent.STEPS to listOf(
            "steps", "step count", "how many steps", "my steps", "kitne steps", "kitne kadam",
            "pedometer", "steps today", "how much have i walked",
        ),
        PhraseIntent.WATER to listOf(
            "drank water", "i drank water", "add water", "log water", "water log", "paani",
            "paani piya", "one glass of water", "glass of water", "track water",
        ),
        PhraseIntent.HEART to listOf(
            "heart rate", "pulse", "my pulse", "dhadkan", "bpm", "heart beat",
        ),
        PhraseIntent.SLEEP to listOf(
            "how did i sleep", "sleep last night", "my sleep", "neend", "sleep hours",
        ),
        PhraseIntent.HEALTH to listOf(
            "health", "my health", "health summary", "how am i doing", "fitness", "health report",
            "sehat",
        ),
        PhraseIntent.CALCULATE to listOf(
            "calculate", "what is", "how much is", "kitna hota hai", "percent of", "percentage of",
            "convert", "plus", "multiplied by", "divided by", "how many", "solve",
        ),
        PhraseIntent.NOTIF_MISSED to listOf(
            "what did i miss", "what did i miss", "missed notifications", "unread",
            "kya miss kiya", "any notifications", "what is new", "new messages",
        ),
        PhraseIntent.NOTIF_READ to listOf(
            "read my notifications", "read notifications", "read the last message",
            "last message", "aakhri message", "read that again", "read it to me",
        ),
        PhraseIntent.REPLY to listOf(
            "reply", "reply to", "reply back", "answer", "jawab do", "reply karo", "tell them",
        ),
        PhraseIntent.MEDIA_PLAY to listOf(
            "play", "resume", "play music", "continue playing", "gaana bajao", "music chalao",
        ),
        PhraseIntent.MEDIA_PAUSE to listOf(
            "pause", "pause music", "stop music", "roko", "pause karo", "music band",
        ),
        PhraseIntent.MEDIA_NEXT to listOf(
            "next", "next track", "next song", "skip", "agla gaana", "skip song",
        ),
        PhraseIntent.MEDIA_PREVIOUS to listOf(
            "previous", "previous track", "previous song", "pichla gaana", "go back a song",
        ),
        PhraseIntent.VOLUME_UP to listOf(
            "louder", "volume up", "increase volume", "turn it up", "awaaz badhao", "awaaz up",
        ),
        PhraseIntent.VOLUME_DOWN to listOf(
            "quieter", "volume down", "decrease volume", "turn it down", "awaaz kam", "awaaz down",
        ),
        PhraseIntent.VOLUME_SET to listOf(
            "volume", "set volume", "volume level", "awaaz", "sound level",
        ),
        PhraseIntent.LOCK_SCREEN to listOf(
            "lock my phone", "lock the phone", "lock the screen", "screen lock", "phone lock karo",
            "lock phone",
        ),
        PhraseIntent.HOME to listOf("go home", "home screen", "press home", "home pe jao"),
        PhraseIntent.BACK to listOf("go back", "press back", "back", "wapas"),
        PhraseIntent.RECENTS to listOf("recent apps", "recents", "open recent apps", "recent tasks"),
        PhraseIntent.SHADE to listOf(
            "notification shade", "open notifications", "pull down notifications", "open the shade",
            "notification panel",
        ),
        PhraseIntent.QUICK_SETTINGS to listOf(
            "quick settings", "open control center", "control panel", "quick panel",
        ),
        PhraseIntent.BATTERY to listOf(
            "battery", "battery status", "how much battery", "kitni battery", "battery level",
        ),
        PhraseIntent.STORAGE to listOf(
            "storage", "how much storage", "free space", "disk space", "jagah kitni hai",
        ),
        PhraseIntent.NETWORK to listOf(
            "network", "wifi status", "am i online", "internet", "net status",
        ),
        PhraseIntent.TIME to listOf(
            "what time is it", "the time", "time now", "kitne baje", "current time",
        ),
        PhraseIntent.DATE to listOf(
            "what is the date", "todays date", "today date", "aaj kya tareekh", "what day is it",
        ),
        PhraseIntent.IDENTITY to listOf(
            "who made you", "who built you", "who created you", "who are you", "your name",
            "tumhe kisne banaya", "who is your owner", "who developed you", "what are you",
        ),
        PhraseIntent.HELP to listOf(
            "what can you do", "help", "capabilities", "what all can you do", "tum kya kar sakte ho",
            "list commands", "commands", "how do i use you",
        ),
        PhraseIntent.STOP to listOf(
            "cancel", "stop", "never mind", "nevermind", "chhodo", "rehne do", "abort", "forget it",
        ),
        PhraseIntent.REPEAT to listOf(
            "again", "repeat", "do it again", "repeat that", "same again", "phir se", "one more time",
        ),
        PhraseIntent.ALARM to listOf(
            "alarm", "set alarm", "set an alarm", "wake me up", "alarm laga do", "wake me at",
        ),
        PhraseIntent.TIMER to listOf(
            "timer", "set a timer", "countdown", "timer laga do", "set timer",
        ),
        PhraseIntent.REMINDER to listOf(
            "remind me", "reminder", "remind", "yaad dila do", "set a reminder",
        ),
        PhraseIntent.TRANSLATE to listOf(
            "translate", "translate this", "meaning of", "matlab kya", "what does it mean",
        ),
        PhraseIntent.DOWNLOAD to listOf(
            "download", "download this", "save this file", "download karo",
        ),
        PhraseIntent.SYSTEM_SETTINGS to listOf(
            "settings", "open settings", "phone settings", "system settings", "settings kholo",
        ),
        PhraseIntent.CALL to listOf("call", "phone", "dial", "ring", "call karo"),
        PhraseIntent.MESSAGE to listOf(
            "message", "send message", "send a message", "text", "whatsapp", "msg",
            "message bhejo", "text karo",
        ),
    )

    private val ALL: List<Pair<PhraseIntent, String>> =
        ALIASES.flatMap { (intent, list) -> list.map { intent to it } }
            .sortedByDescending { it.second.length }

    /** Lowercase, punctuation folded to spaces, whitespace collapsed. */
    fun clean(text: String): String = text.lowercase(Locale.US)
        .replace("'", "")
        .replace(Regex("[^\\p{L}\\p{N}%.+\\-*/ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun tokens(text: String): Set<String> =
        clean(text).split(' ').filter { it.isNotBlank() }.toSet()

    /** The best phrasing match, or null when nothing is close enough. */
    fun match(text: String): PhraseHit? = suggestions(text, limit = 1).firstOrNull()

    /**
     * Ranked candidate intents, best first.
     *
     * The scoring is ordered so an exact phrase always beats a longer fuzzy one: a person saying
     * "open" means open something, while "open gallery" is unambiguous and must win over it.
     */
    fun suggestions(text: String, limit: Int = 3): List<PhraseHit> {
        val clean = clean(text)
        if (clean.isBlank()) return emptyList()

        val words = clean.split(' ').toSet()
        val hits = mutableListOf<PhraseHit>()

        for ((intent, alias) in ALL) {
            val score = when {
                clean == alias -> 1.0
                clean.startsWith("$alias ") -> 0.96
                clean.endsWith(" $alias") -> 0.94
                clean.contains(" $alias ") -> 0.92
                // Same words, different order: "alarm set karo".
                tokens(alias) == words -> 0.90
                else -> {
                    // A near-perfect typo ("open galery") must beat the bare prefix "open", or
                    // every request starting with "open" would collapse into OPEN_APP.
                    val jw = jaroWinkler(clean, alias)
                    if (jw >= FUZZY_FLOOR) jw else 0.0
                }
            }
            if (score > 0.0) hits.add(PhraseHit(intent, alias, score))
        }

        return hits
            .sortedWith(compareByDescending<PhraseHit> { it.score }.thenByDescending { it.alias.length })
            .distinctBy { it.intent }
            .take(limit)
    }

    /** Every phrasing JARVIS knows for one intent — used by the "what can you do" answer. */
    fun aliasesFor(intent: PhraseIntent): List<String> = ALIASES[intent].orEmpty()

    /**
     * Jaro-Winkler similarity, 0..1.
     *
     * Chosen over edit distance because a mis-heard word is usually a *transposition or a missing
     * letter near the start* ("galery", "watsapp"), and Winkler's prefix bonus covers exactly that.
     */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val range = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0

        for (i in a.indices) {
            val start = (i - range).coerceAtLeast(0)
            val end = (i + range + 1).coerceAtMost(b.length)
            for (j in start until end) {
                if (bMatched[j] || a[i] != b[j]) continue
                aMatched[i] = true
                bMatched[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0

        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }

        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0

        var prefix = 0
        for (i in 0 until minOf(4, a.length, b.length)) {
            if (a[i] == b[i]) prefix++ else break
        }
        return jaro + prefix * 0.1 * (1.0 - jaro)
    }
}
