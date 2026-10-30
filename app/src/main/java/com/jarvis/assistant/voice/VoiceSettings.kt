package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.automation.SpeechOut

/**
 * How JARVIS listens, chosen by the operator and stored locally.
 *
 * [SpeechRoute] is deliberately explicit rather than an "auto" mystery: the operator can see which
 * engine is armed in Settings and in the diagnostics panel, and can force the device recognizer when
 * they would rather not send audio to a provider at all.
 */
enum class SpeechRoute(val id: String, val label: String, val blurb: String) {
    AUTO("auto", "AUTO", "Whisper when a key is stored, otherwise the device recognizer"),
    WHISPER("whisper", "WHISPER", "Always send audio to your AI provider"),
    DEVICE("device", "DEVICE", "The phone's own recognizer, online"),
    OFFLINE("offline", "DEVICE OFFLINE", "Ask the phone to work without a network"),
    ;

    companion object {
        fun fromId(id: String?): SpeechRoute = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * Which voice JARVIS speaks with.
 *
 * Gender is a setting rather than something inferred from the phone's default, because the
 * default on most devices is a bright female voice and JARVIS is not written as one. [MALE] is the
 * default here; a phone with no male voice in its language still gets a lower pitch rather than
 * the wrong voice (see `SpeechOut`).
 */
enum class VoiceGender(val id: String, val label: String, val blurb: String) {
    MALE("male", "MALE", "A low, steady voice"),
    FEMALE("female", "FEMALE", "A brighter voice"),
    ;

    companion object {
        fun fromId(id: String?): VoiceGender = entries.firstOrNull { it.id == id } ?: MALE
    }
}

object VoiceSettings {

    private const val PREFS = "jarvis_voice_settings"
    private const val KEY_ROUTE = "speech_route"
    private const val KEY_GENDER = "voice_gender"

    fun route(context: Context): SpeechRoute = SpeechRoute.fromId(
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROUTE, null),
    )

    fun setRoute(context: Context, route: SpeechRoute) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROUTE, route.id)
            .apply()
    }

    /** The voice JARVIS speaks with. Male by default. */
    fun gender(context: Context): VoiceGender = VoiceGender.fromId(
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GENDER, null),
    )

    fun setGender(context: Context, gender: VoiceGender) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GENDER, gender.id)
            .apply()
        // The engine caches the voice it chose, so a change has to force it to look again.
        SpeechOut.forgetVoice()
    }

    /** True when the device recognizer should be told to prefer an offline model. */
    fun preferOffline(context: Context): Boolean = route(context) == SpeechRoute.OFFLINE

    /** The engine that will actually run for the next turn, as a label for the UI. */
    fun activeEngine(context: Context): String = when (route(context)) {
        SpeechRoute.OFFLINE -> "DEVICE · OFFLINE"
        SpeechRoute.DEVICE -> "DEVICE"
        SpeechRoute.WHISPER -> if (Whisper.ready(context)) "WHISPER" else "WHISPER · NO KEY"
        SpeechRoute.AUTO -> if (Whisper.ready(context)) "WHISPER" else "DEVICE"
    }
}
