package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's own recognizer — the fallback when the operator has no key, or when the key belongs
 * to a provider without a speech endpoint.
 *
 * Free, offline on most devices, and no download. The recognizer is created on the main thread,
 * always destroyed after a session, and every await is bounded: a recognizer that never calls back
 * must never leave the HUD stuck.
 */
class LocalSpeech(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val outcome = CompletableDeferred<Outcome>()

    private var recognizer: SpeechRecognizer? = null

    data class Outcome(val text: String? = null, val error: String? = null)

    @Volatile
    private var finished = false

    /** Starts listening. Any failure surfaces through [await] as an error, never as a hang. */
    fun start() {
        main.post {
            if (finished) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                finish(Outcome(error = "This device has no speech recognizer."))
                return@post
            }
            val created = try {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            } catch (t: Throwable) {
                null
            }
            if (created == null) {
                finish(Outcome(error = "The speech recognizer is unavailable right now."))
                return@post
            }
            recognizer = created
            created.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            try {
                created.startListening(intent)
            } catch (t: Throwable) {
                finish(Outcome(error = "The recognizer refused to start."))
            }
        }
    }

    /** Second tap: stop listening and let the recognizer deliver its final result. */
    fun stop() {
        main.post {
            try {
                recognizer?.stopListening()
            } catch (t: Throwable) {
                // Nothing to stop.
            }
        }
        // A recognizer that goes quiet after stopListening must not stall the pipeline.
        main.postDelayed(
            {
                if (!finished) finish(Outcome(error = "No speech detected."))
            },
            6_000L,
        )
    }

    fun cancel() {
        finish(Outcome(error = null), silent = true)
    }

    suspend fun await(timeoutMs: Long = 12_000L): Outcome =
        withTimeoutOrNull(timeoutMs) { outcome.await() }
            ?: Outcome(error = "The recognizer did not answer in time.")

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = matches?.firstOrNull { it.isNotBlank() }?.trim()
            if (best.isNullOrEmpty()) {
                finish(Outcome(error = "I did not catch any words."))
            } else {
                finish(Outcome(text = best))
            }
        }

        override fun onError(error: Int) {
            finish(Outcome(error = describe(error)))
        }
    }

    private fun finish(result: Outcome, silent: Boolean = false) {
        if (finished) return
        finished = true
        main.post {
            val current = recognizer
            recognizer = null
            try {
                current?.cancel()
                current?.destroy()
            } catch (t: Throwable) {
                // Already released.
            }
        }
        if (silent) outcome.complete(Outcome(error = null)) else outcome.complete(result)
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone stream failed."
        SpeechRecognizer.ERROR_CLIENT -> "The recognizer dropped the session."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK -> "The recognizer needs a network route."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The recognizer timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "I did not catch any words."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy — try again."
        SpeechRecognizer.ERROR_SERVER -> "The recognizer server refused the request."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I heard nothing."
        else -> "The recognizer failed (code $code)."
    }
}
