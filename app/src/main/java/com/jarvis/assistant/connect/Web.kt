package com.jarvis.assistant.connect

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The smallest honest HTTP helper for the connector layer.
 *
 * Explicit timeouts, no redirects followed into other hosts, a body cap, and provider error objects
 * translated into one sentence plus an optional hint. Callers run this on IO; nothing here touches
 * the main thread.
 */
internal object Web {

    class WebFailure(override val message: String, val hint: String? = null) : Exception(message)

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_BODY_CHARS = 6_000

    fun get(url: String, bearer: String? = null): JSONObject = request("GET", url, null, bearer)

    fun postForm(url: String, form: Map<String, String>): JSONObject = request("POST", url, form, null)

    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(
        method: String,
        url: String,
        form: Map<String, String>?,
        bearer: String?,
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (form != null) {
                val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
                connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = try {
                connection.responseCode
            } catch (t: Throwable) {
                throw WebFailure("Could not reach the provider.", t.message)
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = try {
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (t: Throwable) {
                ""
            }.take(MAX_BODY_CHARS)

            if (code !in 200..299) throw explain(code, text)

            return try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw WebFailure("The provider replied with something that is not JSON.", "HTTP $code")
            }
        } finally {
            try {
                connection.disconnect()
            } catch (t: Throwable) {
                // Already released.
            }
        }
    }

    private fun explain(code: Int, body: String): WebFailure {
        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            null
        }
        val errorObject = json?.optJSONObject("error")
        val message = sequenceOf(
            errorObject?.optString("message"),
            json?.optString("error_description"),
            json?.optString("error"),
        ).firstOrNull { !it.isNullOrBlank() } ?: "The provider refused the request (HTTP $code)."

        val hint = when {
            code == 400 && message.contains("code", ignoreCase = true) ->
                "An authorization code is single-use and expires within minutes. Start the login again and paste a fresh one."

            code == 400 && message.contains("redirect_uri", ignoreCase = true) ->
                "The redirect URI differs from the one registered with the provider. They must match exactly."

            code == 401 || code == 403 ->
                "The app credentials or the granted scopes were rejected. Check the app id and secret."

            code == 429 ->
                "Rate limit reached. Wait a few minutes, then retry."

            else -> null
        }
        return WebFailure(message, hint)
    }
}
