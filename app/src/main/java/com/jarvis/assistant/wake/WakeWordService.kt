package com.jarvis.assistant.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.voice.AssistantHandoff
import com.jarvis.assistant.voice.Presence
import com.jarvis.assistant.voice.WakeHandoff
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

/**
 * Listens for "Hey Jarvis" while the operator has turned it on, and then hears the command too.
 *
 * This is the one place in the app that holds the microphone for long. It is a foreground service
 * with the microphone type, so Android shows its indicator and the notification says what it is
 * doing.
 *
 * **Why it owns the audio loop.** It used to hand the microphone to Vosk's own `SpeechService`,
 * which is convenient and useless here: the recogniser is given a grammar of exactly one phrase, so
 * "hey jarvis open whatsapp" decoded to "hey jarvis" and the rest was thrown away before the app
 * could ever see it. Reading the microphone here means the same bytes that find the phrase are also
 * the bytes that become the command.
 *
 * The turn is now one motion:
 *
 *  1. audio flows into a small ring buffer *and* the wake recogniser;
 *  2. on a hit, the ring is kept — that is the tail of the phrase and the first word after it;
 *  3. capture continues until [WakeCapture.Tracker] hears the sentence end;
 *  4. the microphone is released and the audio is left in [WakeHandoff] for the app to transcribe.
 *
 * If nothing follows the wake word within [WakeCapture.WAIT_FOR_SPEECH_MS], or the microphone cannot
 * be had, it behaves exactly as it used to: release, open JARVIS, listen there.
 */
class WakeWordService : Service() {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var record: AudioRecord? = null
    private var pump: Thread? = null

    @Volatile
    private var listening = false

    private val main = Handler(Looper.getMainLooper())
    private var handled = false
    private var rearm: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakeWord.disable(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        // Must promote to foreground straight away, or Android stops the service.
        promoteToForeground("Listening for \"Hey Jarvis\"")

        if (!WakeWord.isEnabled(applicationContext)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (listening) return START_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail("The microphone permission is off, so the wake word cannot listen.")
            return START_NOT_STICKY
        }

        val dir = WakeWordModel.installedDir(applicationContext)
        if (dir == null) {
            fail("The voice model is not installed yet. Turn the wake word on again to download it.")
            return START_NOT_STICKY
        }
        listen(dir.path)
        return START_STICKY
    }

    // ---------------------------------------------------------------------------------------------
    // The microphone
    // ---------------------------------------------------------------------------------------------

    private fun listen(modelPath: String) {
        if (listening) return
        try {
            val loaded = model ?: Model(modelPath).also { model = it }
            val speech = Recognizer(loaded, SAMPLE_RATE, WakePhrase.GRAMMAR)
            val device = openMicrophone()
                ?: throw IllegalStateException("microphone busy")

            recognizer = speech
            record = device
            if (!startRecording(device)) {
                throw IllegalStateException("recording refused")
            }

            listening = true
            handled = false
            WakeWord.publish(WakeWord.Phase.LISTENING)
            promoteToForeground("Listening for \"Hey Jarvis\" · tap to open")

            pump = Thread({ pump(device, speech) }, "jarvis-wake").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            listening = false
            fail("Could not open the microphone for the wake word. Close other voice apps and try again.")
        }
    }

    /** Voice-recognition source first (tuned for speech), plain microphone as the fallback. */
    private fun openMicrophone(): AudioRecord? {
        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (t: Throwable) {
            -1
        }
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer * 2, CHUNK_BYTES * 3)

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val candidate = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE.toInt(),
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (t: Throwable) {
                null
            } ?: continue

