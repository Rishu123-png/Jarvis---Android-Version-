package com.jarvis.assistant.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Reading a page the operator pointed at.
 *
 * Everything else in the app that touches the network talks to one known API (Wikipedia, a
 * dictionary, the operator's own model). This one is different: it reads an arbitrary address, so
 * it is written defensively and it refuses more than it accepts.
 *
 *  * **https only.** Plain http is not fetched, because a page that cannot be authenticated is not
 *    worth a plaintext round trip on someone's phone.
 *  * **No local addresses.** Loopback, private ranges, `.local` and link-local hosts are refused, so
 *    a sentence cannot be used to poke at a router or a laptop on the same wifi.
 *  * **Bounded in every direction** — connect timeout, read timeout, a hard byte cap, and a cap on
 *    how much text survives extraction.
 *  * **No JavaScript.** What comes back is the server's HTML; a page that draws its content in the
 *    browser will read as empty, and he says so instead of inventing a summary.
 *
 * [urlIn], [extractText], [titleOf] and [isAllowed] are pure, so the dangerous half has a test.
 */
object WebRead {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 512 * 1024
    private const val MAX_TEXT_CHARS = 6_000

    /** A page, already reduced to words. */
    data class Page(
        val url: String,
        val title: String,
        val text: String,
    ) {
        val host: String
            get() = try {
                URL(url).host.removePrefix("www.")
            } catch (t: Throwable) {
                url
            }

        /** True when there was a page but nothing readable in it — the JavaScript case. */
        val isEmpty: Boolean get() = text.length < 80
    }

    sealed interface Result {
        data class Ok(val page: Page) : Result
        data class Refused(val reason: String) : Result
    }

    // ---------------------------------------------------------------------------------------------
    // Finding a link in a sentence
    // ---------------------------------------------------------------------------------------------

    private val URL_SHAPE = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    /** The first address in what was said, with trailing punctuation trimmed. */
    fun urlIn(spoken: String): String? {
        val match = URL_SHAPE.find(spoken)?.value ?: return null
        return match.trimEnd('.', ',', ';', ')', ']', '!', '?', '\u0964')
    }

    /**
     * True when the address is safe to fetch.
     *
     * Deliberately a whitelist of *schemes* and a blacklist of *hosts*: the failure mode this
     * prevents is a spoken sentence being turned into a probe of the local network.
     */
    fun isAllowed(url: String): Boolean {
        val parsed = try {
            URL(url)
        } catch (t: Throwable) {
            return false
        }
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        val host = parsed.host.lowercase(Locale.US)
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host.endsWith(".internal") || host.endsWith(".home.arpa")) return false

        // IPv4 literals: loopback, private, link-local, and the "this network" block.
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host)
        if (v4 != null) {
            val a = v4.groupValues[1].toIntOrNull() ?: return false
            val b = v4.groupValues[2].toIntOrNull() ?: return false
            if (a == 127 || a == 10 || a == 0) return false
            if (a == 192 && b == 168) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
        }
        // Any IPv6 literal, including [::1].
        if (host.startsWith("[")) return false
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    suspend fun read(url: String): Result = withContext(Dispatchers.IO) {
        val clean = url.trim()
        if (!isAllowed(clean)) {
            return@withContext Result.Refused(
                "I only read https pages, and I will not open a local address.",
            )
        }

        var live: HttpURLConnection? = null
        try {
            val connection = (URL(clean).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain")
                setRequestProperty(
                    "User-Agent",
                    "JARVIS-Android/1.0 (personal assistant; reads one page on request)",
                )
            }
            live = connection
            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext Result.Refused("That page answered with HTTP $code.")
            }
            val type = connection.contentType.orEmpty().lowercase(Locale.US)
            if (type.isNotBlank() && !type.contains("html") && !type.contains("text/plain")) {
                return@withContext Result.Refused("That link is not a page I can read.")
            }

            val bytes = try {
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (total < MAX_BYTES) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        total += read
                    }
                    out.toByteArray()
                }
            } catch (t: Throwable) {
                ByteArray(0)
            }

            if (bytes.isEmpty()) {
                return@withContext Result.Refused("That page sent nothing back.")
            }
            val html = String(bytes, Charsets.UTF_8)
            Result.Ok(
                Page(
                    url = clean,
                    title = titleOf(html).ifBlank { URL(clean).host },
                    text = extractText(html),
                ),
            )
        } catch (t: Throwable) {
            Result.Refused("I could not reach that page (${t.javaClass.simpleName}).")
        } finally {
            try {
                live?.disconnect()
            } catch (t: Throwable) {
                // Already gone.
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Extraction (pure)
    // ---------------------------------------------------------------------------------------------

    /** The `<title>`, or the first `<h1>`, cleaned up. */
    fun titleOf(html: String): String {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
            ?: Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)
        return collapse(decode(stripTags(title.orEmpty()))).take(120)
    }

    /**
     * The readable words of a page.
     *
     * Ordered: remove what is never prose (script, style, nav, footer, forms), keep paragraph and
     * heading boundaries as newlines so the summary has somewhere to breathe, drop the remaining
     * tags, decode entities, then collapse the whitespace the tags left behind.
     */
    fun extractText(html: String): String {
        if (html.isBlank()) return ""
        var text = html

        for (tag in listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "form", "iframe", "aside")) {
            text = text.replace(
                Regex("<$tag\\b[^>]*>.*?</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                " ",
            )
        }
        // Comments can hold script fragments and are never prose.
        text = text.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")

        for (tag in listOf("p", "div", "br", "li", "h1", "h2", "h3", "h4", "tr", "section", "article")) {
            text = text.replace(Regex("</?$tag\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        }
        text = stripTags(text)

        val lines = decode(text)
            .split('\n')
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            // A line of two or three words is a menu item or a button, not a sentence.
            .filter { it.length >= 24 }
            .distinct()

        return lines.joinToString("\n").trim().take(MAX_TEXT_CHARS)
    }

    private fun stripTags(value: String): String =
        value.replace(Regex("<[^>]{0,400}>"), " ")

    private fun collapse(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    /** The handful of entities that actually turn up in prose. */
    fun decode(value: String): String {
        if (!value.contains('&')) return value
        var out = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&mdash;", "\u2014")
            .replace("&ndash;", "\u2013")
            .replace("&hellip;", "\u2026")
        // Numeric entities, decimal and hex.
        out = Regex("&#(\\d{2,6});").replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            if (code in 32..0x2FFF) String(Character.toChars(code)) else " "
        }
        out = Regex("&#x([0-9a-fA-F]{2,5});").replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
            if (code in 32..0x2FFF) String(Character.toChars(code)) else " "
        }
        return out
    }

    /** The first [sentences] sentences, for when there is no model to summarise with. */
    fun firstSentences(text: String, sentences: Int = 2, limit: Int = 320): String {
        if (text.isBlank()) return ""
        val joined = text.replace('\n', ' ')
        val parts = Regex("[^.!?\u0964]+[.!?\u0964]?").findAll(joined)
            .map { it.value.trim() }
            .filter { it.length > 20 }
            .take(sentences)
            .joinToString(" ")
        return (if (parts.isBlank()) joined else parts).take(limit).trim()
    }
}
