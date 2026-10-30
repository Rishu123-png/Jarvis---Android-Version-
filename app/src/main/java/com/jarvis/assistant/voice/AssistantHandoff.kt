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

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            putExtra(EXTRA_VOICE, true)
        }

    /**
     * Returns false instead of throwing. A broken assistant service can swallow the phone's whole
     * assist gesture, so every path out of here is guarded.
     */
    fun open(context: Context): Boolean = try {
        context.startActivity(intent(context))
        true
    } catch (t: Throwable) {
        false
    }
}
