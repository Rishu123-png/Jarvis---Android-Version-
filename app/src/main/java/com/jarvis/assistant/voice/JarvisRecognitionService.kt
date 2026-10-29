package com.jarvis.assistant.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognitionService.Callback
import android.speech.SpeechRecognizer

/**
 * The recognition half of the assistant seat.
 *
 * Android's assistant metadata refuses to parse without a `recognitionService`, so one has to exist
 * even though JARVIS never routes speech through it: the app asks the phone's own recogniser for a
 * sentence (see `Ears`), and the assist gesture is handled by [JarvisSession], which opens the app.
 *
 * So this service is honest about having nothing to say. If anything ever does hand it audio, it
 * replies "no speech input" immediately instead of hanging or pretending to listen — a silent
 * service would look like a broken microphone to whatever called it.
 */
class JarvisRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val callback = listener ?: return
        try {
            callback.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        } catch (t: Throwable) {
            // The caller went away between the bind and this line.
        }
    }

    override fun onCancel(listener: Callback?) {
        // Nothing was running.
    }

    override fun onStopListening(listener: Callback?) {
        // Nothing was running.
    }
}
