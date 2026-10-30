package com.jarvis.assistant.voice

import android.content.Context
import java.util.Locale
import kotlin.math.sqrt

/**
 * Staying in the conversation instead of ending it.
 *
 * One spoken sentence used to be the whole session: say "open whatsapp", he opens it, says "done"
 * and the microphone closes. The operator then has to reach for the orb — or say the wake phrase
 * again — for the obvious next sentence ("message Divik hi, on my way"), even though they are
 * already mid-thought.
 *
 * So after a turn finishes he keeps the ear open for a short window, asks what is next, and takes
 * anything said in that window as a normal command. The window is bounded in every direction:
 *
 *  * a maximum number of turns, so a phone left on a table cannot listen forever;
 *  * a maximum wall-clock window per turn;
 *  * words that end it immediately ("bas", "stop", "thank you");
 *  * silence ends it, quietly, without a failure card.
 *
 * The decision logic is pure — [isExit], [prompt], [hasSpeech] and [silenceAtEndMs] take no Context
 * — so the grammar of a conversation can be tested on the JVM without a phone or a microphone.
 */
object Conversation {

    private const val PREFS = "jarvis_voice_settings"
    private const val KEY_ENABLED = "conversation_enabled"
    private const val KEY_WINDOW = "conversation_window_ms"
    private const val KEY_TURNS = "conversation_max_turns"

    /** Nine seconds: long enough to think, short enough that the phone is not "listening" forever. */
    const val DEFAULT_WINDOW_MS = 9_000L
    const val MIN_WINDOW_MS = 4_000L
    const val MAX_WINDOW_MS = 20_000L

    const val DEFAULT_TURNS = 6
    const val MIN_TURNS = 1
    const val MAX_TURNS = 12

    /** How much trailing quiet means "they have finished talking". */
    const val SILENCE_DONE_MS = 900L

