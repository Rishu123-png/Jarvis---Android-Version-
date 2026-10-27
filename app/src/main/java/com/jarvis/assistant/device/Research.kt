package com.jarvis.assistant.device

import com.jarvis.assistant.data.Summarise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looking things up without a key, an account or a bill.
 *
 * The internet is the biggest library that has ever existed and the only part of it this needs is
 * the part that is public, free and documented: the Wikipedia REST API and a free dictionary. No
 * key, no sign-up, no quota to think about, nothing to pay for. That was the whole constraint, and
 * it is met — the two endpoints below work from a fresh phone with a SIM card and nothing else.
 *
 * What it deliberately cannot do: log in anywhere, follow a link it was not given, run a script from
 * a page, or read a site that forbids it. It fetches one JSON document, reads two fields out of it,
 * and stops. That is why it can be allowed to run at all.
 *
 * The parsing is separated from the fetching so the whole thing is testable in CI with a canned
 * response — see `ResearchTest`. Users of this file should go through `IntentRouter`, which asks
 * permission first via [com.jarvis.assistant.data.ResearchDesk].
 */
object Research {

    /** One answer, ready to be shown and saved. */
    data class Finding(
        val topic: String,
        val summary: String,
        val source: String,
        val url: String,
    )

    sealed class Outcome {
        data class Found(val finding: Finding) : Outcome()
        data class Missing(val query: String) : Outcome()
        data class Unreachable(val reason: String) : Outcome()
    }

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_BODY_CHARS = 400_000

    /**
     * Wikipedia asks automated clients to identify themselves. Being honest here is also what keeps
     * the requests allowed rather than throttled.
     */
    private const val AGENT =
        "JARVIS-Android/1.0 (personal assistant; +https://github.com/Rishu123-png/Jarvis---Android-Version-)"

    // ---------------------------------------------------------------------------------------------
    // URLs (pure, and covered by tests)
    // ---------------------------------------------------------------------------------------------

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun host(wiki: String): String = if (wiki.lowercase() == "hi") "hi.wikipedia.org" else "en.wikipedia.org"

    fun searchUrl(wiki: String, query: String): String =
        "https://${host(wiki)}/w/api.php?action=query&list=search&srlimit=1&format=json" +
            "&srsearch=${enc(query)}"

    fun summaryUrl(wiki: String, title: String): String =
        "https://${host(wiki)}/api/rest_v1/page/summary/${enc(title)}"

    fun dictionaryUrl(word: String): String =
        "https://api.dictionaryapi.dev/api/v2/entries/en/${enc(word)}"

    // ---------------------------------------------------------------------------------------------
    // Parsing (pure)
    // ---------------------------------------------------------------------------------------------

    /** The best title Wikipedia offers for a search, or null when nothing matched. */
    fun parseSearch(body: String): String? = try {
        val query = JSONObject(body).optJSONObject("query")
        val results = query?.optJSONArray("search") ?: JSONArray()
        val title = results.optJSONObject(0)?.optString("title").orEmpty().trim()
        title.ifBlank { null }
    } catch (t: Throwable) {
        null
    }

    /** A page summary from the REST API. Null when the page does not exist or carries no text. */
    fun parseSummary(body: String, wiki: String = "en"): Finding? = try {
        val json = JSONObject(body)
        val title = json.optString("title").trim()
        val extract = Summarise.tidy(json.optString("extract"))
        val page = json.optJSONObject("content_urls")
            ?.optJSONObject("desktop")
            ?.optString("page")
            .orEmpty()
        if (title.isBlank() || extract.isBlank()) {
            null
        } else {
            Finding(
                topic = title,
                summary = Summarise.best(title, extract, maxSentences = 2, maxChars = 300),
                source = "WIKIPEDIA",
                url = page.ifBlank { "https://${host(wiki)}/wiki/${enc(title)}" },
            )
        }
    } catch (t: Throwable) {
        null
    }

    /** A dictionary definition, folded into the same shape as a summary. */
    fun parseDefinition(body: String): Finding? = try {
        val entries = JSONArray(body)
        val entry = entries.optJSONObject(0)
        val word = entry?.optString("word").orEmpty().trim()
        val meaning = entry?.optJSONArray("meanings")?.optJSONObject(0)
        val definition = meaning?.optJSONArray("definitions")?.optJSONObject(0)
            ?.optString("definition")
            .orEmpty()
            .trim()
        val partOfSpeech = meaning?.optString("partOfSpeech").orEmpty().trim()
        if (word.isBlank() || definition.isBlank()) {
            null
        } else {
            Finding(
                topic = word,
                summary = Summarise.trim(
                    if (partOfSpeech.isBlank()) definition else "($partOfSpeech) $definition",
                    280,
                ),
                source = "DICTIONARY",
                url = dictionaryUrl(word),
            )
        }
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------------------------------------
    // The lookup
    // ---------------------------------------------------------------------------------------------

    /**
     * One question in, one answer out.
     *
     * In order: the literal title (most questions are already the name of a page), then a search to
     * fix the spelling or find the page the person meant, then — for a single word — a dictionary.
     * A Hindi request tries the Hindi encyclopedia and falls back to English rather than answering
     * nothing.
     */
    suspend fun look(topic: String, wiki: String = "en"): Outcome = withContext(Dispatchers.IO) {
        val query = topic.trim().trim('.', ',', '?', '!', '"', '\'').take(120)
        if (query.isBlank()) return@withContext Outcome.Missing(query)

        val hosts = if (host(wiki) == "hi.wikipedia.org") listOf("hi", "en") else listOf("en")
        var reachedAnything = false

        for (code in hosts) {
            val direct = request(summaryUrl(code, query))
            if (direct != null) {
                reachedAnything = true
                parseSummary(direct, code)?.let { return@withContext Outcome.Found(it) }
            }

            val search = request(searchUrl(code, query))
            if (search != null) {
                reachedAnything = true
                val title = parseSearch(search)
                if (title != null && !title.equals(query, ignoreCase = true)) {
                    val page = request(summaryUrl(code, title))
                    if (page != null) {
                        parseSummary(page, code)?.let { return@withContext Outcome.Found(it) }
                    }
                }
            }
        }

        // A single word is often a definition question rather than an encyclopedia one.
        if (query.matches(Regex("[\\p{L}'-]{3,30}"))) {
            val body = request(dictionaryUrl(query))
            if (body != null) {
                reachedAnything = true
                parseDefinition(body)?.let { return@withContext Outcome.Found(it) }
            }
        }

        if (reachedAnything) {
            Outcome.Missing(query)
        } else {
            Outcome.Unreachable("no connection")
        }
    }

    /** The response body, or null when the request failed or was not a success. */
    private fun request(url: String): String? {
        var live: HttpURLConnection? = null
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", AGENT)
            }
            live = connection
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.length > MAX_BODY_CHARS) text.take(MAX_BODY_CHARS) else text
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                live?.disconnect()
            } catch (t: Throwable) {
                // Already released.
            }
        }
    }
}
