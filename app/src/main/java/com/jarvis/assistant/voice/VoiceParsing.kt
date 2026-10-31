package com.jarvis.assistant.voice

import java.util.Locale

/** What a camera sentence is asking for. */
data class CameraAsk(val capture: Boolean, val showNewest: Boolean)

/** What a reminder sentence is asking for. Exactly one of the two time forms is set. */
data class ReminderAsk(
    val inSeconds: Long?,
    val atClock: String?,
    val text: String,
)

/**
 * The spoken-language parsers, with no Android in them.
 *
 * These used to live inside [IntentRouter], where a `Context` made them unreachable from a JVM test.
 * They are pure string functions: same input, same output, no device. [IntentRouter] is the only
 * caller, and every rule here is covered by `VoiceParsingTest`.
 */
object VoiceParsing {

    // Keep letters and combining marks from every script; the local parser must not erase Devanagari.
    private val noise = Regex("[^\\p{L}\\p{M}\\p{N} :+@./'%-]")
    private val spaces = Regex("\\s+")

    /** Convert Unicode decimal digits to ASCII so Hindi/other locale numerals reach numeric parsers. */
    private fun asciiDigits(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            val digit = Character.digit(ch, 10)
            append(if (digit in 0..9) ('0'.code + digit).toChar() else ch)
        }
    }

    /** Lowercase, strip punctuation that never carries meaning, collapse whitespace. */
    fun normalise(raw: String): String =
        asciiDigits(raw).lowercase(Locale.US)
            .replace(noise, " ")
            .replace(spaces, " ")
            .trim()
            .trimEnd('.', ',', '!', '?')
            .trim()

    /** People speak in chains: "open whatsapp, open chat with Divik and say hi". */
    fun flatten(clean: String): String = clean
        .replace("open chat with", "chat with")
        .replace("open the chat with", "chat with")
        .replace("open chat", "chat")
        .replace("and say", "say")
        .replace("and tell", "say")
        .replace("and message", "say")
        .replace(spaces, " ")
        .trim()

    /** Returns (app, contact, text) for the phrasings people actually use. */
    fun parseMessage(clean: String): Triple<String, String, String>? {
        val app = when {
            clean.contains("telegram") || clean.contains("टेलीग्राम") -> "telegram"
            clean.contains("sms") || clean.contains("text message") || clean.contains("एसएमएस") -> "sms"
            else -> "whatsapp"
        }

        // Common Devanagari phrasing: "राहुल को व्हाट्सएप पर संदेश भेजो कि मैं देर से आऊँगा".
        val hindi = Regex(
            "^(.+?)\\s+को\\s+(?:(व्हाट्सएप|व्हाट्सऐप|टेलीग्राम|एसएमएस|whatsapp|telegram|sms)\\s+पर\\s+)?" +
                "(?:(?:मैसेज|संदेश|message)\\s*)?(?:भेजो|भेजिए|भेज|लिखो|कहो)(?:\\s+कि)?\\s+(.+)$",
        ).find(clean)
        if (hindi != null) {
            val selectedApp = when (hindi.groupValues[2]) {
                "टेलीग्राम", "telegram" -> "telegram"
                "एसएमएस", "sms" -> "sms"
                "व्हाट्सएप", "व्हाट्सऐप", "whatsapp" -> "whatsapp"
                else -> app
            }
            return Triple(selectedApp, hindi.groupValues[1].trim(), hindi.groupValues[3].trim())
        }

        val commandish = Regex(
            "^(?:open\\s+)?(?:send|message|msg|text|chat|whatsapp|telegram|wa|sms|dm|write)\\b",
        ).containsMatchIn(clean)
        if (!commandish) return null

        val body = clean
            .replace(Regex("\\b(?:whatsapp|telegram|sms|wa|dm)\\b"), " ")
            .replace(Regex("\\b(?:send|message|msg|text|write|open|chat|to|with)\\b"), " ")
            .replace(spaces, " ")
            .trim()
        if (body.isEmpty()) return null

        // "<contact> say <text>" / "<contact> saying <text>" / "<contact> bol <text>"
        Regex("^(.+?)\\s+(?:say|saying|that|bol|ki|likh)\\s+(.+)$").find(body)?.let { match ->
            val contact = match.groupValues[1].trim()
            val text = match.groupValues[2].trim()
            if (contact.isNotBlank() && text.isNotBlank()) return Triple(app, contact, text)
        }

        // "<contact>: <text>"
        val colon = body.split(":").map { it.trim() }
        if (colon.size >= 2 && colon[0].isNotBlank() && colon[1].isNotBlank()) {
            return Triple(app, colon[0], colon.drop(1).joinToString(": "))
        }

        // Just a name: open the chat, write nothing.
        if (body.split(' ').size <= 4) return Triple(app, body, "")

        return null
    }

    /** Contact slot from English, Hinglish or common Devanagari call requests. */
    fun parseCallTarget(clean: String): String? {
        val patterns = listOf(
            Regex("^(?:कृपया\\s+|मुझे\\s+)?(.+?)\\s+को\\s+(?:कॉल|फोन)\\s+(?:करो|करिए|कर|लगाओ|मिलाओ)$"),
            Regex("^(.+?)\\s+(?:(?:को|ko)\\s+)?(?:call|phone|dial)\\s+(?:karo|kar do|lagao)$"),
            Regex("^(?:call|phone|dial|ring)\\s+(?:to\\s+)?(.+)$"),
        )
        val match = patterns.firstNotNullOfOrNull { it.find(clean.trim()) } ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    fun parseVolume(clean: String): Int? =
        Regex("(?:volume|वॉल्यूम|awaaz|आवाज़)\\D{0,12}(\\d{1,3})").find(clean)
            ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)

    /** "alarm 7:30", "alarm 7 30 am", "set alarm at 6" -> "HH:mm". */
    fun parseAlarm(clean: String): String? {
        val match = Regex("(?:alarm|अलार्म)\\D{0,12}(\\d{1,2})[:. ]?(\\d{2})?\\s*(am|pm|सुबह|शाम|रात)?").find(clean)
            ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val period = match.groupValues[3].ifBlank {
            when {
                clean.contains("शाम") -> "शाम"
                clean.contains("रात") -> "रात"
                clean.contains("सुबह") -> "सुबह"
                else -> ""
            }
        }
        when (period) {
            "pm", "शाम", "रात" -> if (hour in 1..11) hour += 12
            "am", "सुबह" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    /** "timer 10 minutes" -> 600. */
    fun parseTimerSeconds(clean: String): Int? {
        val match = Regex(
            "(?:timer|टाइमर)\\D{0,12}(\\d{1,3})\\s*(second|sec|minute|min|hour|ghanta|minat|सेकंड|सेकेंड|मिनट|घंटा|घंटे)",
        ).find(clean) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("ghanta") || unit.startsWith("घंट") -> value * 3_600
            unit.startsWith("sec") || unit.startsWith("सेक") -> value
            else -> value * 60
        }
        return seconds.takeIf { it > 0 }
    }

    /** "open camera, capture and show me in gallery" -> capture + show. */
    fun cameraAsk(clean: String): CameraAsk? {
        val mentions = clean.contains("camera") || clean.contains("selfie") ||
            clean.contains("photo") || clean.contains("picture")
        if (!mentions) return null

        // A verb is required: "show me my photos" must not silently take a new one.
        val capture = clean.contains("capture") || clean.contains("take") ||
            clean.contains("snap") || clean.contains("click") || clean.contains("khich")
        if (!capture) return null

        val show = clean.contains("gallery") || clean.contains("show") ||
            clean.contains("open it") || clean.contains("dikha")
        return CameraAsk(capture = true, showNewest = show)
    }

    /** "remind me in 10 minutes to call dad", "remind me at 6:30 to leave". */
    fun parseReminder(clean: String): ReminderAsk? {
        val hindiReminder = clean.contains("याद दिल") || clean.contains("याद करा")
        if (!clean.contains("remind") && !hindiReminder) return null

        val delayMatch = Regex(
            "(?:in|after)\\s+(\\d{1,3})\\s*(second|sec|minute|min|hour|ghanta)s?" +
                "|(\\d{1,3})\\s*(सेकंड|सेकेंड|मिनट|घंटा|घंटे)\\s*में",
        ).find(clean)
        val atMatch = Regex("\\bat\\s+(\\d{1,2})[:. ]?(\\d{2})?\\s*(am|pm)?").find(clean)

        val inSeconds = delayMatch?.let { match ->
            val value = (match.groupValues[1].ifBlank { match.groupValues[3] }).toLongOrNull()
                ?: return@let null
            val unit = match.groupValues[2].ifBlank { match.groupValues[4] }
            when {
                unit.startsWith("hour") || unit == "ghanta" || unit.startsWith("घंट") -> value * 3_600
                unit.startsWith("sec") || unit.startsWith("सेक") -> value
                else -> value * 60
            }
        }?.takeIf { it > 0 }

        val atClock = atMatch?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return@let null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            when (match.groupValues[3]) {
                "pm" -> if (hour in 1..11) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) null
            else String.format(Locale.US, "%02d:%02d", hour, minute)
        }

        if (inSeconds == null && atClock == null) return null

        var text = clean
        delayMatch?.let { text = text.replace(it.value, " ") }
        atMatch?.let { text = text.replace(it.value, " ") }
        text = text
            .replace(Regex("^remind(?:er)?\\s+(?:me\\s+)?"), " ")
            .replace(Regex("^मुझे\\s+"), " ")
            .replace(Regex("(?:की\\s+)?याद\\s+दिल(?:ाओ|ाना)|याद\\s+कर(?:ाओ|ाना)?"), " ")
            .replace(Regex("\\bto\\b"), " ")
            .replace(spaces, " ")
            .trim()

        if (text.isBlank()) return null
        return ReminderAsk(inSeconds = inSeconds, atClock = atClock, text = text)
    }

    /** "18:30" -> "6:30 PM". Falls back to the input when it is not a valid HH:mm. */
    fun clock12(hhmm: String): String {
        val parts = hhmm.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return hhmm
        val suffix = if (hour < 12) "AM" else "PM"
        val display = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        return "$display:" + String.format(Locale.US, "%02d", minute) + " $suffix"
    }

    /** Contact-name comparison key: lowercase, letters and digits only. */
    fun normaliseName(value: String): String =
        value.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ")
            .replace(spaces, " ")
            .trim()
}
