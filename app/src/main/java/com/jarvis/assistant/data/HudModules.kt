package com.jarvis.assistant.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.Amber

/**
 * A shortcut tile on the HUD.
 *
 * Tiles are navigation entry points. [isLive] marks the ones that are backed by real device APIs;
 * the rest are marked STAGED on the HUD rather than pretending to work.
 */
data class HudModule(
    val id: String,
    val title: String,
    val caption: String,
    val icon: ImageVector,
    val accent: Color,
    val isLive: Boolean,
)

object HudModules {

    const val APPS = "apps"
    const val NETWORK = "network"
    const val POWER = "power"
    const val DIAGNOSTICS = "diagnostics"
    const val NOTIFICATIONS = "notifications"
    const val CONTROLS = "controls"
    const val VOICE = "voice"
    const val AUTOMATION = "automation"
    const val INSTAGRAM = "instagram"
    const val CONNECT = "connect"

    val all: List<HudModule> = listOf(
        HudModule(
            id = APPS,
            title = "APPS",
            caption = "Installed apps",
            icon = Icons.Outlined.Apps,
            accent = ElectricCyan,
            isLive = true,
        ),
        HudModule(
            id = NETWORK,
            title = "NETWORK",
            caption = "Link telemetry",
            icon = Icons.Outlined.Wifi,
            accent = IceBlue,
            isLive = true,
        ),
        HudModule(
            id = POWER,
            title = "POWER",
            caption = "Cell diagnostics",
            icon = Icons.Outlined.BatteryFull,
            accent = StatusGreen,
            isLive = true,
        ),
        HudModule(
            id = DIAGNOSTICS,
            title = "DIAGNOSTICS",
            caption = "Core telemetry",
            icon = Icons.Outlined.Memory,
            accent = ElectricCyan,
            isLive = true,
        ),
        HudModule(
            id = NOTIFICATIONS,
            title = "ALERTS",
            caption = "Notification shade",
            icon = Icons.Outlined.NotificationsActive,
            accent = ElectricCyan,
            isLive = true,
        ),
        HudModule(
            id = CONTROLS,
            title = "CONTROLS",
            caption = "Quick actions",
            icon = Icons.Outlined.Tune,
            accent = IceBlue,
            isLive = true,
        ),
        HudModule(
            id = VOICE,
            title = "VOICE",
            caption = "Speech I/O",
            icon = Icons.Outlined.Mic,
            accent = Amber,
            isLive = false,
        ),
        HudModule(
            id = AUTOMATION,
            title = "AUTOMATION",
            caption = "Routine control",
            icon = Icons.Outlined.AutoAwesome,
            accent = AlertRed,
            isLive = true,
        ),
        HudModule(
            id = INSTAGRAM,
            title = "INSTAGRAM",
            caption = "Creator analytics",
            icon = Icons.Outlined.PhotoCamera,
            accent = IceBlue,
            isLive = true,
        ),
        HudModule(
            id = CONNECT,
            title = "CONNECTIONS",
            caption = "Accounts · Drive · Gmail",
            icon = Icons.Outlined.Link,
            accent = IceBlue,
            isLive = true,
        ),
    )

    val terminalIcon: ImageVector = Icons.Outlined.Radar

    fun byId(id: String?): HudModule? = all.firstOrNull { it.id == id }
}
