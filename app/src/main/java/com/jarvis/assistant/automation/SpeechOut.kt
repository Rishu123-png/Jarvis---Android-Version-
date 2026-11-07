package com.jarvis.assistant.automation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.voice.Feelings
import com.jarvis.assistant.voice.VoiceGender
import com.jarvis.assistant.voice.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * Text-to-speech for the SAY step.
 *
 * Deliberately narrow: one engine, initialised on first use, flushed per utterance, and always
 * bounded by a timeout so a device with no voice data cannot stall a routine. No audio is recorded
 * and no microphone permission is involved.
 *
 * The voice itself is chosen, not inherited: most phones default to a bright female voice, so a
 * male voice is preferred for the language being spoken. When the device has no male voice for that
 * language, pitch is lowered instead — a wrong voice speaks someone else's assistant, a lower
 * pitch still sounds like the same one.
 */
object SpeechOut {

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var languageChecked = false

    /** The language tag currently set on the engine, so we only touch it when it changes. */
    private var currentLanguage: String? = null

    /** Language + gender of the voice currently installed, so the picker is scanned rarely. */
    private var currentVoiceKey: String? = null

    /**
     * The chosen voice's own pitch and rate.
     *
     * Kept separately from what is actually applied so a mood can be layered on top without the
     * engine drifting: base × tone, every time, never base × tone × tone.
     */
    private var basePitch = 1f
    private var baseRate = 1f

    private suspend fun ensureEngine(context: Context): TextToSpeech? {
        engine?.let { if (ready?.isCompleted == true) return it }

        val pending = CompletableDeferred<Boolean>()
        ready = pending

        val created = withContext(Dispatchers.Main) {
            try {
                TextToSpeech(context.applicationContext) { status ->
                    if (!pending.isCompleted) pending.complete(status == TextToSpeech.SUCCESS)
                }
            } catch (t: Throwable) {
                if (!pending.isCompleted) pending.complete(false)
                null
            }
        }
        val initialised = withTimeoutOrNull(8_000L) { pending.await() } == true
        if (!initialised || created == null) {
            try {
                created?.shutdown()
            } catch (t: Throwable) {
                // Nothing to do: the engine never became usable.
            }
            ready = null
            return null
        }

        if (!languageChecked) {
            languageChecked = true
            try {
                val result = created.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    created.setLanguage(Locale.US)
                }
            } catch (t: Throwable) {
                // Keep the default voice rather than failing the step.
            }
        }

