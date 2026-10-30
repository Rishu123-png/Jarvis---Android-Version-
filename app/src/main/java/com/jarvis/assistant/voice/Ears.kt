package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.ai.AiProvider
import com.jarvis.assistant.wake.WakeCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The microphone, reduced to bytes.
 *
 * Nothing here decides what to do with the audio — that is [Whisper]'s job, and [VoiceController]
 * owns the decision to record at all. This class only guarantees that a recording always ends: the
 * worker thread exits on [stop], on [cancel], or after [MAX_SECONDS], whichever comes first.
 */
class MicRecorder(
    /**
     * Called once, on the recorder thread, when the operator has spoken and then stopped. The
     * caller turns that into an ordinary stop, so a pause ends the turn without a tap.
     */
    private val onQuiet: (() -> Unit)? = null,
) {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /** Returns a human-readable reason when the microphone cannot start, or null on success. */
    fun start(): String? {
        if (running) return null

        val minBuffer = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        } catch (t: Throwable) {
            -1
        }
        if (minBuffer <= 0) return "This device refused a 16 kHz microphone stream."
        val bufferSize = maxOf(minBuffer * 2, 4_096)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            return "The microphone is unavailable (${t.javaClass.simpleName})."
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            try {
                recorder.release()
            } catch (t: Throwable) {
                // Nothing to release.
            }
            return "Another app is holding the microphone."
        }

        synchronized(pcm) { pcm.reset() }
        running = true
        record = recorder

        val started = try {
            recorder.startRecording()
            true
        } catch (t: Throwable) {
            false
        }
        if (!started) {
            running = false
            try {
                recorder.release()
            } catch (t: Throwable) {
                // Already gone.
            }
            record = null
            return "Recording could not start."
        }

        worker = Thread({
            val buffer = ByteArray(bufferSize)
            var total = 0
            val quiet = if (onQuiet == null) null else WakeCapture.Tracker()
            while (running && total < SAMPLE_RATE * 2 * MAX_SECONDS) {
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (t: Throwable) {
                    -1
                }
                if (read <= 0) break
                synchronized(pcm) { pcm.write(buffer, 0, read) }
                total += read
                // The orb answers the room, and the same measurement decides when the sentence
                // ended. Wrapped so the picture can never cost a recording.
                try {
                    val loudness = Presence.rmsOf(buffer, read)
                    Presence.publishRms(loudness)
                    val chunkMs = read * 1_000 / (SAMPLE_RATE * 2)
                    if (quiet != null && quiet.feed(loudness, chunkMs) == WakeCapture.Decision.FINISH) {
                        // Stop this loop, then let the owner run an ordinary stop. Returning here
                        // means the thread is already on its way out when that join happens.
                        running = false
                        onQuiet?.invoke()
                        return@Thread
                    }
                } catch (t: Throwable) {
                    // Loudness is decoration; the transcript is the point.
                }
            }
        }, "jarvis-mic").apply {
            isDaemon = true
            start()
        }

        return null
    }

    /**
     * A copy of everything captured so far.
     *
     * The conversation window polls this so it can notice that the operator has stopped talking
     * and stop recording early, instead of always waiting the full window. The copy is taken under
     * the same lock the reader thread writes with, so it is never a half-written frame.
     */
    fun snapshot(): ByteArray = synchronized(pcm) { pcm.toByteArray() }

    /** Stops the stream and returns raw 16-bit mono PCM at [SAMPLE_RATE]. */
    fun stop(): ByteArray {
        running = false
        try {
            record?.stop()
        } catch (t: Throwable) {
            // Already stopped.
        }
        worker?.let { thread ->
            // Never join ourselves: the auto-stop callback arrives from this very thread.
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(600)
                } catch (t: Throwable) {
                    // Interrupted; the daemon thread will exit on its own.
                }
            }
        }
        try {
            record?.release()
        } catch (t: Throwable) {
            // Already released.
        }
        record = null
        worker = null
        // The microphone is shut: the orb should fall back to still rather than hold a peak.
        try {
            Presence.reset()
        } catch (t: Throwable) {
            // A picture cannot be allowed to fail a recording.
        }
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        synchronized(pcm) { pcm.reset() }
        return bytes
    }

    fun cancel() {
        stop()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 30
    }
}

