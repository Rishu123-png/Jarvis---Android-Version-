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
import java.net.UnknownHostException
import java.net.URL

/**
 * One turn, optionally with pictures.
 *
 * [images] holds base64 JPEG — no `data:` prefix, no mime type — because the three wire formats
 * want the same bytes inside three different envelopes, and building those envelopes is the job of
 * [AiClient], not of whoever took the photo. The list is empty for every ordinary turn, so nothing
 * about the text path changes.
 */
data class AiMessage(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
) {
    companion object {
        fun user(text: String) = AiMessage("user", text)
        fun assistant(text: String) = AiMessage("assistant", text)

        /** Text plus one or more base64 JPEGs, for the providers that can see. */
        fun looking(text: String, images: List<String>) =
            AiMessage("user", text, images.filter { it.isNotBlank() })
    }
}

sealed interface AiResult {
    data class Ok(
        val text: String,
        val providerLabel: String,
        val model: String,
        /** Set when the configured model was rejected and a fallback answered instead. */
        val note: String? = null,
    ) : AiResult

    data class Error(
        val message: String,
        val hint: String? = null,
    ) : AiResult
}

sealed interface ModelsResult {
    data class Ok(val models: List<String>, val providerLabel: String) : ModelsResult
    data class Error(val message: String, val hint: String? = null) : ModelsResult
}

