package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextSecondary

// ---------------------------------------------------------------------------------------------
// APPS — superseded by AppDrawerScreen, kept as the empty/failure explanation surface.
// ---------------------------------------------------------------------------------------------

/**
 * Shown only if the drawer has nothing to display. This exists because an empty drawer is the
 * most common launcher bug, and a bare blank screen tells the user nothing.
 */
@Composable
fun AppsScreen() {
    HudPanel(label = "LAUNCHER · NO APPS INDEXED", accent = ElectricCyan) {
        Text(
            text = "PackageManager returned no launchable activities. The drawer now reads real " +
                "installed apps, so an empty list means the query itself failed.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        ReadoutRow(label = "QUERY", value = "MAIN / LAUNCHER")
        ReadoutRow(label = "MANIFEST", value = "<queries> REQUIRED", valueColor = StatusGreen)
        ReadoutRow(label = "FILTER", value = "SELF EXCLUDED")
        HudDivider(modifier = Modifier.padding(vertical = 8.dp), accent = ElectricCyan)
        Text(
            text = "On Android 11+ a package visibility declaration is mandatory. Without the " +
                "<queries> element in AndroidManifest.xml, queryIntentActivities returns an " +
                "empty list with no error at all.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// VOICE
// ---------------------------------------------------------------------------------------------

@Composable
fun VoiceScreen() {
    StagedWatermark(icon = Icons.Outlined.Mic, accent = IceBlue, label = "SPEECH INTERFACE · STAGED")

    HudPanel(label = "MODULE STATUS", accent = IceBlue) {
        ReadoutRow(label = "STATE", value = "NOT INSTALLED", valueColor = TextMuted)
        ReadoutRow(label = "STATUS", value = "NOT BUILT", valueColor = TextMuted)
        ReadoutRow(label = "ENGINE", value = "SPEECH RECOGNIZER")
        ReadoutRow(label = "MIC PERMISSION", value = "NOT REQUESTED", valueColor = StatusGreen)
        HudDivider(modifier = Modifier.padding(vertical = 8.dp), accent = IceBlue)
        Text(
            text = "JARVIS does not hold the microphone permission today. The permission will " +
                "be requested only when the voice module is opened, and recording stops the " +
                "moment the command ends.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
    }

    HudPanel(label = "PLANNED CAPABILITY") {
        listOf(
            "Push-to-talk microphone capture",
            "On-device speech to text",
            "Spoken JARVIS responses",
            "Conversational follow-up turns",
        ).forEach { line ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(IceBlue.copy(alpha = 0.8f)),
                )
                Spacer(Modifier.width(9.dp))
                Text(text = line, style = HudType.value, color = TextSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
