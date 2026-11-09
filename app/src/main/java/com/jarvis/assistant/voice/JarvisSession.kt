package com.jarvis.assistant.voice

import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import com.jarvis.assistant.data.AssistContext

/**
 * What happens when the phone's assistant gesture fires.
 *
 * The session does not draw anything of its own — the app already has the one listening surface it
 * needs, with the right language, the right guards and the right mic lifetime. So the session hands
 * over and closes itself in the same instant.
 *
 * Two details that matter:
 *  - the handover is attempted *before* [hide], so a slow activity start cannot leave the session
 *    window sitting empty on top of the phone;
 *  - every failure is swallowed. A crash here is not a crash in a corner of the app, it is the
 *    assist gesture misbehaving on the whole phone.
 */
class JarvisSession(context: Context) : VoiceInteractionSession(context) {

    /** Guards against handing over twice if both show forms ever arrive. */
    private var handed = false

    /**
     * The screen, read from the Assist API before a word is spoken.
     *
     * This is the one place in the app that gets another app's accessibility tree *without* the
     * operator having granted the screen reader: the gesture itself brings the structure along. It
     * is read here, reduced to plain text by [AssistContext], and handed to the app with the
     * intent — so "what does this say?" and "reply to this" have something to look at.
     *
     * Everything is guarded. A structure from a secure window is mostly nulls, some apps return an
     * empty tree, and a throw here would kill the phone's assist gesture.
     */
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        try {
            assistText = readStructure(state.assistStructure)
        } catch (t: Throwable) {
            assistText = ""
        }
        handOver()
    }

    /** Read once, carried by the handover. */
    private var assistText: String = ""

    private fun readStructure(structure: AssistStructure?): String {
        if (structure == null) return ""
        val nodes = ArrayList<AssistContext.Node>(64)
        for (index in 0 until structure.windowCount) {
            val window = structure.getWindowAt(index) ?: continue
            val root = window.root ?: continue
            collect(root, nodes, depth = 0)
            if (nodes.size >= AssistContext.MAX_NODES) break
        }
        return AssistContext.summarise(nodes)
    }

    private fun collect(node: AssistStructure.ViewNode, into: MutableList<AssistContext.Node>, depth: Int) {
        if (depth > AssistContext.MAX_DEPTH || into.size >= AssistContext.MAX_NODES) return
        val secret = isSecret(node)
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val hint = node.hint.orEmpty()
        if (text.isNotBlank() || description.isNotBlank() || hint.isNotBlank()) {
            into.add(
                AssistContext.Node(
                    text = listOf(text, description, hint).filter { it.isNotBlank() }.joinToString(" "),
                    password = secret,
                ),
            )
        }
        for (child in 0 until node.childCount) {
            node.getChildAt(child)?.let { collect(it, into, depth + 1) }
        }
    }

    /**
     * Whether a field holds something the operator would not want sent anywhere.
     *
     * The platform already withholds most password text, but a field can be a visible-password field
     * or a web password box, and this is a belt as well as braces: the string is dropped before it
     * is ever assembled, not filtered afterwards.
     */
    private fun isSecret(node: AssistStructure.ViewNode): Boolean {
        val inputType = node.inputType
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> true
            else -> false
        }
    }

    /** Android 12 and later. */
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        handOver()
    }

    /**
     * Android 10 and 11.
     *
     * Deliberately **not** marked `override`: Android 12 replaced the one-argument `onShow` with the
     * two-argument form, so this signature no longer exists to override in the SDK we compile
     * against — but it is still exactly the method an older system calls on this object at runtime.
     * Same JVM signature, so the dispatch lands here. Removing it would leave the gesture doing
     * nothing at all on Android 10 and 11.
     */
    @Suppress("unused")
    fun onShow(args: Bundle?) {
        handOver()
    }

    private fun handOver() {
        if (handed) return
        handed = true
        try {
            AssistantHandoff.open(context, assistText)
        } catch (t: Throwable) {
            // Never let the gesture end in a stack trace on someone's screen.
        }
        try {
            hide()
        } catch (t: Throwable) {
            // Already gone.
        }
    }
}
