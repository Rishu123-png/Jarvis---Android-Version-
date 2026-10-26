package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.device.JarvisNotification
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.AlertRed
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification panel.
 *
 * Three honest states: access never granted (explainer + one button), granted but idle, and granted
 * with a live list. Dismissal goes through the listener service; nothing is stored on disk.
 */
@Composable
fun NotificationsScreen(
    notifications: List<JarvisNotification>,
    hasAccess: Boolean,
    onEnableAccess: () -> Unit,
    onDismiss: (JarvisNotification) -> Unit,
    onDismissAll: () -> Unit,
    onBack: () -> Unit,
) {
    ModuleScaffold(
        title = "NOTIFICATIONS",
        subtitle = "notification listener",
        accent = if (hasAccess) ElectricCyan else Amber,
        phaseLabel = "P3",
        onBack = onBack,
    ) {
        if (!hasAccess) {
            AccessCard(onEnableAccess = onEnableAccess)
        }

        HudPanel(
            label = "ACTIVE",
            accent = if (hasAccess) ElectricCyan else TextMuted,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasAccess && notifications.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .background(ElectricCyan.copy(alpha = 0.14f), ChipShape)
                                .border(1.dp, ElectricCyan.copy(alpha = 0.4f), ChipShape)
                                .clickable(onClick = onDismissAll)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = "CLEAR ALL",
                                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                                color = ElectricCyan,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    PulseDot(
                        color = if (hasAccess) StatusGreen else TextMuted,
                        diameter = 4,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${notifications.size}",
                        style = HudType.label.copy(fontSize = 8.sp),
                        color = if (hasAccess) StatusGreen else TextMuted,
                    )
                }
            },
        ) {
            when {
                !hasAccess -> Text(
                    text = "No notification data. Enable access above and this panel fills with " +
                        "the notifications currently on your phone.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )

                notifications.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.7f),
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "SHADE IS CLEAR",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 2.sp),
                        color = TextMuted,
                    )
                }

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notifications.forEach { item ->
                        NotificationCard(item = item, onDismiss = { onDismiss(item) })
                    }
                }
            }
        }

        HudPanel(label = "SCOPE", accent = TextMuted) {
            ReadoutRow(
                label = "ACCESS",
                value = if (hasAccess) "GRANTED" else "NOT GRANTED",
                valueColor = if (hasAccess) StatusGreen else Amber,
            )
            ReadoutRow(label = "STORAGE", value = "IN MEMORY ONLY")
            ReadoutRow(label = "NETWORK", value = "NEVER UPLOADED", valueColor = StatusGreen)
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Notification text is read on-device so the HUD can display it. It is never " +
                    "written to disk, never sent anywhere, and disappears when access is revoked " +
                    "or the app is closed.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun AccessCard(onEnableAccess: () -> Unit) {
    HudPanel(label = "ACCESS REQUIRED", accent = Amber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NOTIFICATION ACCESS IS OFF",
                    style = HudType.value.copy(fontSize = 12.sp, letterSpacing = 1.2.sp),
                    color = TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Android requires you to enable this in system settings.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Amber.copy(alpha = 0.14f), ChipShape)
                .border(1.dp, Amber.copy(alpha = 0.6f), ChipShape)
                .clickable(onClick = onEnableAccess)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "OPEN NOTIFICATION ACCESS SETTINGS",
                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
                color = Amber,
            )
            Spacer(Modifier.weight(1f))
            HudChip(text = "SYSTEM", accent = Amber.copy(alpha = 0.7f))
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "In the list that opens, find JARVIS and turn it on. Come back here — the panel " +
                "picks it up immediately. This is the same switch Android uses for smartwatch and " +
                "car integrations.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
    }
}

@Composable
private fun NotificationCard(item: JarvisNotification, onDismiss: () -> Unit) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelNavy.copy(alpha = 0.65f), TileShape)
            .border(
                1.dp,
                if (item.isOngoing) IceBlue.copy(alpha = 0.3f) else ElectricCyan.copy(alpha = 0.16f),
                TileShape,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.appLabel.uppercase(Locale.US),
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.3.sp),
                    color = ElectricCyan.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = timeFormat.format(Date(item.postedAt)),
                    style = HudType.label.copy(fontSize = 8.sp),
                    color = TextMuted,
                )
                if (item.isOngoing) {
                    Spacer(Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(IceBlue),
                    )
                }
            }

            if (item.title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.title,
                    style = HudType.value.copy(fontSize = 12.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.text,
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (item.isClearable && !item.isOngoing) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(Color(0xFF10202F), ChipShape)
                    .border(1.dp, AlertRed.copy(alpha = 0.3f), ChipShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = AlertRed.copy(alpha = 0.85f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
