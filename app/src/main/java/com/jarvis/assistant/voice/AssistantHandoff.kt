package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.MainActivity

/**
 * The single piece of plumbing between "the phone's assistant gesture fired" and "the app is
 * listening".
 *
 * One place, so the session, the recognition service and a plain `ACTION_ASSIST` launch all end up
 * in the same state instead of three slightly different ones.
 */
object AssistantHandoff {

    const val EXTRA_VOICE = "com.jarvis.assistant.extra.VOICE"

    /**
     * The screen the assist gesture fired on, as one line of labels.
     *
     * Carried as an extra so the activity can be restarted without losing it, and mirrored in a
     * static field so [takeAssist] works when the activity was already alive and the intent only
     * arrives through `onNewIntent`.
     */
    const val EXTRA_ASSIST = "com.jarvis.assistant.extra.ASSIST"

    @Volatile
    private var assist: String = ""

    /** Remembers the screen for the next turn. Blank is a valid answer: many screens are silent. */
    fun hand(assistText: String) {
        assist = assistText.take(com.jarvis.assistant.data.AssistContext.LIMIT)
    }

    /** Reads the screen for this turn, and clears it: one page, one answer. */
    fun takeAssist(): String {
        val held = assist
        assist = ""
        return held
    }

    fun intent(context: Context, assistText: String = ""): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            putExtra(EXTRA_VOICE, true)
            if (assistText.isNotBlank()) putExtra(EXTRA_ASSIST, assistText)
        }

    /**
     * Returns false instead of throwing. A broken assistant service can swallow the phone's whole
     * assist gesture, so every path out of here is guarded.
     */
    fun open(context: Context, assistText: String = ""): Boolean = try {
        hand(assistText)
        context.startActivity(intent(context, assistText))
        true
    } catch (t: Throwable) {
        false
    }
}
