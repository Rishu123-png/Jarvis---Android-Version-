package com.jarvis.assistant.wake

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The on/off switch for "Hey Jarvis", and what the settings screen shows about it.
 *
 * Nothing here polls. The service pushes its state into [status]; the screen collects the flow.
 */
object WakeWord {

    private const val PREFS = "jarvis_wake"
    private const val KEY_ENABLED = "enabled"

    enum class Phase { OFF, NEEDS_MODEL, DOWNLOADING, LISTENING, HEARD, PAUSED, FAILED }

    data class Status(val phase: Phase, val detail: String = "", val percent: Int = 0)

    private val _status = MutableStateFlow(Status(Phase.OFF))
    val status: StateFlow<Status> = _status.asStateFlow()

    internal fun publish(phase: Phase, detail: String = "", percent: Int = 0) {
        _status.value = Status(phase, detail, percent)
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    private fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * Turns listening on. The caller must already hold RECORD_AUDIO and have the model installed;
     * the service checks both again and reports anything missing rather than failing silently.
     */
    fun enable(context: Context) {
        setEnabled(context, true)
        start(context)
    }

    fun disable(context: Context) {
        setEnabled(context, false)
        context.stopService(Intent(context, WakeWordService::class.java))
        publish(Phase.OFF)
    }

    /**
     * Starts the foreground service if the operator turned it on. Called when the app opens, which is
     * a foreground moment, so Android allows the microphone service to start. A phone that was
     * rebooted therefore resumes listening the first time JARVIS is opened, not before.
     */
    fun resumeIfEnabled(context: Context) {
        if (isEnabled(context)) start(context)
    }

    private fun start(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, WakeWordService::class.java),
            )
        } catch (t: Throwable) {
            publish(Phase.FAILED, "Android would not start listening. Open JARVIS and try again.")
        }
    }
}
