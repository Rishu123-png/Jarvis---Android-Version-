package com.jarvis.assistant.data

import android.content.Context
import java.net.URI

/**
 * The brain on the computer.
 *
 * JARVIS has always planned on the phone, with the operator's key. This is the other arrangement:
 * a model running on their own PC answers the planning, so the thinking happens on a machine with a
 * real GPU, nothing is sent to a cloud, and the phone only says the words and does the actions.
 *
 * **Why it is safe to add.** The phone will not talk plain HTTP to a machine on the network, and it
 * should not: anything on the wifi could pretend to be the core. So the address is allowed in
 * exactly two shapes:
 *
 *  * **https** — the core serves TLS, whoever it is;
 *  * **loopback** — the core is reached over USB with `adb reverse`, which means the bytes never
 *    touch the network at all.
 *
 * A plain `http://192.168.x.x` is refused, deliberately, with a sentence saying what to do instead.
 * That is the whole security model of this feature and it is one rule.
 *
 * **Why it is separate from the AI key.** Speech-to-text still uses the operator's key, or the on-
 * phone recogniser, exactly as before. Only planning moves. If the core is off, asleep, or the
 * cable is out, [com.jarvis.assistant.voice.IntentRouter] falls back to the key — one sentence said
 * twice, never a command that silently does nothing.
 *
 * [normaliseBase], [isLoopback], [isAllowed] and [endpoint] are pure, so the rule has a test.
 */
object CoreLink {

    /** Ollama's default when it is running on the machine the operator is sitting at. */
    const val DEFAULT_BASE = "http://127.0.0.1:11434/v1"

    /** Small models answer a planning prompt faster; the operator can change it. */
    const val DEFAULT_MODEL = "qwen2.5:7b"

    private const val PREFS = "jarvis_core"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE = "base"
    private const val KEY_MODEL = "model"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun base(context: Context): String =
        normaliseBase(prefs(context).getString(KEY_BASE, null) ?: DEFAULT_BASE)

    fun model(context: Context): String =
        (prefs(context).getString(KEY_MODEL, null) ?: "").ifBlank { DEFAULT_MODEL }

    /**
     * Stores the core settings. Refuses an address the app would not be allowed to reach, and says
     * why, so the operator is never left with a setting that quietly cannot work.
     */
    fun save(context: Context, enabled: Boolean, rawBase: String, rawModel: String): String? {
        val base = normaliseBase(rawBase)
        if (enabled && !isAllowed(base)) return refusalFor(base)

        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_BASE, base)
            .putString(KEY_MODEL, rawModel.trim().ifBlank { DEFAULT_MODEL })
            .apply()
        return null
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    /** One sentence the settings screen shows when an address is refused. */
    fun refusalFor(base: String): String =
        "JARVIS will not talk to $base: a plain http address on the network could be any machine " +
            "pretending to be yours. Run the core on this PC and connect over USB (adb reverse), " +
            "or give the core an https address."

    /**
     * The address, tidied into the shape the client expects: a scheme, no trailing slash, and a
     * `/v1` path because every OpenAI-compatible server puts its chat endpoint there.
     */
    fun normaliseBase(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_BASE

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        return withScheme.trimEnd('/').let { base ->
            if (base.endsWith("/v1")) base else "$base/v1"
        }
    }

    /** True when the host is this device itself — the `adb reverse` case, and the emulator. */
    fun isLoopback(base: String): Boolean {
        val host = hostOf(base) ?: return false
        return host == "127.0.0.1" ||
            host == "localhost" ||
            host == "::1" ||
            host == "[::1]" ||
            host == "10.0.2.2"
    }

    fun isSecure(base: String): Boolean = base.trim().lowercase().startsWith("https://")

    /**
     * The one rule. Secure, or local — never in between. Anything else is refused before it is
     * saved, so a misconfigured core fails here and not halfway through a spoken command.
     */
    fun isAllowed(base: String): Boolean {
        val normalised = normaliseBase(base)
        return isSecure(normalised) || isLoopback(normalised)
    }

    /** The chat endpoint, which is the same path on every OpenAI-compatible server. */
    fun endpoint(base: String): String = "${normaliseBase(base)}/chat/completions"

    /** A host for display, without the scheme or path; null when it cannot be parsed. */
    fun hostOf(base: String): String? = try {
        URI(normaliseBase(base)).host?.lowercase()
    } catch (t: Throwable) {
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
