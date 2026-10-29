package com.jarvis.assistant.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.voice.AssistantHandoff
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Listens for "Hey Jarvis" while the operator has turned it on.
 *
 * This is the one place in the app that holds the microphone for long. It is a foreground service
 * with the microphone type, so Android shows its indicator and the notification says what it is
 * doing. It does not run a thread of its own: Vosk's SpeechService owns the audio loop.
 *
 * On a hit it does three things in order: releases the microphone (the app's own listening needs
 * it), opens JARVIS listening, and re-arms once after [REARM_MS]. If the re-arm cannot get the
 * microphone, the service says so in its notification instead of pretending to listen.
 */
class WakeWordService : Service() {

    private var model: Model? = null
    private var speech: SpeechService? = null
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
        if (speech != null) return START_STICKY

        val dir = WakeWordModel.installedDir(applicationContext)
        if (dir == null) {
            fail("The voice model is not installed yet. Turn the wake word on again to download it.")
            return START_NOT_STICKY
        }
        listen(dir.path)
        return START_STICKY
    }

    private fun listen(modelPath: String) {
        try {
            val loaded = model ?: Model(modelPath).also { model = it }
            val recognizer = Recognizer(loaded, SAMPLE_RATE, WakePhrase.GRAMMAR)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speech = service
            // Vosk reports a microphone that would not open as false, not as an exception.
            if (!service.startListening(listener)) {
                throw IllegalStateException("microphone busy")
            }
            WakeWord.publish(WakeWord.Phase.LISTENING)
            promoteToForeground("Listening for \"Hey Jarvis\" · tap to open")
        } catch (t: Throwable) {
            speech = null
            fail("Could not open the microphone for the wake word. Close other voice apps and try again.")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis)
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) = check(hypothesis)

        override fun onError(exception: Exception?) {
            main.post {
                releaseMicrophone()
                fail("The wake word stopped: the microphone was taken by another app.")
            }
        }

        override fun onTimeout() {
            // Vosk reports silence this way. It is normal; keep listening.
        }
    }

    /** Runs on Vosk's thread. Only the cheap check happens here; the handover goes to main. */
    private fun check(hypothesis: String?) {
        if (!WakePhrase.heard(hypothesis)) return
        main.post { onHeard() }
    }

    private fun onHeard() {
        if (handled) return
        handled = true
        WakeWord.publish(WakeWord.Phase.HEARD)
        releaseMicrophone()
        // Android can refuse a background activity start. The notification below is the fallback:
        // one tap opens the same listening surface.
        AssistantHandoff.open(applicationContext)
        WakeWord.publish(WakeWord.Phase.PAUSED, "Heard you. Back to listening in a moment.")
        promoteToForeground("Heard you · tap to talk", AssistantHandoff.intent(applicationContext))

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

    private fun releaseMicrophone() {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (t: Throwable) {
            // Already released.
        }
        speech = null
    }

    /** A failure is final for this run: say why, drop the foreground state, and stop. */
    private fun fail(reason: String) {
        WakeWord.publish(WakeWord.Phase.FAILED, reason)
        releaseMicrophone()
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

        /** Long enough for the app's own voice session to take the microphone and finish with it. */
        private const val REARM_MS = 10_000L
    }
}
