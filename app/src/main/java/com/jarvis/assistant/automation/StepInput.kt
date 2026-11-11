package com.jarvis.assistant.automation

import java.net.URI
import java.util.Locale

/**
 * The two decisions a step makes before it touches the phone: what address is this, and which
 * gesture was asked for.
 *
 * Both used to live inside [StepExecutor] as private functions, which meant the riskiest file in the
 * app held logic that could not be tested without a device. Neither decision needs an Android API —
 * a URL is a string and a gesture is a word — so they live here, as pure functions, with a JVM suite
 * of their own. [StepExecutor] keeps only the part that really needs the platform: firing the intent
 * or calling the service.
 *
 * The rules themselves are unchanged. This is a move, not a redesign, so that the behaviour the
 * operator already relies on keeps its shape while gaining a test.
 */
object StepInput {

    /**
     * A web address, or null when the text is not one.
     *
     * A bare host is given `https://`, because a routine author types `example.com/hook?x=1` far more
     * often than they type the scheme. Only `http` and `https` survive: `file://` and `intent://`
     * parse perfectly well and must never be reachable from a saved routine, which is the whole
     * reason this is a whitelist of schemes and not a blacklist of the bad ones.
     */
    fun normaliseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // A leading `word:` is read as a scheme, but `example.com:8080` is a host and a port, so a
        // colon followed by digits is left alone.
        val declared = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase(Locale.US)
        if (declared != null && declared != "http" && declared != "https") return null

        val withScheme = if (declared == null) "https://$trimmed" else trimmed

        val uri = try {
            URI(withScheme)
        } catch (t: Throwable) {
            return null
        }
        if (uri.scheme?.lowercase(Locale.US) !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    /** A scheme name (`file`, `intent`, `javascript`) but not a host read as one (`host:8080`). */
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(?![0-9])")

    /**
     * The canonical name of a navigation gesture, or null when the word is none of them.
     *
     * The operator says "go home", "home", "navigate back" and "shade" and means six different
     * things. Resolving that vocabulary to one name each keeps the mapping in a place a test can
     * read, and keeps [StepExecutor] from holding a `when` over Android constants that nothing
     * without a device can execute.
     */
    fun gestureKey(raw: String): String? = when (raw.trim().lowercase(Locale.US)) {
        "back", "navigate back" -> "back"
        "home", "go home" -> "home"
        "recents", "recent apps" -> "recents"
        "notifications", "shade", "notification shade" -> "notifications"
        "quick", "quick settings" -> "quick"
        "lock", "lock screen" -> "lock"
        else -> null
    }

    /** The words a rejection offers back, so a typo is answered rather than ignored. */
    const val GESTURES: String = "back, home, recents, notifications, quick, lock"
}
