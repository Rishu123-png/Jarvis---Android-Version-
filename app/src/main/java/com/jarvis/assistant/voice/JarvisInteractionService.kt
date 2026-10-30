package com.jarvis.assistant.voice

import android.service.voice.VoiceInteractionService

/**
 * The assistant seat, held open by the system.
 *
 * This class deliberately does nothing. Android binds it to decide that JARVIS *may* be the
 * assistant, and it is the only component that has to exist for the role to be offered at all.
 * There is no listener here, no wake word, no microphone, no thread: the work happens only when the
 * gesture fires and [JarvisSession] hands over to the app.
 *
 * If this file ever grows a `onCreate` that starts something, it has become a background service
 * and this app no longer idles at zero.
 */
class JarvisInteractionService : VoiceInteractionService()
