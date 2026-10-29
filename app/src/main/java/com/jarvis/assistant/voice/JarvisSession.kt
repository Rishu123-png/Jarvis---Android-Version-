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

    override fun onShow(args: Bundle?) {
        super.onShow(args)
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
