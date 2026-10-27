package com.jarvis.assistant.ai

import android.content.Context

/**
 * Translation through the operator's own key.
 *
 * There is no bundled model and no JARVIS server: the sentence goes to the provider the operator
 * already pays for, and only the sentence — no context, no history. When no key is stored the answer
 * is a plain "add a key", not a silent failure.
 *
 * The prompt asks for the translation alone so the reply can be spoken directly without the model's
 * usual preamble.
 */
object Translator {

    /** Languages with a stable, spoken name. Anything else is passed through verbatim. */
    private val known = mapOf(
        "hindi" to "Hindi",
        "english" to "English",
        "hinglish" to "Hindi written in Latin script (Hinglish)",
        "urdu" to "Urdu",
        "punjabi" to "Punjabi",
        "bengali" to "Bengali",
        "tamil" to "Tamil",
        "telugu" to "Telugu",
        "marathi" to "Marathi",
        "gujarati" to "Gujarati",
        "kannada" to "Kannada",
        "malayalam" to "Malayalam",
        "spanish" to "Spanish",
        "french" to "French",
        "german" to "German",
        "japanese" to "Japanese",
        "chinese" to "Simplified Chinese",
        "arabic" to "Arabic",
        "russian" to "Russian",
        "portuguese" to "Portuguese",
    )

    data class Outcome(val text: String? = null, val error: String? = null)

    fun languageName(raw: String): String {
        val key = raw.trim().lowercase()
        return known[key] ?: raw.trim().ifBlank { "English" }
    }

    /** Returns the translation, or a spoken-friendly error. */
    suspend fun translate(context: Context, text: String, targetLanguage: String): Outcome {
        val body = text.trim()
        if (body.isEmpty()) return Outcome(error = "There is nothing to translate.")
        if (AiKeyStore.config(context) == null) {
            return Outcome(error = "Translation needs an AI key. Add one in Settings, then Intelligence.")
        }

        val target = languageName(targetLanguage)
        val system = """
            You are a translation engine inside a phone assistant.

            Translate the operator's message into $target. Reply with the translation only: no quotes,
            no romanisation, no explanation, no language label, no notes. If the message is already in
            $target, return it unchanged. Keep names, numbers and emoji exactly as they are.
        """.trimIndent()

        return when (val result = AiClient.chat(context, listOf(AiMessage.user(body)), system)) {
            is AiResult.Ok -> Outcome(text = result.text.trim().take(1_200))
            is AiResult.Error -> Outcome(error = result.message)
        }
    }
}
