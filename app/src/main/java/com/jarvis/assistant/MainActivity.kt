package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jarvis.assistant.ui.JarvisApp
import com.jarvis.assistant.voice.AssistantHandoff
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.VoidBlack

/**
 * Single-activity host. This activity is the Home app target, with its own
 * intent filters; for now it is a plain launcher activity.
 */
class MainActivity : ComponentActivity() {

    /**
     * Set when the app was opened from the floating bubble, the widget, or the phone's assistant
     * gesture — all of which mean "start listening". Kept as state so `singleTask` re-launches
     * (onNewIntent) also arm the voice surface.
     */
    private var voiceArmed by mutableStateOf(false)

    /** Set by a launcher shortcut, so a long-press lands straight on that surface. */
    private var startRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceArmed = wantsVoice(intent)
        startRoute = routeOf(intent)

        setContent {
            JarvisTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(VoidBlack),
                    color = VoidBlack,
                ) {
                    JarvisApp(
                        voiceArmed = voiceArmed,
                        onVoiceConsumed = { voiceArmed = false },
                        startRoute = startRoute,
                        onRouteConsumed = { startRoute = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsVoice(intent)) voiceArmed = true
        routeOf(intent)?.let { startRoute = it }
    }

    private fun routeOf(intent: Intent?): String? =
        intent?.getStringExtra("com.jarvis.assistant.extra.ROUTE")?.takeIf { it.isNotBlank() }

    /**
     * The voice extra covers the bubble, the widget and our own assistant session. `ACTION_ASSIST`
     * covers the OEMs that hand the gesture straight to the activity instead of opening a
     * VoiceInteractionSession — the same intent, a different door.
     */
    private fun wantsVoice(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(AssistantHandoff.EXTRA_VOICE, false)) return true
        return intent.action == Intent.ACTION_ASSIST
    }
}
