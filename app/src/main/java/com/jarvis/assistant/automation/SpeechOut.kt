package com.jarvis.assistant.automation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
 */
object SpeechOut {

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var languageChecked = false

    /** The language tag currently set on the engine, so we only touch it when it changes. */
    private var currentLanguage: String? = null

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
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "Nothing to say"

        val tts = ensureEngine(context) ?: return "No speech engine available on this device"
        applyLanguage(tts, language)

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
    }
}
