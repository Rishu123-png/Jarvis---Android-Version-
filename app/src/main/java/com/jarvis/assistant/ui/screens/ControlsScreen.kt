package com.jarvis.assistant.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.device.DndControl
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudMeter
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import com.jarvis.assistant.ui.theme.TileShape

/**
 * Quick controls.
 *
 * Split deliberately into "JARVIS can do this" and "JARVIS hands this to Android", because a HUD
 * that silently fails to toggle Wi-Fi is worse than one that opens the Wi-Fi panel.
 */
@Composable
fun ControlsScreen(
    torchOn: Boolean,
    torchAvailable: Boolean,
    volumePercent: Int,
    mediaTitle: String?,
    isPlaying: Boolean,
    hasNotificationAccess: Boolean,
    onToggleTorch: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenInternetPanel: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenDndSettings: () -> Unit,
    dndAccess: Boolean,
    dndMode: DndControl.Mode?,
    onSetDnd: (DndControl.Mode) -> Unit,
    onOpenDndAccess: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onBack: () -> Unit,
) {
    ModuleScaffold(
        title = "CONTROLS",
        subtitle = "quick actions",
        accent = ElectricCyan,
        statusLabel = "LIVE",
        onBack = onBack,
    ) {
        // ---------------- direct control ----------------
        HudPanel(label = "DIRECT CONTROL", accent = ElectricCyan) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ControlTile(
                    icon = Icons.Outlined.FlashlightOn,
                    label = "TORCH",
                    state = when {
                        !torchAvailable -> "UNAVAILABLE"
                        torchOn -> "ON"
                        else -> "OFF"
                    },
                    active = torchOn,
                    enabled = torchAvailable,
                    onClick = onToggleTorch,
                    modifier = Modifier.weight(1f),
                )
                ControlTile(
                    icon = Icons.Outlined.VolumeDown,
                    label = "VOL −",
                    state = "$volumePercent%",
                    active = false,
                    enabled = true,
                    onClick = onVolumeDown,
                    modifier = Modifier.weight(1f),
                )
                ControlTile(
                    icon = Icons.Outlined.VolumeUp,
                    label = "VOL +",
                    state = "$volumePercent%",
                    active = false,
                    enabled = true,
                    onClick = onVolumeUp,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "MEDIA VOLUME",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            HudMeter(progress = volumePercent / 100f, accent = ElectricCyan, height = 5.dp)

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Torch and volume are applied immediately — no permission needed.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }

        // ---------------- media ----------------
        HudPanel(label = "MEDIA TRANSPORT", accent = IceBlue) {
            if (!hasNotificationAccess) {
                Text(
                    text = "Media control needs notification access: Android only exposes active " +
                        "media sessions to notification listeners.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                PillButton(
                    label = "ENABLE NOTIFICATION ACCESS",
                    accent = Amber,
                    onClick = onOpenNotificationAccess,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = IceBlue.copy(alpha = 0.85f),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = mediaTitle ?: "NO ACTIVE MEDIA SESSION",
                        style = HudType.value,
                        color = if (mediaTitle != null) TextPrimary else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TransportButton(
                        icon = Icons.Outlined.SkipPrevious,
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f),
                    )
                    TransportButton(
                        icon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        onClick = onTogglePlayback,
                        highlighted = true,
                        modifier = Modifier.weight(1.4f),
                    )
                    TransportButton(
                        icon = Icons.Outlined.SkipNext,
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---------------- quiet mode ----------------
        HudPanel(
            label = "QUIET MODE",
            accent = if (dndAccess) StatusGreen else Amber,
            trailing = {
                Text(
                    text = if (dndAccess) {
                        (dndMode ?: DndControl.Mode.OFF).label
                    } else {
                        "ACCESS NEEDED"
                    },
                    style = HudType.label.copy(fontSize = 8.sp),
                    color = if (dndAccess) StatusGreen else Amber,
                )
            },
        ) {
            Text(
                text = if (dndAccess) {
                    "Silences interruptions directly. Android allows this only because you granted " +
                        "Do Not Disturb access; revoke it any time and these buttons open the " +
                        "system screen instead."
                } else {
                    "JARVIS cannot change interruptions until you grant Do Not Disturb access. " +
                        "The button below opens the exact system screen for it."
                },
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))

            DndControl.Mode.entries.forEach { mode ->
                ModeButton(
                    mode = mode,
                    selected = dndMode == mode,
                    enabled = dndAccess,
                    onClick = { onSetDnd(mode) },
                )
                Spacer(Modifier.height(7.dp))
            }

            if (!dndAccess) {
                Spacer(Modifier.height(3.dp))
                ModeButton(
                    mode = null,
                    selected = false,
                    enabled = true,
                    onClick = onOpenDndAccess,
                )
            }
        }

        // ---------------- handed to android ----------------
        HudPanel(label = "SYSTEM PANELS", accent = TextMuted) {
            Text(
                text = "Android does not let a normal app flip these silently. Each button opens " +
                    "the matching system control, pre-focused.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))

            SystemRow(
                icon = Icons.Outlined.Wifi,
                label = "WI-FI / MOBILE DATA",
                caption = "System connectivity panel",
                onClick = onOpenInternetPanel,
            )
            SystemRow(
                icon = Icons.Outlined.Bluetooth,
                label = "BLUETOOTH",
                caption = "Bluetooth settings",
                onClick = onOpenBluetoothSettings,
            )
            SystemRow(
                icon = Icons.Outlined.DoNotDisturbOn,
                label = "DO NOT DISTURB",
                caption = if (dndAccess) "Full control above · this opens system settings" else "Zen mode settings",
                onClick = onOpenDndSettings,
            )
            SystemRow(
                icon = Icons.Outlined.Brightness6,
                label = "DISPLAY / BRIGHTNESS",
                caption = "Display settings",
                onClick = onOpenDisplaySettings,
            )
        }

        HudPanel(label = "WHY SOME TOGGLES ARE MISSING", accent = TextMuted) {
            ReadoutRow(
                label = "DND TOGGLE",
                value = if (dndAccess) "DIRECT" else "NEEDS ONE GRANT",
                valueColor = if (dndAccess) StatusGreen else Amber,
            )
            ReadoutRow(label = "WIFI TOGGLE", value = "SYSTEM ONLY")
            ReadoutRow(label = "BLUETOOTH TOGGLE", value = "SYSTEM ONLY")
            ReadoutRow(label = "BRIGHTNESS WRITE", value = "WRITE_SETTINGS")
            ReadoutRow(label = "AUTO-ROTATE", value = "NOT EXPOSED")
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Since Android 10, apps cannot programmatically toggle Wi-Fi or Bluetooth; " +
                    "even system apps use panels. Settings writes need WRITE_SETTINGS, which is a " +
                    "special access grant. JARVIS could ask for it, but it is a broad permission " +
                    "for a small gain — so JARVIS opens the panel instead.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

/**
 * One interruption mode. When [mode] is null this row is the "grant access" affordance, which is
 * why it takes a nullable mode instead of a second composable.
 */
@Composable
private fun ModeButton(
    mode: DndControl.Mode?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        !enabled -> TextMuted
        selected -> StatusGreen
        else -> ElectricCyan
    }
    val label = mode?.label ?: "GRANT DO NOT DISTURB ACCESS"
    val caption = mode?.note ?: "Opens the system screen that allows direct control"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.14f) else PanelNavy.copy(alpha = 0.7f), ChipShape)
            .border(1.dp, accent.copy(alpha = if (selected) 0.6f else 0.22f), ChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (mode == null) Icons.Outlined.OpenInNew else Icons.Outlined.DoNotDisturbOn,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                color = if (enabled) TextPrimary else TextMuted,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = StatusGreen,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ControlTile(
    icon: ImageVector,
    label: String,
    state: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(110),
        label = "control-tile",
    )
    val accent = when {
        !enabled -> TextMuted
        active -> StatusGreen
        else -> ElectricCyan
    }

    Column(
        modifier = modifier
            .aspectRatio(0.95f)
            .scale(scale)
            .background(
                if (active) accent.copy(alpha = 0.14f) else PanelNavy.copy(alpha = 0.8f),
                TileShape,
            )
            .border(1.dp, accent.copy(alpha = if (active) 0.65f else 0.2f), TileShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accent,
            modifier = Modifier.size(19.dp),
        )
        Column {
            Text(
                text = label,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = TextPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = state,
                style = HudType.label.copy(fontSize = 8.sp),
                color = accent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = if (highlighted) ElectricCyan else IceBlue

    Box(
        modifier = modifier
            .height(46.dp)
            .background(
                if (pressed) accent.copy(alpha = 0.24f) else accent.copy(alpha = 0.10f),
                ChipShape,
            )
            .border(1.dp, accent.copy(alpha = 0.38f), ChipShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun SystemRow(
    icon: ImageVector,
    label: String,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1826).copy(alpha = 0.7f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.12f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = IceBlue.copy(alpha = 0.8f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = HudType.value.copy(fontSize = 11.sp),
                color = TextPrimary,
                maxLines = 1,
            )
            Text(
                text = caption.uppercase(),
                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                color = TextMuted,
                maxLines = 1,
            )
        }
        Text(
            text = "OPEN",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
            color = ElectricCyan.copy(alpha = 0.8f),
        )
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun PillButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.14f), ChipShape)
            .border(1.dp, accent.copy(alpha = 0.5f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
            color = accent,
        )
    }
}
