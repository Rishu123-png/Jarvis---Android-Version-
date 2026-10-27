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
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.VoidBlack

/**
 * Single-activity host. This activity is the Home app target, with its own
 * intent filters; for now it is a plain launcher activity.
 */
/**
 * A FragmentActivity rather than a plain ComponentActivity: BiometricPrompt is hosted by a fragment,
 * so the lock screen's fingerprint/face prompt needs this base class. `setContent` works unchanged.
 */
class MainActivity : FragmentActivity() {

    /**
     * Set when the app was opened from the floating bubble or the widget, which both mean "start
     * listening". Kept as state so `singleTask` re-launches (onNewIntent) also arm the voice surface.
     */
    private var voiceArmed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceArmed = wantsVoice(intent)

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
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsVoice(intent)) voiceArmed = true
    }

    private fun wantsVoice(intent: Intent?): Boolean =
        intent?.getBooleanExtra("com.jarvis.assistant.extra.VOICE", false) == true
}
