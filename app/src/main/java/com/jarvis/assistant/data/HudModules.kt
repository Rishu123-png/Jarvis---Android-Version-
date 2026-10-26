package com.jarvis.assistant.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Memory
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
 * Phase 1 ships these as navigation entry points into read-only panels. Later phases attach
 * real capability to each [id] — the tile itself should not need to change.
 */
data class HudModule(
    val id: String,
    val title: String,
    val caption: String,
    val icon: ImageVector,
    val accent: Color,
    /** Roadmap phase that will make this tile fully functional. */
    val phase: Int,
    val isLive: Boolean,
)

object HudModules {

    const val APPS = "apps"
    const val NETWORK = "network"
    const val POWER = "power"
    const val DIAGNOSTICS = "diagnostics"
    const val VOICE = "voice"
    const val AUTOMATION = "automation"

    val all: List<HudModule> = listOf(
        HudModule(
            id = APPS,
            title = "APPS",
            caption = "Launcher grid",
            icon = Icons.Outlined.Apps,
            accent = ElectricCyan,
            phase = 2,
            isLive = false,
        ),
        HudModule(
            id = NETWORK,
            title = "NETWORK",
            caption = "Link telemetry",
            icon = Icons.Outlined.Wifi,
            accent = IceBlue,
            phase = 3,
            isLive = true,
        ),
        HudModule(
            id = POWER,
            title = "POWER",
            caption = "Cell diagnostics",
            icon = Icons.Outlined.BatteryFull,
            accent = StatusGreen,
            phase = 3,
            isLive = true,
        ),
        HudModule(
            id = DIAGNOSTICS,
            title = "DIAGNOSTICS",
            caption = "Core telemetry",
            icon = Icons.Outlined.Memory,
            accent = ElectricCyan,
            phase = 1,
            isLive = true,
        ),
        HudModule(
            id = VOICE,
            title = "VOICE",
            caption = "Speech I/O",
            icon = Icons.Outlined.Mic,
            accent = Amber,
            phase = 4,
            isLive = false,
        ),
        HudModule(
            id = AUTOMATION,
            title = "AUTOMATION",
            caption = "Task control",
            icon = Icons.Outlined.AutoAwesome,
            accent = AlertRed,
            phase = 6,
            isLive = false,
        ),
    )

    val terminalIcon: ImageVector = Icons.Outlined.Radar

    fun byId(id: String?): HudModule? = all.firstOrNull { it.id == id }
}
