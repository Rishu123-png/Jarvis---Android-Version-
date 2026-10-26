package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import com.jarvis.assistant.ui.theme.TileShape

// ---------------------------------------------------------------------------------------------
// APPS (phase 2)
// ---------------------------------------------------------------------------------------------

private data class AppSlot(val name: String, val icon: ImageVector)

private val appSlots = listOf(
    AppSlot("PHONE", Icons.Outlined.Phone),
    AppSlot("MESSAGES", Icons.Outlined.Email),
    AppSlot("CAMERA", Icons.Outlined.CameraAlt),
    AppSlot("GALLERY", Icons.Outlined.Photo),
    AppSlot("MAPS", Icons.Outlined.Map),
    AppSlot("BROWSER", Icons.Outlined.Public),
    AppSlot("CLOCK", Icons.Outlined.Alarm),
    AppSlot("SETTINGS", Icons.Outlined.Settings),
)

@Composable
fun AppsScreen() {
    HudPanel(label = "LAUNCHER PREVIEW", accent = ElectricCyan) {
        Text(
            text = "Phase 1 shows the intended grid with placeholder slots. Phase 2 replaces " +
                "these with shortcuts built from the installed-app packages, and Jarvis becomes " +
                "selectable as the Android Home app.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
    }

    SectionHeader(title = "SLOTS", trailing = "8 STAGED")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        appSlots.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { slot ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.92f)
                            .background(PanelNavy.copy(alpha = 0.7f), TileShape)
                            .border(1.dp, ElectricCyan.copy(alpha = 0.10f), TileShape)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = slot.icon,
                            contentDescription = slot.name,
                            tint = TextMuted.copy(alpha = 0.75f),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = slot.name,
                            style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.6.sp),
                            color = TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    HudPanel(label = "WHY EMPTY", accent = TextMuted) {
        Text(
            text = "Slots are intentionally inert until the launcher module can query " +
                "PackageManager and launch real activities. Showing fake app icons would " +
                "misrepresent what the device is doing.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// VOICE (phase 4)
// ---------------------------------------------------------------------------------------------

@Composable
fun VoiceScreen() {
    StagedWatermark(icon = Icons.Outlined.Mic, accent = IceBlue, label = "SPEECH INTERFACE · STAGED")

    HudPanel(label = "MODULE STATUS", accent = IceBlue) {
        ReadoutRow(label = "STATE", value = "NOT INSTALLED", valueColor = TextMuted)
        ReadoutRow(label = "TARGET PHASE", value = "PHASE 4")
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
// AUTOMATION (phase 6)
// ---------------------------------------------------------------------------------------------

@Composable
fun AutomationScreen() {
    StagedWatermark(
        icon = Icons.Outlined.AutoAwesome,
        accent = AlertRed,
        label = "AUTOMATION · STAGED",
    )

    HudPanel(label = "MODULE STATUS", accent = AlertRed) {
        ReadoutRow(label = "STATE", value = "LOCKED", valueColor = AlertRed)
        ReadoutRow(label = "TARGET PHASE", value = "PHASE 6")
        ReadoutRow(label = "ACCESSIBILITY", value = "NOT ENABLED", valueColor = StatusGreen)
        HudDivider(modifier = Modifier.padding(vertical = 8.dp), accent = AlertRed)
        Text(
            text = "No accessibility service is declared by this build. Automation will need an " +
                "explicit, user-visible enable step in Android settings plus a confirmation " +
                "prompt before any sensitive action runs.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
    }

    HudPanel(label = "DESIGN RULES", accent = AlertRed) {
        listOf(
            "Every automated action is confirmed before it runs",
            "No silent background control of other apps",
            "Nothing leaves the device without a visible prompt",
            "The user can revoke automation at any time",
        ).forEach { line ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(AlertRed.copy(alpha = 0.8f)),
                )
                Spacer(Modifier.width(9.dp))
                Text(text = line, style = HudType.value, color = TextSecondary)
            }
        }
    }

    HudPanel(label = "REALITY CHECK", accent = TextMuted) {
        Text(
            text = "Android does not allow an ordinary app to control every screen or every " +
                "app. JARVIS automates only what the platform exposes, and says so plainly.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
    }
}
