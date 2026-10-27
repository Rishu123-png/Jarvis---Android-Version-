package com.jarvis.assistant.ai

import org.json.JSONObject

/**
 * Pulls the JSON object out of a model reply.
 *
 * Providers are inconsistent about instructions: some wrap the object in a code fence, some add a
 * sentence of prose before it, some add one after. Everything here is tolerant of that, and returns
 * null — never a guess — when there is no object to find.
 *
 * Pure and dependency-free so it can be tested on the JVM without a device or a key.
 */
object JsonReply {

    fun extractObject(reply: String): JSONObject? {
        val text = reply.trim()
        if (text.isEmpty()) return null

        // Fast path: the whole reply is one object (the common case with a well-behaved model).
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                return JSONObject(text)
            } catch (t: Throwable) {
                // Fall through to the scan: prose or braces inside strings.
            }
        }

        // Every '{' is a candidate start; the first one that yields a balanced, valid object wins.
        var index = text.indexOf('{')
        while (index >= 0) {
            val slice = firstBalanced(text, index)
            if (slice != null) {
                try {
                    return JSONObject(slice)
                } catch (t: Throwable) {
                    // Not this one.
                }
            }
            index = text.indexOf('{', index + 1)
        }
        return null
    }

    /** Walks from [start] counting braces, ignoring any inside string literals. */
    private fun firstBalanced(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
