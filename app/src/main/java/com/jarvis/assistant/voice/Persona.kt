package com.jarvis.assistant.voice

import com.jarvis.assistant.data.PhraseBook
import com.jarvis.assistant.data.PhraseIntent
import java.util.Locale

/**
 * Who JARVIS is, and how he talks about it.
 *
 * Two rules, enforced here so they cannot drift apart across the router, the AI prompt and the
 * terminal:
 *
 *  1. **He was built by Rishu Jaswar.** Asked "who made you", that is the whole answer. It is
 *     answered locally and never sent to a language model, because a model asked about itself will
 *     happily name its own provider — and a family member does not recite its ingredients.
 *  2. **He never talks about keys, providers or models.** Anything that slips through — a model
 *     volunteering "as an AI language model", a status line saying "Groq", a key-shaped string —
 *     is scrubbed before it is spoken or shown.
 *
 * [sanitize] is deliberately aggressive. A missing word is a small loss; telling the operator which
 * API key is in use is the thing they explicitly asked never to happen.
 */
object Persona {

    const val BUILDER = "Rishu Jaswar"

    /** Vocab that belongs to the plumbing, never to the conversation. */
    private val PLUMBING = listOf(
        "groq", "openai", "anthropic", "gemini", "google ai", "claude", "chatgpt", "gpt-4",
        "gpt-3", "llama", "mistral", "qwen", "deepseek", "api key", "apikey", "bearer token",
        "llm", "language model", "endpoint", "api endpoint", "provider key", "ai provider",
        "as an ai", "as a large language model", "my training data", "prompt", "token limit",
    )

    private val KEY_SHAPE = listOf(
        Regex("\\b(sk|gsk|AIza|sk-ant|xai)[-_A-Za-z0-9]{8,}\\b"),
        Regex("\\b[A-Za-z0-9_-]{32,}\\b"),
    )

    /** True for "who made you", "tumhe kisne banaya" and every phrasing in the table. */
    fun isIdentityQuestion(text: String): Boolean {
        val clean = PhraseBook.clean(text)
        if (clean.isBlank()) return false
        if (PhraseBook.match(clean)?.intent == PhraseIntent.IDENTITY) return true
        return clean.contains("who built") || clean.contains("who made") ||
            clean.contains("who created") || clean.contains("your creator") ||
            clean.contains("kisne banaya") || clean.contains("kisne bnaya")
    }

    /** The answer, in the operator's language. */
    fun answer(lang: Lang): String = Language.say(lang, "identity")

    /**
     * Removes provider names, key-shaped strings and "as an AI" disclaimers.
     *
     * Sentences are dropped whole when they only exist to name the machinery, so the result reads
     * like something a person would say rather than a redacted log.
     */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text

        var result = text
        for (shape in KEY_SHAPE) {
            result = result.replace(shape, "")
        }

        val sentences = result.split(Regex("(?<=[.!?])\\s+"))
        val kept = sentences.filter { sentence ->
            val lower = sentence.lowercase(Locale.US)
            PLUMBING.none { lower.contains(it) }
        }

        result = kept.joinToString(" ").trim()
        if (result.isBlank()) {
            return "I am JARVIS. Built by $BUILDER."
        }
        return result.replace(Regex("\\s+"), " ").trim()
    }
}
