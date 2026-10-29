package com.jarvis.assistant.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates one short-lived session per assist gesture. It holds no state between gestures. */
class JarvisSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisSession(this)
}
