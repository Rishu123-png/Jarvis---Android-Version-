package com.jarvis.assistant.ai

import org.json.JSONObject

/**
 * Reading a provider's stream, one line at a time.
 *
 * All three wires speak Server-Sent Events and none of them agree on what is inside. OpenAI sends
 * `choices[0].delta.content`, Anthropic sends a `content_block_delta` whose `delta.text` is the
 * piece, and Gemini — only when asked for `alt=sse` — sends `candidates[0].content.parts[].text`.
 * Everything else in the stream (role announcements, token counts, ping events, `message_stop`) is
 * noise that must produce an empty delta rather than a spoken word.
 *
 * Pure functions, no sockets: the socket belongs to [AiClient], the shape belongs here, where it can
 * be tested against real captured frames.
 */
object Sse {

    /** The payload of one `data:` line, or null for comments, blanks and every other field type. */
    fun payload(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val body = trimmed.removePrefix("data:").trim()
        if (body.isEmpty()) return null
        return body
    }

    /** The end-of-stream marker. OpenAI sends it; the other two just close the connection. */
    fun isEnd(data: String): Boolean = data == "[DONE]"

    /**
     * The text this frame adds, or null when the frame carries no words.
     *
     * A frame that does not parse is treated as no words rather than as an error: a stream is not
     * worth failing over one unreadable line when the next one is already on the wire.
     */
    fun delta(wire: AiWire, data: String): String? = runCatching {
        when (wire) {
            AiWire.OPENAI -> openAi(data)
            AiWire.ANTHROPIC -> anthropic(data)
            AiWire.GEMINI -> gemini(data)
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** `choices[0].delta.content`, null on the first frame where only the role is announced. */
    private fun openAi(data: String): String? {
        val delta = JSONObject(data)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?: return null
        return delta.optString("content").takeIf { it.isNotEmpty() }
    }

    /** Only `content_block_delta` carries words; the rest of the event types are bookkeeping. */
    private fun anthropic(data: String): String? {
        val json = JSONObject(data)
        if (json.optString("type") != "content_block_delta") return null
        return json.optJSONObject("delta")?.optString("text").orEmpty().takeIf { it.isNotEmpty() }
    }

    /** Gemini can put several parts in one frame, and any of them may be the words. */
    private fun gemini(data: String): String? {
        val parts = JSONObject(data)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        val text = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            text.append(part.optString("text"))
        }
        return text.toString().takeIf { it.isNotEmpty() }
    }

    /** A complete SSE body, for a test that wants the whole stream at once. */
    fun deltas(wire: AiWire, body: String): String = buildString {
        for (line in body.lineSequence()) {
            val data = payload(line) ?: continue
            if (isEnd(data)) break
            delta(wire, data)?.let { append(it) }
        }
    }
}
