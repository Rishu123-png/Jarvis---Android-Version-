package com.jarvis.assistant.ai

/**
 * Supported AI back ends.
 *
 * Design goal: the operator pastes whichever key they already pay for. The provider is inferred
 * from the key's prefix, so there is no account, no JARVIS backend, and no service in the middle.
 * [CUSTOM] exists for any OpenAI-compatible endpoint (self-hosted, proxy, or a provider not
 * listed here).
 */
enum class AiWire {
    /** POST /chat/completions with a bearer token. Used by most commercial APIs. */
    OPENAI,

    /** POST /messages with x-api-key. */
    ANTHROPIC,

    /** POST /models/{model}:generateContent?key=… */
    GEMINI,
}

enum class AiProvider(
    val id: String,
    val label: String,
    val wire: AiWire,
    val defaultModel: String,
    val keyHint: String,
    /**
     * Fallbacks tried in order when a model is rejected with 404 ("no such model").
     *
     * Providers retire model ids without warning — a hardcoded id is a build that breaks months
     * later with no code change. So the client walks this list, uses the first name the account can
     * actually reach, and remembers it. [listModels] is the authoritative fix; this is the safety
     * net that keeps a routine working when nobody is looking.
     */
    val fallbackModels: List<String> = emptyList(),
) {
    OPENAI(
        "openai", "OpenAI", AiWire.OPENAI, "gpt-4o-mini", "sk-…",
        fallbackModels = listOf("gpt-4.1-mini", "gpt-4o", "gpt-4.1", "o4-mini"),
    ),
    ANTHROPIC(
        "anthropic", "Anthropic", AiWire.ANTHROPIC, "claude-sonnet-4-5", "sk-ant-…",
        fallbackModels = listOf("claude-3-5-sonnet-latest", "claude-3-7-sonnet-latest", "claude-3-5-haiku-latest"),
    ),
    GEMINI(
        "gemini", "Google Gemini", AiWire.GEMINI, "gemini-2.0-flash", "AIza…",
        fallbackModels = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-1.5-flash"),
    ),
    GROQ(
        "groq", "Groq", AiWire.OPENAI, "llama-3.1-8b-instant", "gsk_…",
        fallbackModels = listOf(
            "llama-3.3-70b-versatile",
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "qwen/qwen3-32b",
            "llama3-8b-8192",
            "mixtral-8x7b-32768",
        ),
    ),
    XAI(
        "xai", "xAI Grok", AiWire.OPENAI, "grok-2-latest", "xai-…",
        fallbackModels = listOf("grok-beta", "grok-3", "grok-4"),
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", AiWire.OPENAI, "openai/gpt-4o-mini", "sk-or-v1-…",
        fallbackModels = listOf("openai/gpt-4.1-mini", "anthropic/claude-3.5-sonnet", "google/gemini-2.0-flash-001"),
    ),
    MISTRAL(
        "mistral", "Mistral", AiWire.OPENAI, "mistral-small-latest", "any",
        fallbackModels = listOf("mistral-large-latest", "open-mistral-7b", "open-mixtral-8x7b"),
    ),
    CUSTOM("custom", "Custom / self-hosted", AiWire.OPENAI, "", "any");

    /** Only the custom entry needs a base URL; the rest are hard-coded. */
    val needsBaseUrl: Boolean get() = this == CUSTOM

    /** The configured model first, then the fallbacks, with no duplicates. */
    fun candidates(configured: String): List<String> =
        (listOf(configured.trim()) + fallbackModels)
            .filter { it.isNotBlank() }
            .distinct()

    companion object {

        fun fromId(id: String?): AiProvider =
            AiProvider.entries.firstOrNull { it.id == id } ?: OPENAI

        /**
         * Infers the provider from the shape of the key. Order matters: the more specific
         * prefixes are tested before the generic `sk-` case.
         */
        fun detect(rawKey: String): AiProvider {
            val key = rawKey.trim()
            return when {
                key.isEmpty() -> OPENAI
                key.startsWith("sk-or-v1-") -> OPENROUTER
                key.startsWith("sk-ant-") -> ANTHROPIC
                key.startsWith("gsk_") -> GROQ
                key.startsWith("xai-") -> XAI
                key.startsWith("AIza") -> GEMINI
                key.startsWith("sk-proj-") -> OPENAI
                key.startsWith("sk-svcacct-") -> OPENAI
                key.startsWith("sk-") -> OPENAI
                else -> CUSTOM
            }
        }
    }
}
