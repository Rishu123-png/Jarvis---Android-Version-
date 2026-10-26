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
) {
    OPENAI("openai", "OpenAI", AiWire.OPENAI, "gpt-4o-mini", "sk-…"),
    ANTHROPIC("anthropic", "Anthropic", AiWire.ANTHROPIC, "claude-sonnet-4-5", "sk-ant-…"),
    GEMINI("gemini", "Google Gemini", AiWire.GEMINI, "gemini-2.0-flash", "AIza…"),
    GROQ("groq", "Groq", AiWire.OPENAI, "llama-3.3-70b-versatile", "gsk_…"),
    XAI("xai", "xAI Grok", AiWire.OPENAI, "grok-2-latest", "xai-…"),
    OPENROUTER("openrouter", "OpenRouter", AiWire.OPENAI, "openai/gpt-4o-mini", "sk-or-v1-…"),
    MISTRAL("mistral", "Mistral", AiWire.OPENAI, "mistral-small-latest", "any"),
    CUSTOM("custom", "Custom / self-hosted", AiWire.OPENAI, "", "any");

    /** Only the custom entry needs a base URL; the rest are hard-coded. */
    val needsBaseUrl: Boolean get() = this == CUSTOM

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
