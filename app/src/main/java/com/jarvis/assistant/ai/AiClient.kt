package com.jarvis.assistant.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.net.UnknownHostException

data class AiMessage(val role: String, val content: String) {
    companion object {
        fun user(text: String) = AiMessage("user", text)
        fun assistant(text: String) = AiMessage("assistant", text)
    }
}

sealed interface AiResult {
    data class Ok(
        val text: String,
        val providerLabel: String,
        val model: String,
    ) : AiResult

    data class Error(
        val message: String,
        val hint: String? = null,
    ) : AiResult
}

/**
 * Minimal, dependency-free client for the three wire formats JARVIS supports.
 *
 * Stability rules that matter more than features here:
 *  * every call runs on [Dispatchers.IO] — never the main thread;
 *  * connect and read timeouts are explicit, so a stalled provider cannot hang the UI;
 *  * exactly one request can be in flight per call site, enforced by the screen;
 *  * every failure is mapped to a sentence the operator can act on.
 *
 * Deliberately not streaming: a partial stream is harder to cancel cleanly, and this client has to
 * be boring.
 */
object AiClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_REPLY_TOKENS = 420
    private const val MAX_REPLY_CHARS = 4_000
    private const val MAX_ERROR_CHARS = 2_000
    private const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"

    private class Request(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    suspend fun chat(
        context: Context,
        messages: List<AiMessage>,
        system: String,
    ): AiResult = withContext(Dispatchers.IO) {
        val config = AiKeyStore.config(context)
            ?: return@withContext AiResult.Error(
                "No API key configured.",
                "Add one in Settings → INTELLIGENCE.",
            )
        val apiKey = AiKeyStore.apiKey(context)
            ?: return@withContext AiResult.Error(
                "The stored key could not be decrypted.",
                "Enter it again in Settings → INTELLIGENCE.",
            )
        val model = config.resolvedModel.trim()
        if (model.isEmpty()) {
            return@withContext AiResult.Error(
                "No model selected.",
                "Enter a model name in Settings → INTELLIGENCE.",
            )
        }
        if (messages.isEmpty()) {
            return@withContext AiResult.Error("Nothing to send.")
        }

        val request = try {
            buildRequest(config, apiKey, model, messages, system)
        } catch (t: Throwable) {
            return@withContext AiResult.Error("Could not build the request.", t.message)
        }

        val (code, body) = try {
            post(request)
        } catch (e: SocketTimeoutException) {
            return@withContext AiResult.Error(
                "The provider did not answer in time.",
                "Check your connection, or try a smaller model.",
            )
        } catch (e: UnknownHostException) {
            return@withContext AiResult.Error(
                "No network route.",
                "Check your connection and try again.",
            )
        } catch (e: IOException) {
            return@withContext AiResult.Error("Network error.", e.message)
        } catch (t: Throwable) {
            return@withContext AiResult.Error("Request failed.", t.message)
        }

        if (code !in 200..299) {
            return@withContext errorFor(code, body, config.provider)
        }

        val text = extractText(config.provider.wire, body)
            ?: return@withContext AiResult.Error(
                "The provider sent a reply JARVIS could not read.",
                "HTTP $code · check the model name.",
            )

        AiResult.Ok(
            text = text.take(MAX_REPLY_CHARS),
            providerLabel = config.provider.label,
            model = model,
        )
    }

    /** Round-trips a fixed prompt so the operator can prove the key works. */
    suspend fun selfTest(context: Context): AiResult = chat(
        context = context,
        messages = listOf(AiMessage.user("Reply with exactly one word: ONLINE")),
        system = "You are a connectivity probe. Reply with one word only.",
    )

    // -----------------------------------------------------------------------------------------
    // Request building
    // -----------------------------------------------------------------------------------------

    private fun baseUrl(config: AiKeyStore.Config): String = when (config.provider) {
        AiProvider.OPENAI -> "https://api.openai.com/v1"
        AiProvider.GROQ -> "https://api.groq.com/openai/v1"
        AiProvider.XAI -> "https://api.x.ai/v1"
        AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
        AiProvider.MISTRAL -> "https://api.mistral.ai/v1"
        AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        AiProvider.CUSTOM ->
            config.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_OPENAI_BASE }
    }

    private fun buildRequest(
        config: AiKeyStore.Config,
        apiKey: String,
        model: String,
        messages: List<AiMessage>,
        system: String,
    ): Request = when (config.provider.wire) {
        AiWire.OPENAI -> {
            val base = baseUrl(config)
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val array = JSONArray()
            if (system.isNotBlank()) {
                array.put(JSONObject().put("role", "system").put("content", system))
            }
            messages.forEach { message ->
                array.put(JSONObject().put("role", message.role).put("content", message.content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("messages", array)
                .put("max_tokens", MAX_REPLY_TOKENS)
                .put("temperature", 0.4)
                .toString()
            Request(url, mapOf("Authorization" to "Bearer $apiKey"), body)
        }

        AiWire.ANTHROPIC -> {
            val array = JSONArray()
            messages.forEach { message ->
                array.put(JSONObject().put("role", message.role).put("content", message.content))
            }
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", MAX_REPLY_TOKENS)
                .put("system", system)
                .put("temperature", 0.4)
                .put("messages", array)
                .toString()
            Request(
                url = "${baseUrl(config)}/messages",
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                ),
                body = body,
            )
        }

        AiWire.GEMINI -> {
            val array = JSONArray()
            messages.forEach { message ->
                array.put(
                    JSONObject()
                        .put("role", if (message.role == "assistant") "model" else "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", message.content)),
                        )
                )
            }
            val root = JSONObject()
                .put("contents", array)
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("maxOutputTokens", MAX_REPLY_TOKENS)
                        .put("temperature", 0.4),
                )
            if (system.isNotBlank()) {
                root.put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", system)),
                    ),
                )
            }
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            Request(
                url = "${baseUrl(config)}/models/$model:generateContent?key=$encodedKey",
                headers = emptyMap(),
                body = root.toString(),
            )
        }
    }

    private fun post(request: Request): Pair<Int, String> {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.use { stream ->
                stream.write(request.body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text.take(MAX_ERROR_CHARS + MAX_REPLY_CHARS)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(wire: AiWire, body: String): String? = runCatching {
        when (wire) {
            AiWire.OPENAI -> JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")

            AiWire.ANTHROPIC -> JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .optString("text")

            AiWire.GEMINI -> JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text")
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun errorFor(code: Int, body: String, provider: AiProvider): AiResult.Error {
        val detail = providerMessage(body)
        return when (code) {
            401, 403 -> AiResult.Error(
                "${provider.label} rejected the key (HTTP $code).",
                detail ?: "Check the key in Settings → INTELLIGENCE.",
            )
            404 -> AiResult.Error(
                "That model is not available on ${provider.label} (HTTP 404).",
                detail ?: "Set a model this key can access.",
            )
            413 -> AiResult.Error("The request was too large.", detail)
            429 -> AiResult.Error(
                "Rate limit reached on ${provider.label} (HTTP 429).",
                detail ?: "Wait a moment, or use a different key.",
            )
            in 500..599 -> AiResult.Error(
                "${provider.label} is having trouble (HTTP $code).",
                detail ?: "Try again shortly.",
            )
            else -> AiResult.Error("Unexpected reply (HTTP $code).", detail)
        }
    }

    private fun providerMessage(body: String): String? = runCatching {
        val root = JSONObject(body)
        val error = root.optJSONObject("error")
        error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()?.take(200)
}