        engine = created
        return created
    }

    /**
     * Speaks [text] and returns the failure reason, or null on success.
     * Blocks until the utterance finishes or [timeoutMillis] elapses.
     */
    suspend fun speak(
        context: Context,
        text: String,
        timeoutMillis: Long = 20_000L,
        /** BCP-47 tag, e.g. "hi-IN". Null keeps the device default. */
        language: String? = null,
        /** Mood colouring: concern is slower and softer, playfulness a touch quicker. */
        tone: Feelings.Tone = Feelings.Tone.NEUTRAL,
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "Nothing to say"

        val tts = ensureEngine(context) ?: return "No speech engine available on this device"
        applyLanguage(tts, language)
        applyVoice(context, tts)
        applyTone(tts, tone)

        val utteranceId = "jarvis-" + UUID.randomUUID()
        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (!done.isCompleted) done.complete(true)
            }

            @Deprecated("Required by the platform interface")
            override fun onError(id: String?) {
                if (!done.isCompleted) done.complete(false)
            }

            override fun onError(id: String?, errorCode: Int) {
                if (!done.isCompleted) done.complete(false)
            }
        })

        return try {
            val queued = withContext(Dispatchers.Main) {
                tts.speak(trimmed.take(600), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            if (queued != TextToSpeech.SUCCESS) return "Speech engine rejected the request"
            val finished = withTimeoutOrNull(timeoutMillis) { done.await() } == true
            if (finished) null else "Speech did not finish in time"
        } catch (t: Throwable) {
            "Speech failed: ${t.javaClass.simpleName}"
        }
    }

    /**
     * Switches the voice to the operator's language.
     *
     * A Hindi sentence read by an English voice is barely understandable, so the language the router
     * detected is handed all the way to the engine. If the phone lacks that voice, the previous one
     * stays — a reply in the wrong accent beats silence.
     */
    private suspend fun applyLanguage(tts: TextToSpeech, language: String?) {
        if (language.isNullOrBlank() || language == currentLanguage) return
        val locale = try {
            Locale.forLanguageTag(language)
        } catch (t: Throwable) {
            return
        }
        val applied = withContext(Dispatchers.Main) {
            try {
                tts.setLanguage(locale)
            } catch (t: Throwable) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        if (applied == TextToSpeech.LANG_MISSING_DATA || applied == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Try the bare language ("hi" rather than "hi-IN") before giving up on it.
            val fallback = withContext(Dispatchers.Main) {
                try {
                    tts.setLanguage(Locale(locale.language))
                } catch (t: Throwable) {
                    TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
            if (fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED) {
                currentLanguage = locale.language
            }
            return
        }
        currentLanguage = language
    }

    /**
     * Installs the operator's voice choice for the language currently set.
     *
     * Scanned only when the language or the choice changes: enumerating voices can take a moment on
     * engines that query their installed data, and a spoken sentence must not wait for it twice.
     */
    private suspend fun applyVoice(context: Context, tts: TextToSpeech) {
        val gender = try {
            VoiceSettings.gender(context)
        } catch (t: Throwable) {
            VoiceGender.MALE
        }
        val key = "${currentLanguage ?: "default"}|${gender.id}"
        if (key == currentVoiceKey) return

        // Enumerating voices can query the engine's installed data, so it never happens on the
        // caller's thread — which, on the voice surface, is the main thread.
        val chosen = withContext(Dispatchers.Default) {
            val voices = try {
                tts.voices
            } catch (t: Throwable) {
                null
            }
            pickVoice(voices, currentLanguage, gender)
        }

        withContext(Dispatchers.Main) {
            try {
                val installed = chosen != null && tts.setVoice(chosen) == TextToSpeech.SUCCESS
                basePitch = when {
                    // A real male voice: keep it natural, only a touch below centre.
                    gender == VoiceGender.MALE && installed -> 0.94f
                    // No male voice on this phone: the pitch is the only honest lever left.
                    gender == VoiceGender.MALE -> 0.78f
                    else -> 1.05f
                }
                baseRate = if (gender == VoiceGender.MALE) 0.97f else 1.0f
                tts.setPitch(basePitch)
                tts.setSpeechRate(baseRate)
            } catch (t: Throwable) {
                // Keep whatever voice the engine chose rather than failing the sentence.
            }
        }
        currentVoiceKey = key
    }

    /** Best voice for [gender] in [language], or null when the device offers nothing suitable. */
    private fun pickVoice(voices: Set<Voice>?, language: String?, gender: VoiceGender): Voice? {
        if (voices.isNullOrEmpty()) return null
        val wanted = try {
            if (language.isNullOrBlank()) Locale.getDefault() else Locale.forLanguageTag(language)
        } catch (t: Throwable) {
            Locale.getDefault()
        }
        val sameLanguage = voices.filter { it.locale.language.equals(wanted.language, ignoreCase = true) }
        val pool = sameLanguage.ifEmpty { voices.filter { it.locale == Locale.getDefault() } }
        if (pool.isEmpty()) return null

        val best = pool.sortedByDescending { scoreVoice(it, gender) }.first()
        // Never swap to the opposite gender on purpose: keeping the engine's default voice and
        // shifting its pitch is less jarring than a voice the operator did not ask for.
        return if (scoreVoice(best, gender) < 0) null else best
    }

    /**
     * Engines describe gender inconsistently: some put `male` in the feature list, some in the voice
     * name (`hi-in-x-hib-local`), a few do not say at all. Anything unsaid scores zero, which is
     * why the pitch fallback in [applyVoice] matters as much as this.
     */
    private fun scoreVoice(voice: Voice, gender: VoiceGender): Int {
        val haystack = (voice.name + " " + voice.features.joinToString(" ")).lowercase(Locale.US)
        val tokens = haystack.split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
        val female = tokens.contains("female") || tokens.contains("woman")
        val male = !female && (tokens.contains("male") || tokens.contains("man"))

        var score = when (gender) {
            VoiceGender.MALE -> if (male) 20 else if (female) -20 else 0
            VoiceGender.FEMALE -> if (female) 20 else if (male) -20 else 0
        }
        // A voice that works with no network is worth more on a phone that is often offline.
        if (!voice.isNetworkConnectionRequired) score += 2
        if (tokens.contains("high")) score += 1
        return score
    }

    /**
     * Applies mood on top of the voice, for this sentence only.
     *
     * Bounded on purpose: a concerned voice should sound like the same person speaking gently, not
     * like a different engine. Anything outside a narrow band would read as a broken pitch.
     */
    private suspend fun applyTone(tts: TextToSpeech, tone: Feelings.Tone) {
        val pitch = (basePitch * tone.pitch).coerceIn(0.55f, 1.6f)
        val rate = (baseRate * tone.rate).coerceIn(0.6f, 1.3f)
        withContext(Dispatchers.Main) {
            try {
                tts.setPitch(pitch)
                tts.setSpeechRate(rate)
            } catch (t: Throwable) {
                // Keep the previous tone rather than failing the sentence.
            }
        }
    }

    /** Forces the next sentence to choose a voice again. Called when the setting changes. */
    fun forgetVoice() {
        currentVoiceKey = null
    }

    /** Releases the engine when the app goes away. Safe to call repeatedly. */
    fun release() {
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (t: Throwable) {
            // Ignore: shutdown on an already-dead engine is harmless.
        }
        engine = null
        ready = null
        languageChecked = false
        currentLanguage = null
        currentVoiceKey = null
    }
}
