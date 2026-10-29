package com.jarvis.assistant.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

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
            AssistantHandoff.open(context)
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