/**
 * Minimal, dependency-free client for the three wire formats JARVIS supports.
 *
 * Stability and durability rules that matter more than features here:
 *  * every call runs on [Dispatchers.IO] — never the main thread;
 *  * connect and read timeouts are explicit, so a stalled provider cannot hang the UI;
 *  * a rejected model (HTTP 404) falls through to the provider's fallback list instead of failing
 *    the whole request — providers retire model ids without notice, and a hardcoded name is how a
 *    working app breaks months later with no code change;
 *  * the model that actually answered is reported back so the caller can remember it;
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
        val body: String?,
    )

    private class Credentials(
        val config: AiKeyStore.Config,
        val apiKey: String,
    )

    // -----------------------------------------------------------------------------------------
    // Chat
    // -----------------------------------------------------------------------------------------

    suspend fun chat(
        context: Context,
        messages: List<AiMessage>,
        system: String,
    ): AiResult = withContext(Dispatchers.IO) {
        val credentials = loadCredentials(context) ?: return@withContext missingKey(context)
        if (messages.isEmpty()) return@withContext AiResult.Error("Nothing to send.")

        val configured = credentials.config.resolvedModel
        val models = credentials.config.provider.candidates(configured)

        var modelMissing = false
        var lastMessage: String? = null

        for (model in models) {
            val request = try {
                buildRequest(credentials, model, messages, system)
            } catch (t: Throwable) {
                return@withContext AiResult.Error("Could not build the request.", t.message)
            }

            val response = try {
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

            val (code, body) = response

            // 404 almost always means "this model name is not available to your key". Walk on.
            if (code == 404) {
                modelMissing = true
                lastMessage = providerMessage(body)
                continue
            }

            if (code !in 200..299) {
                return@withContext errorFor(code, body, credentials.config.provider)
            }

            val text = extractText(credentials.config.provider.wire, body)
                ?: return@withContext AiResult.Error(
                    "The provider sent a reply JARVIS could not read.",
                    "HTTP $code · tap FETCH MODELS and pick one explicitly.",
                )

            // Remember the winner so the next request skips the failed candidates.
            if (model != configured) {
                AiKeyStore.updateConfig(
                    context = context,
                    provider = credentials.config.provider,
                    model = model,
                    baseUrl = credentials.config.baseUrl,
                )
            }

            return@withContext AiResult.Ok(
                text = text.take(MAX_REPLY_CHARS),
                providerLabel = credentials.config.provider.label,
                model = model,
                note = if (model != configured) "auto-selected" else null,
            )
        }

        if (modelMissing) {
            AiResult.Error(
                "No usable model on ${credentials.config.provider.label}.",
                lastMessage
                    ?: "Tap FETCH MODELS in Settings → INTELLIGENCE to list what your key can use.",
            )
        } else {
            AiResult.Error("No model configured.", "Set one in Settings → INTELLIGENCE.")
        }
    }

    /** Round-trips a fixed prompt so the operator can prove the key works. */
    suspend fun selfTest(context: Context): AiResult = chat(
        context = context,
        messages = listOf(AiMessage.user("Reply with exactly one word: ONLINE")),
        system = "You are a connectivity probe. Reply with one word only.",
    )

    // -----------------------------------------------------------------------------------------
    // Model discovery
    // -----------------------------------------------------------------------------------------

    /**
     * Asks the provider which models this key can actually use. This is the authoritative answer;
     * everything else in the app is a guess with a fallback.
     */
    suspend fun listModels(context: Context): ModelsResult = withContext(Dispatchers.IO) {
        val credentials = loadCredentials(context) ?: return@withContext missingModels()
        val provider = credentials.config.provider

        val request = when (provider.wire) {
            AiWire.OPENAI -> Request(
                url = "${baseUrl(credentials.config)}/models",
                headers = mapOf("Authorization" to "Bearer ${credentials.apiKey}"),
                body = null,
            )

            AiWire.ANTHROPIC -> Request(
                url = "${baseUrl(credentials.config)}/models?limit=100",
                headers = mapOf(
                    "x-api-key" to credentials.apiKey,
                    "anthropic-version" to "2023-06-01",
                ),
                body = null,
            )

            AiWire.GEMINI -> Request(
                url = "${baseUrl(credentials.config)}/models?pageSize=200&key=" +
                    URLEncoder.encode(credentials.apiKey, "UTF-8"),
                headers = emptyMap(),
                body = null,
            )
        }

        val (code, body) = try {
            send(request)
        } catch (e: SocketTimeoutException) {
            return@withContext ModelsResult.Error(
                "The provider did not answer in time.",
                "Try again in a moment.",
            )
        } catch (e: UnknownHostException) {
            return@withContext ModelsResult.Error("No network route.", "Check your connection.")
        } catch (e: IOException) {
            return@withContext ModelsResult.Error("Network error.", e.message)
        } catch (t: Throwable) {
            return@withContext ModelsResult.Error("Request failed.", t.message)
        }

        if (code !in 200..299) {
            val mapped = errorFor(code, body, provider)
            return@withContext ModelsResult.Error(mapped.message, mapped.hint)
        }

        val models = parseModels(provider.wire, body)
        if (models.isEmpty()) {
            ModelsResult.Error(
                "The provider returned no models.",
                "Your key may not have access to any, or this endpoint does not list them.",
            )
        } else {
            ModelsResult.Ok(models, provider.label)
        }
    }

    private fun parseModels(wire: AiWire, body: String): List<String> = runCatching {
        when (wire) {
            AiWire.OPENAI, AiWire.ANTHROPIC -> {
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                (0 until data.length())
                    .mapNotNull { index ->
                        data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
                    }
            }

            AiWire.GEMINI -> {
                val data = JSONObject(body).optJSONArray("models") ?: JSONArray()
                (0 until data.length())
                    .mapNotNull { index ->
                        val item = data.optJSONObject(index) ?: return@mapNotNull null
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        val canGenerate = methods == null ||
                            (0 until methods.length()).any {
                                methods.optString(it) == "generateContent"
                            }
                        if (!canGenerate) {
                            null
                        } else {
                            item.optString("name").removePrefix("models/")
                                .takeIf { it.isNotBlank() }
                        }
                    }
            }
        }
    }.getOrNull().orEmpty()
        .distinct()
        .sorted()
        .take(200)

    /** Keeps the list readable: chat-capable ids first, obvious non-chat entries last. */
    fun rankModels(models: List<String>): List<String> {
        val noise = listOf("embed", "whisper", "tts", "dall", "moderation", "audio", "image", "rerank")
        val (chat, other) = models.partition { id ->
            noise.none { id.contains(it, ignoreCase = true) }
        }
        return chat + other
    }

    // -----------------------------------------------------------------------------------------
    // Credentials
    // -----------------------------------------------------------------------------------------

    private fun loadCredentials(context: Context): Credentials? {
        val config = AiKeyStore.config(context) ?: return null
        val apiKey = AiKeyStore.apiKey(context) ?: return null
        return Credentials(config, apiKey)
    }

    private fun missingKey(context: Context): AiResult.Error =
        if (AiKeyStore.config(context) == null) {
            AiResult.Error("No API key configured.", "Add one in Settings → INTELLIGENCE.")
        } else {
            AiResult.Error(
                "The stored key could not be decrypted.",
                "Enter it again in Settings → INTELLIGENCE.",
            )
        }

    private fun missingModels(): ModelsResult = ModelsResult.Error(
        "No API key configured.",
        "Add one in Settings → INTELLIGENCE.",
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
        credentials: Credentials,
        model: String,
        messages: List<AiMessage>,
        system: String,
    ): Request {
        val provider = credentials.config.provider
        val apiKey = credentials.apiKey

        return when (provider.wire) {
            AiWire.OPENAI -> {
                val base = baseUrl(credentials.config)
                val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
                val array = JSONArray()
                if (system.isNotBlank()) {
                    array.put(JSONObject().put("role", "system").put("content", system))
                }
                messages.forEach { message ->
                    array.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", openAiContent(message)),
                    )
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
                    array.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("content", anthropicContent(message)),
                    )
                }
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", MAX_REPLY_TOKENS)
                    .put("system", system)
                    .put("temperature", 0.4)
                    .put("messages", array)
                    .toString()
                Request(
                    url = "${baseUrl(credentials.config)}/messages",
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
                            .put("parts", geminiParts(message)),
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
                    url = "${baseUrl(credentials.config)}/models/$model:generateContent?key=$encodedKey",
                    headers = emptyMap(),
                    body = root.toString(),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Content envelopes — text stays a plain string, pictures become the provider's own shape
    // ---------------------------------------------------------------------------------------------

    /**
     * OpenAI-style: `content` is a string, or an array of parts when there is an image. Keeping the
     * plain string for text-only turns matters — some OpenAI-compatible servers reject the array
     * form, and every text turn must keep working on them.
     */
    private fun openAiContent(message: AiMessage): Any {
        if (message.images.isEmpty()) return message.content
        val parts = JSONArray()
        if (message.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", message.content))
        }
        message.images.forEach { image ->
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$image"),
                    ),
            )
        }
        return parts
    }

    /** Anthropic: the image is a base64 source block, and it comes before the text. */
    private fun anthropicContent(message: AiMessage): Any {
        if (message.images.isEmpty()) return message.content
        val parts = JSONArray()
        message.images.forEach { image ->
            parts.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", image),
                    ),
            )
        }
        if (message.content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", message.content))
        }
        return parts
    }

    /** Gemini: `inline_data` per part, then the words. */
    private fun geminiParts(message: AiMessage): JSONArray {
        val parts = JSONArray()
        message.images.forEach { image ->
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", image),
                ),
            )
        }
        if (message.content.isNotBlank()) {
            parts.put(JSONObject().put("text", message.content))
        }
        return parts
    }

    private fun post(request: Request): Pair<Int, String> = send(request, "POST")

    private fun send(request: Request, method: String = "GET"): Pair<Int, String> {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (request.body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { stream ->
                    stream.write(request.body.toByteArray(Charsets.UTF_8))
                }
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
                detail ?: "Tap FETCH MODELS and pick one your key can reach.",
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
