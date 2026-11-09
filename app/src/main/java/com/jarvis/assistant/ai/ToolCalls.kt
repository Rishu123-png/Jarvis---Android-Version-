package com.jarvis.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading the model's answer when the answer is a tool call instead of a paragraph.
 *
 * Three wires, three envelopes, one result: a name and an arguments object. Kept apart from
 * [AiClient] because it is pure JSON handling, and because the shape of each envelope is exactly the
 * kind of thing that should have a test rather than a device.
 *
 * Nothing here trusts the body. A response that is not a tool call returns null, and the caller falls
 * back to the prose path it has always had — a model that ignores `tools` must not be able to break
 * a routine that used to work.
 */
object ToolCalls {

    data class Call(val name: String, val arguments: String) {
        /** The arguments as an object, or null when the model sent something unparseable. */
        fun argumentsObject(): JSONObject? = runCatching { JSONObject(arguments) }.getOrNull()
    }

    fun from(wire: AiWire, body: String): Call? = runCatching {
        when (wire) {
            AiWire.OPENAI -> openAi(body)
            AiWire.ANTHROPIC -> anthropic(body)
            AiWire.GEMINI -> gemini(body)
        }
    }.getOrNull()

    /** `choices[0].message.tool_calls[0].function` — arguments arrive as a JSON *string*. */
    private fun openAi(body: String): Call? {
        val message = JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: return null
        val calls = message.optJSONArray("tool_calls") ?: return null
        if (calls.length() == 0) return null
        val function = calls.optJSONObject(0)?.optJSONObject("function") ?: return null
        val name = function.optString("name").trim()
        if (name.isEmpty()) return null
        val arguments = function.optString("arguments").trim()
        return Call(name, arguments.ifEmpty { "{}" })
    }

    /** `content[]` holds blocks; the interesting one has `type == "tool_use"` and an object `input`. */
    private fun anthropic(body: String): Call? {
        val blocks = JSONObject(body).optJSONArray("content") ?: return null
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") != "tool_use") continue
            val name = block.optString("name").trim()
            if (name.isEmpty()) continue
            val input = block.optJSONObject("input")
            return Call(name, input?.toString() ?: "{}")
        }
        return null
    }

    /** `candidates[0].content.parts[]` and a part carrying `functionCall{name, args}`. */
    private fun gemini(body: String): Call? {
        val parts = JSONObject(body)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        for (index in 0 until parts.length()) {
            val call = parts.optJSONObject(index)?.optJSONObject("functionCall") ?: continue
            val name = call.optString("name").trim()
            if (name.isEmpty()) continue
            return Call(name, call.optJSONObject("args")?.toString() ?: "{}")
        }
        return null
    }

    /**
     * True when the body contains a tool call at all, whatever its name. Used to tell "the model
     * called nothing" apart from "the model cannot call anything", which are different failures.
     */
    fun present(wire: AiWire, body: String): Boolean = from(wire, body) != null

    /** A short, safe rendering of a call for the run log. Never includes a param body. */
    fun describe(call: Call): String {
        val keys = call.argumentsObject()?.keySet()?.sorted()?.joinToString(",") ?: ""
        return if (keys.isEmpty()) call.name else "${call.name}($keys)"
    }

    /** Unused by production code; kept so a test can build the exact envelope each wire sends. */
    internal fun envelopeForTest(wire: AiWire, name: String, arguments: JSONObject): String = when (wire) {
        AiWire.OPENAI ->
            JSONObject()
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject().put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", name)
                                                .put("arguments", arguments.toString()),
                                        ),
                                ),
                            ),
                        ),
                    ),
                )
                .toString()

        AiWire.ANTHROPIC ->
            JSONObject()
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "thinking"))
                        .put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("name", name)
                                .put("input", arguments),
                        ),
                )
                .toString()

        AiWire.GEMINI ->
            JSONObject()
                .put(
                    "candidates",
                    JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "functionCall",
                                        JSONObject().put("name", name).put("args", arguments),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                .toString()
    }
}
