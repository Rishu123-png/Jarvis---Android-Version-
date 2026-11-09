package com.jarvis.assistant.ai

/**
 * What came back when a model was handed a tool instead of a paragraph.
 *
 * Four outcomes, and the difference between them is the whole point:
 *
 *  * [Called] — the model used the schema. This is the good path.
 *  * [Said] — the model answered in prose anyway. Not a failure: the caller still has the parser it
 *    had before, and a model that talks its way to a valid object is the same object.
 *  * [Unsupported] — the server rejected the request *because* of `tools`. Worth remembering, so it
 *    is not paid for again on every step of every routine.
 *  * [Failed] — the network, the key, or the model list. The caller reports it as it always did.
 *
 * Separating [Said] from [Unsupported] matters: one means "try the other path", the other means
 * "this provider can never do this, stop asking".
 */
sealed interface ToolResult {

    data class Called(
        val call: ToolCalls.Call,
        val providerLabel: String,
        val model: String,
        val note: String? = null,
    ) : ToolResult

    data class Said(val text: String) : ToolResult

    data class Unsupported(val reason: String) : ToolResult

    data class Failed(val error: AiResult.Error) : ToolResult
}