/**
 * Speech to text through the provider the operator already pays for.
 *
 * The model lives at the provider, not in the APK: no 60 MB download, no native build, no ABI to
 * break. Only providers with an OpenAI-compatible `/audio/transcriptions` endpoint are accepted;
 * everything else falls back to the device recognizer in [LocalSpeech], so voice never becomes
 * unusable just because the key belongs to a provider without audio.
 */
object Whisper {

    data class Heard(val text: String? = null, val error: String? = null, val model: String? = null)

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_BODY = 4_000

    fun ready(context: Context): Boolean {
        val config = AiKeyStore.config(context) ?: return false
        return supports(config.provider)
    }

    fun supports(provider: AiProvider): Boolean = when (provider) {
        AiProvider.OPENAI, AiProvider.GROQ, AiProvider.CUSTOM -> true
        else -> false
    }

    /**
     * [hint] biases the transcript's script, not its content.
     *
     * Whisper decides on its own whether a Hindi sentence should come back in Devanagari or Roman
     * letters. A Hinglish speaker wants Roman, so a short Roman example is sent as the prompt —
     * the one lever a transcription endpoint offers for this. It is only sent when the operator was
     * already speaking Hinglish, so a Devanagari speaker is never pushed into Roman.
     */
    suspend fun transcribe(
        context: Context,
        pcm: ByteArray,
        hint: String? = null,
    ): Heard = withContext(Dispatchers.IO) {
        val config = AiKeyStore.config(context)
            ?: return@withContext Heard(error = "No AI key is stored.")
        val key = AiKeyStore.apiKey(context)
            ?: return@withContext Heard(error = "No AI key is stored.")
        if (!supports(config.provider)) {
            return@withContext Heard(error = "${config.provider.label} has no speech endpoint.")
        }
        if (pcm.size < MicRecorder.SAMPLE_RATE) {
            return@withContext Heard(error = "Nothing was recorded.")
        }

        val model = modelFor(config.provider)
        val boundary = "----jarvis" + System.currentTimeMillis()
        val body = buildBody(
            boundary = boundary,
            model = model,
            wav = Wav.wrapPcm(pcm, MicRecorder.SAMPLE_RATE),
            hint = hint,
        )

        val url = "${baseUrl(config.provider, config.baseUrl)}/audio/transcriptions"
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }
        } catch (t: Throwable) {
            return@withContext Heard(error = "Could not open a connection to ${config.provider.label}.")
        }

        try {
            connection.outputStream.use { stream -> stream.write(body) }
            val code = try {
                connection.responseCode
            } catch (t: Throwable) {
                return@withContext Heard(error = "The transcription request failed to send.")
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = try {
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (t: Throwable) {
                ""
            }.take(MAX_BODY)

            if (code !in 200..299) {
                return@withContext Heard(error = explain(code, text, config.provider.label))
            }

            val spoken = try {
                JSONObject(text).optString("text").trim()
            } catch (t: Throwable) {
                ""
            }
            if (spoken.isEmpty()) Heard(error = "The provider returned no words.")
            else Heard(text = spoken, model = model)
        } catch (t: Throwable) {
            Heard(error = "Transcription failed (${t.javaClass.simpleName}).")
        } finally {
            try {
                connection.disconnect()
            } catch (t: Throwable) {
                // Already closed.
            }
        }
    }

    private fun modelFor(provider: AiProvider): String = when (provider) {
        AiProvider.GROQ -> "whisper-large-v3-turbo"
        else -> "whisper-1"
    }

    private fun baseUrl(provider: AiProvider, custom: String): String = when (provider) {
        AiProvider.GROQ -> "https://api.groq.com/openai/v1"
        AiProvider.OPENAI -> "https://api.openai.com/v1"
        else -> custom.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
    }

    private fun buildBody(
        boundary: String,
        model: String,
        wav: ByteArray,
        hint: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
        write("Content-Type: audio/wav\r\n\r\n")
        out.write(wav)
        write("\r\n--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
        write("--$boundary\r\n")
        write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
        if (!hint.isNullOrBlank()) {
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n")
            write(hint.take(200))
            write("\r\n")
        }
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun explain(code: Int, body: String, provider: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (t: Throwable) {
            ""
        }.take(120)
        return when {
            code == 401 || code == 403 -> "$provider rejected the key for speech."
            code == 404 -> "$provider has no transcription endpoint on this key."
            code == 413 -> "The recording was too long for $provider."
            code == 429 -> "$provider is rate-limiting speech requests."
            detail.isNotBlank() -> "HTTP $code · $detail"
            else -> "HTTP $code from $provider."
        }
    }
}