            if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
            try {
                candidate.release()
            } catch (t: Throwable) {
                // Nothing to release.
            }
        }
        return null
    }

    private fun startRecording(device: AudioRecord): Boolean = try {
        device.startRecording()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * The whole listening life of the service, on one thread.
     *
     * Wake mode and command mode are the same loop; only what it does with each read changes. That
     * matters, because switching modes without losing a buffer is exactly what makes
     * "hey jarvis open whatsapp" work.
     */
    private fun pump(device: AudioRecord, speech: Recognizer) {
        val chunk = ByteArray(CHUNK_BYTES)
        val ring = WakeCapture.Ring()
        val command = ByteArrayOutputStream()
        var tracker: WakeCapture.Tracker? = null

        while (listening) {
            val read = try {
                device.read(chunk, 0, chunk.size)
            } catch (t: Throwable) {
                -1
            }

            if (read <= 0) {
                if (!listening) break
                if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    main.post { fail("The microphone was taken by another app.") }
                    return
                }
                continue
            }

            val chunkMs = read * 1_000 / (SAMPLE_RATE.toInt() * 2)
            val loudness = try {
                Presence.rmsOf(chunk, read)
            } catch (t: Throwable) {
                0.0
            }

            val active = tracker
            if (active == null) {
                ring.append(chunk, read)

                val hypothesis = try {
                    if (speech.acceptWaveForm(chunk, read)) speech.result else speech.partialResult
                } catch (t: Throwable) {
                    null
                }

                if (WakePhrase.heard(hypothesis)) {
                    // Keep everything up to this instant: the tail of the phrase and whatever was
                    // said while the recogniser was still making up its mind.
                    command.reset()
                    val kept = ring.snapshot()
                    command.write(kept, 0, kept.size)
                    tracker = WakeCapture.Tracker()
                    main.post { onHeard() }
                }
            } else {
                command.write(chunk, 0, read)
                when (active.feed(loudness, chunkMs)) {
                    WakeCapture.Decision.FINISH -> {
                        val audio = command.toByteArray()
                        main.post { handOver(audio) }
                        return
                    }

                    WakeCapture.Decision.NOTHING -> {
                        main.post { handOver(null) }
                        return
                    }

                    WakeCapture.Decision.CAPTURING -> Unit
                }
            }
        }
    }

    private fun releaseMicrophone() {
        listening = false

        val device = record
        record = null
        try {
            device?.stop()
        } catch (t: Throwable) {
            // Already stopped.
        }
        try {
            device?.release()
        } catch (t: Throwable) {
            // Already released.
        }

        val thread = pump
        pump = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(600)
            } catch (t: Throwable) {
                // The thread is a daemon; it will exit on its own.
            }
        }

        try {
            recognizer?.close()
        } catch (t: Throwable) {
            // Already closed.
        }
        recognizer = null
    }

    // ---------------------------------------------------------------------------------------------
    // The turn
    // ---------------------------------------------------------------------------------------------

    /** Main thread. Updates the card before the sentence has even been transcribed. */
    private fun onHeard() {
        if (handled) return
        handled = true
        WakeWord.publish(WakeWord.Phase.HEARD)
        promoteToForeground("Heard you · listening for the rest")
    }

    /**
     * Main thread. [audio] is the whole sentence including the wake phrase, or null when nothing
     * followed it.
     */
    private fun handOver(audio: ByteArray?) {
        releaseMicrophone()

        if (audio != null && audio.isNotEmpty()) {
            WakeHandoff.deliver(audio)
        }

        // Android can refuse a background activity start. The notification is the fallback, and it
        // carries the same captured sentence while it is fresh.
        AssistantHandoff.open(applicationContext)

        WakeWord.publish(
            WakeWord.Phase.PAUSED,
            if (audio != null) "Heard you. Working on it." else "Heard you. Back to listening in a moment.",
        )
        promoteToForeground(
            if (audio != null) "Heard you · working on it" else "Heard you · tap to talk",
            AssistantHandoff.intent(applicationContext),
        )
        scheduleRearm()
    }

    private fun scheduleRearm() {
        val again = Runnable {
            handled = false
            rearm = null
            if (WakeWord.isEnabled(applicationContext)) {
                WakeWordModel.installedDir(applicationContext)?.let { listen(it.path) }
            }
        }
        rearm = again
        main.postDelayed(again, REARM_MS)
    }

    /** A failure is final for this run: say why, drop the foreground state, and stop. */
    private fun fail(reason: String) {
        releaseMicrophone()
        WakeWord.publish(WakeWord.Phase.FAILED, reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(text: String, target: Intent? = null) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            target ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "TURN OFF", stop)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Wake word", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shown while JARVIS listens for \"Hey Jarvis\"" },
            )
        }
    }

    override fun onDestroy() {
        rearm?.let { main.removeCallbacks(it) }
        rearm = null
        releaseMicrophone()
        try {
            model?.close()
        } catch (t: Throwable) {
            // Nothing to release.
        }
        model = null
        // A failure keeps its reason on screen; only a normal stop goes back to OFF.
        if (WakeWord.status.value.phase != WakeWord.Phase.FAILED) {
            WakeWord.publish(WakeWord.Phase.OFF)
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.assistant.wake.STOP"
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIFICATION_ID = 4417
        private const val SAMPLE_RATE = 16_000f

        /** One read: 100 ms of 16 kHz, 16-bit mono. */
        private const val CHUNK_BYTES = 3_200

        /** Long enough for the app to take the microphone and finish with it. */
        private const val REARM_MS = 10_000L
    }
}
