package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.automation.SpeechOut

/**
 * How JARVIS listens, chosen by the operator and stored locally.
 *
 * [SpeechRoute] controls both speech recognition and whether voice text may reach JARVIS's cloud
 * planner. DEVICE/OFFLINE use the local command parser; AUTO/WHISPER may use the configured provider.
 * The Android system recognizer in DEVICE mode can still use its own network service.
 */
enum class SpeechRoute(val id: String, val label: String, val blurb: String) {
    AUTO(
        "auto",
        "AUTO",
        "Use provider speech when available; otherwise use the phone. A configured AI provider may receive the transcript.",
    ),
    WHISPER(
        "whisper",
        "WHISPER",
        "Use provider speech transcription and its AI planner; this route needs a provider with a supported speech endpoint.",
    ),
    DEVICE(
        "device",
        "DEVICE",
        "Use the phone recognizer and local command parser. Android may use its own network service.",
    ),
    OFFLINE(
        "offline",
        "DEVICE OFFLINE",
        "Require on-device speech recognition and local command parsing; unavailable if this phone has no offline model.",
    ),
    ;

    /** DEVICE and OFFLINE routes never send voice text or personal context to JARVIS's AI provider. */
    val allowsCloudPlanner: Boolean
        get() = this == AUTO || this == WHISPER

    /** Only these routes may send captured PCM to the configured transcription provider. */
    val allowsProviderTranscription: Boolean
        get() = this == AUTO || this == WHISPER

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

    /** True only when the platform exposes a dedicated on-device speech recognizer. */
    fun preferOffline(context: Context): Boolean = route(context) == SpeechRoute.OFFLINE

    /** The engine that will actually run for the next turn, as a label for the UI. */
    fun activeEngine(context: Context): String = when (route(context)) {
        SpeechRoute.OFFLINE -> if (LocalSpeech.offlineRecognitionAvailable(context)) {
            "DEVICE · OFFLINE · LOCAL PLANNER"
        } else {
            "OFFLINE MODEL UNAVAILABLE"
        }
        SpeechRoute.DEVICE -> "DEVICE · LOCAL PLANNER"
        SpeechRoute.WHISPER -> when {
            Whisper.ready(context) -> "WHISPER · CLOUD PLANNER"
            AiKeyStore.config(context) != null -> "WHISPER · ENDPOINT UNSUPPORTED"
            else -> "WHISPER · NO KEY"
        }
        SpeechRoute.AUTO -> when {
            Whisper.ready(context) -> "WHISPER · CLOUD PLANNER"
            AiKeyStore.config(context) != null -> "DEVICE · CLOUD PLANNER"
            else -> "DEVICE · LOCAL PLANNER"
        }
    }
}
