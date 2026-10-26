package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jarvis.assistant.ui.JarvisApp
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.VoidBlack

/**
 * Single-activity host. Phase 2 will turn this activity into a Home app target with its own
 * intent filters; for now it is a plain launcher activity.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JarvisTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(VoidBlack),
                    color = VoidBlack,
                ) {
                    JarvisApp()
                }
            }
        }
    }
}