    /** RMS floor for 16-bit PCM: room tone sits far below this, speech far above it. */
    const val SPEECH_RMS = 240.0

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * On by default: the first turn was already an explicit "I want to talk to you", so closing the
     * ear the instant a step finishes is the surprising behaviour, not the open one.
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun windowMs(context: Context): Long = prefs(context)
        .getInt(KEY_WINDOW, DEFAULT_WINDOW_MS.toInt())
        .toLong()
        .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)

    fun setWindowMs(context: Context, millis: Long) {
        prefs(context).edit()
            .putInt(KEY_WINDOW, millis.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS).toInt())
            .apply()
    }

    fun maxTurns(context: Context): Int = prefs(context)
        .getInt(KEY_TURNS, DEFAULT_TURNS)
        .coerceIn(MIN_TURNS, MAX_TURNS)

    fun setMaxTurns(context: Context, turns: Int) {
        prefs(context).edit()
            .putInt(KEY_TURNS, turns.coerceIn(MIN_TURNS, MAX_TURNS))
            .apply()
    }

    // ---------------------------------------------------------------------------------------------
    // What he says to keep it open
    // ---------------------------------------------------------------------------------------------

    /**
     * The line that turns a finished command into a conversation.
     *
     * It is shaped by what just happened: after opening a chat app the useful question is who to
     * message, after an alarm it is whether anything else needs remembering. A generic "what next?"
     * every time is what makes an assistant feel like a form.
     */
    fun prompt(lang: Lang, lastTitle: String = ""): String = when (lang.replyLang) {
        Lang.HINGLISH -> hinglishPrompt(lastTitle)
        Lang.HI -> hindiPrompt(lastTitle)
        else -> englishPrompt(lastTitle)
    }

    /** Said when the operator ends the conversation, so it closes politely instead of going mute. */
    fun goodbye(lang: Lang): String = when (lang.replyLang) {
        Lang.HINGLISH -> "Theek hai boss, main yahin hoon."
        Lang.HI -> "ठीक है बॉस, मैं यहीं हूँ।"
        else -> "Alright boss, I am here."
    }

    /** Said when the window closes on silence — never an error, just a line. */
    fun slept(lang: Lang): String = when (lang.replyLang) {
        Lang.HINGLISH -> "Kuch suna nahi, main wait kar raha hoon."
        Lang.HI -> "कुछ सुनाई नहीं दिया, मैं रुका हुआ हूँ।"
        else -> "I did not hear anything, so I will wait."
    }

    private fun hinglishPrompt(lastTitle: String): String {
        val title = lastTitle.uppercase(Locale.US)
        return when {
            mentions(title, "MESSAGE", "WHATSAPP", "TELEGRAM", "CHAT", "SMS") ->
                "Boss, kisko message karna hai?"
            mentions(title, "CALL") -> "Boss, kisko call karna hai?"
            mentions(title, "ALARM", "REMINDER") -> "Boss, aur kuch yaad rakhna hai?"
            mentions(title, "MUSIC", "SONG", "PLAY") -> "Boss, aur kuch bajau?"
            mentions(title, "OPEN", "APP") -> "Boss, ab kya karna hai? Kisi ko message karna hai?"
            else -> "Boss, aur kya karna hai?"
        }
    }

    private fun hindiPrompt(lastTitle: String): String {
        val title = lastTitle.uppercase(Locale.US)
        return when {
            mentions(title, "MESSAGE", "WHATSAPP", "TELEGRAM", "CHAT", "SMS") ->
                "बॉस, किसको संदेश भेजना है?"
            mentions(title, "CALL") -> "बॉस, किसको कॉल करना है?"
            mentions(title, "ALARM", "REMINDER") -> "बॉस, और कुछ याद रखना है?"
            else -> "बॉस, अब क्या करना है?"
        }
    }

    private fun englishPrompt(lastTitle: String): String {
        val title = lastTitle.uppercase(Locale.US)
        return when {
            mentions(title, "MESSAGE", "WHATSAPP", "TELEGRAM", "CHAT", "SMS") ->
                "Who am I messaging, boss?"
            mentions(title, "CALL") -> "Who am I calling, boss?"
            mentions(title, "ALARM", "REMINDER") -> "Anything else to remember?"
            mentions(title, "OPEN", "APP") -> "What next, boss? Want me to message someone?"
            else -> "What else, boss?"
        }
    }

    private fun mentions(title: String, vararg words: String): Boolean =
        words.any { title.contains(it) }

    // ---------------------------------------------------------------------------------------------
    // Reading the reply
    // ---------------------------------------------------------------------------------------------

    private val EXIT_PHRASES = setOf(
        "bas", "bas itna", "bas itna hi", "bas karo", "bas ho gaya", "ho gaya",
        "stop", "stop karo", "stop it", "band karo", "band", "ruko", "ruko bas",
        "theek hai", "thik hai", "theek hai bas", "ok theek hai", "achha theek hai",
        "that's all", "thats all", "that is all", "that will be all", "nothing",
        "nothing else", "nothing more", "no thanks", "no thank you", "not now", "later",
        "bye", "bye bye", "goodbye", "good bye", "ok bye", "okay bye", "chalo bye",
        "so jao", "so ja", "so jaao", "kuch nahi", "kuch nahi chahiye", "kuch nahi bas",
        "cancel", "cancel karo", "shut up", "chup", "dhanyawad", "thank you", "thanks",
        "thank you jarvis", "thanks jarvis", "shukriya", "enough", "no", "nahi", "na",
        "naa", "nope", "chal", "chalo",
    )

    /** Lowercased, punctuation flattened, single-spaced. Keeps letters and digits only. */
    fun normalize(text: String): String = text.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * True when the operator is closing the conversation.
     *
     * Only a whole-sentence match counts. "nahi" on its own is a no; "nahi, whatsapp kholo" is a
     * correction and must not be mistaken for one.
     */
    fun isExit(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val clean = normalize(text)
        if (clean.isBlank()) return false
        if (clean in EXIT_PHRASES) return true
        // "bas itna hi kaafi hai" and similar tails.
        return clean.length <= 28 && (clean.endsWith(" bas") || clean.endsWith(" bas itna"))
    }

    // ---------------------------------------------------------------------------------------------
    // Audio
    // ---------------------------------------------------------------------------------------------

    /**
     * True when the buffer contains something louder than room tone.
     *
     * Used to decide whether a captured window is worth sending to a provider at all: a silent
     * window ends the conversation for free instead of costing a transcription request.
     */
    fun hasSpeech(pcm: ByteArray, threshold: Double = SPEECH_RMS): Boolean {
        if (pcm.size < 640) return false
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = sampleAt(pcm, index)
            sum += sample.toDouble() * sample.toDouble()
            count += 1
            index += 2
        }
        if (count == 0) return false
        return sqrt(sum / count) > threshold
    }

    /**
     * Milliseconds of quiet at the end of the buffer, measured in 20 ms frames.
     *
     * This is what makes the conversation feel responsive on the provider route: instead of always
     * waiting the full window, recording stops once the operator has been quiet for
     * [SILENCE_DONE_MS].
     */
    fun silenceAtEndMs(pcm: ByteArray, sampleRate: Int = 16_000, threshold: Double = SPEECH_RMS): Long {
        val bytesPerFrame = maxOf(2, (sampleRate / 50) * 2) // 20 ms
        var quietFrames = 0
        var end = pcm.size - (pcm.size % bytesPerFrame)
        while (end > 0) {
            val start = end - bytesPerFrame
            if (frameIsLoud(pcm, start, end, threshold)) break
            quietFrames += 1
            end = start
        }
        return quietFrames * 20L
    }

    private fun frameIsLoud(pcm: ByteArray, start: Int, end: Int, threshold: Double): Boolean {
        var sum = 0.0
        var count = 0
        var index = start
        while (index + 1 < end) {
            val sample = sampleAt(pcm, index)
            sum += sample.toDouble() * sample.toDouble()
            count += 1
            index += 2
        }
        if (count == 0) return false
        return sqrt(sum / count) > threshold
    }

    /** Little-endian signed 16-bit, the only format [MicRecorder] records. */
    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val low = pcm[index].toInt() and 0xFF
        val high = pcm[index + 1].toInt() and 0xFF
        return ((high shl 8) or low).toShort().toInt()
    }
}
