package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.BootSequence
import com.jarvis.assistant.device.DeviceInfo
import com.jarvis.assistant.data.HudModule
import com.jarvis.assistant.data.HudModules
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.device.StorageStatus
import com.jarvis.assistant.device.SystemStatus
import com.jarvis.assistant.ui.components.AppDock
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.components.BlinkingCursor
import com.jarvis.assistant.ui.components.ClockSnapshot
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.HudTile
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.components.SectionHeader
import com.jarvis.assistant.ui.components.TelemetryStat
import com.jarvis.assistant.ui.components.Typewriter
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.CyanDeep
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * The JARVIS home dashboard.
 *
 * Reading order is deliberate: identity -> reactor (the hero) -> telemetry strip -> modules ->
 * system log -> voice affordance. Everything above the fold is live data; everything that is
 * not live yet says so.
 */
@Composable
fun DashboardScreen(
    clock: ClockSnapshot,
    status: SystemStatus,
    storage: StorageStatus,
    device: DeviceInfo,
    dockApps: List<InstalledApp>,
    appCount: Int,
    isHomeApp: Boolean,
    notificationCount: Int,
    isDockCustom: Boolean,
    onLaunchApp: (InstalledApp) -> Unit,
    onEditDock: (slot: Int) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModule: (HudModule) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    var statusNoteIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_500)
            statusNoteIndex = (statusNoteIndex + 1) % BootSequence.statusNotes.size
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "identity") {
            IdentityBar(
                clock = clock,
                status = status,
                device = device,
                isHomeApp = isHomeApp,
                onOpenSettings = onOpenSettings,
            )
        }

        item(key = "reactor") {
            ReactorBlock(
                clock = clock,
                status = status,
                statusNote = BootSequence.statusNotes[statusNoteIndex],
            )
        }

        item(key = "stats") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TelemetryStat(
                    label = "BATTERY",
                    value = "${status.battery.percent}%",
                    accent = when {
                        status.battery.isCharging -> StatusGreen
                        status.battery.percent <= 15 -> AlertRed
                        else -> ElectricCyan
                    },
                    progress = status.battery.percent / 100f,
                    modifier = Modifier.weight(1f),
                )
                TelemetryStat(
                    label = "LINK",
                    value = status.network.transport,
                    accent = if (status.network.connected) ElectricCyan else AlertRed,
                    caption = if (status.network.validated) "VALIDATED" else "UNVERIFIED",
                    modifier = Modifier.weight(1f),
                )
                TelemetryStat(
                    label = "MEMORY",
                    value = "${status.memory.percent}%",
                    accent = IceBlue,
                    progress = status.memory.percent / 100f,
                    modifier = Modifier.weight(1f),
                )
                TelemetryStat(
                    label = "STORAGE",
                    value = "${storage.percentUsed}%",
                    accent = if (storage.percentUsed >= 90) AlertRed else TextSecondary,
                    progress = storage.percentUsed / 100f,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "dock") {
            DockBlock(
                dockApps = dockApps,
                appCount = appCount,
                isDockCustom = isDockCustom,
                onLaunchApp = onLaunchApp,
                onEditDock = onEditDock,
                onOpenDrawer = onOpenDrawer,
            )
        }

        item(key = "modules-header") {
            SectionHeader(
                title = "SYSTEM MODULES",
                modifier = Modifier.padding(top = 4.dp),
                trailing = "${HudModules.all.count { it.isLive }}/${HudModules.all.size} LIVE",
            )
        }

        items(
            items = HudModules.all.chunked(2),
            key = { row -> row.joinToString("-") { it.id } },
        ) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { module ->
                    HudTile(
                        module = module,
                        onClick = { onOpenModule(module) },
                        modifier = Modifier.weight(1f),
                        badge = if (module.id == HudModules.NOTIFICATIONS && notificationCount > 0) {
                            notificationCount.coerceAtMost(99).toString()
                        } else {
                            null
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item(key = "log") {
            HudPanel(
                label = "SYSTEM LOG",
                accent = ElectricCyan,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(color = StatusGreen, diameter = 4)
                        Spacer(Modifier.width(5.dp))
                        Text("LIVE", style = HudType.label, color = StatusGreen)
                    }
                },
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(166.dp)) {
                    Typewriter(
                        lines = BootSequence.lines,
                        modifier = Modifier.fillMaxWidth(),
                        color = TextSecondary,
                        charsPerSecond = 90,
                        startDelayMillis = 350L,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(">", style = HudType.terminal, color = ElectricCyan)
                    Spacer(Modifier.width(6.dp))
                    BlinkingCursor(modifier = Modifier.padding(top = 1.dp))
                    Spacer(Modifier.weight(1f))
                    TerminalLink(onClick = onOpenTerminal)
                }
            }
        }

        item(key = "voice") {
            VoiceBar(onClick = { onOpenModule(HudModules.all.first { it.id == HudModules.VOICE }) })
        }

        item(key = "footer") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "J.A.R.V.I.S. · CORE INTERFACE",
                    style = HudType.label,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "JUST A RATHER VERY INTELLIGENT SYSTEM",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 2.2.sp),
                    color = TextMuted.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun IdentityBar(
    clock: ClockSnapshot,
    status: SystemStatus,
    device: DeviceInfo,
    isHomeApp: Boolean,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "J.A.R.V.I.S.",
                    style = HudType.value.copy(fontSize = 17.sp, letterSpacing = 4.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(ElectricCyan, CircleShape)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = device.displayName.uppercase() + " · ANDROID " + device.androidRelease,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.5.sp),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudChip(
                    text = if (isHomeApp) "HOME" else "LAUNCHER",
                    accent = if (isHomeApp) StatusGreen else ElectricCyan,
                    filled = isHomeApp,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color(0xFF0A1826), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.22f), ChipShape)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Launcher settings",
                        tint = ElectricCyan.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = clock.labelWithSeconds,
                style = HudType.valueSmall,
                color = TextSecondary,
            )
            Text(
                text = if (status.network.connected) "CORE ONLINE" else "CORE LOCAL",
                style = HudType.label.copy(fontSize = 8.sp),
                color = if (status.network.connected) StatusGreen else TextMuted,
            )
        }
    }
}

@Composable
private fun ReactorBlock(
    clock: ClockSnapshot,
    status: SystemStatus,
    statusNote: String,
) {
    val battery = status.battery
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val diameter: Dp = minOf(maxWidth * 0.86f, 320.dp)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ArcReactor(
                diameter = diameter,
                powerLevel = battery.percent,
                isCharging = battery.isCharging,
                accent = ElectricCyan,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(text = clock.time, style = HudType.clock, color = TextPrimary)
                        Column(modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                            Text(
                                text = clock.meridiem,
                                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
                                color = IceBlue.copy(alpha = 0.85f),
                            )
                            Text(
                                text = clock.seconds,
                                style = HudType.valueSmall.copy(fontSize = 11.sp),
                                color = TextMuted,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = clock.date,
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(
                            color = if (battery.isCharging) StatusGreen else ElectricCyan,
                            diameter = 4,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "POWER ${battery.percent}%",
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                            color = if (battery.isCharging) StatusGreen else IceBlue,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Status ticker under the reactor.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xFF0A1826).copy(alpha = 0.85f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.18f), ChipShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                PulseDot(color = ElectricCyan, diameter = 5)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = statusNote,
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 2.sp),
                    color = IceBlue.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun TerminalLink(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.35f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "TERMINAL",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
            color = ElectricCyan,
        )
    }
}

@Composable
private fun DockBlock(
    dockApps: List<InstalledApp>,
    appCount: Int,
    isDockCustom: Boolean,
    onLaunchApp: (InstalledApp) -> Unit,
    onEditDock: (slot: Int) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "QUICK ACCESS",
                style = HudType.sectionTitle,
                color = ElectricCyan.copy(alpha = 0.9f),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .background(Color(0xFF0A1826), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.2f), ChipShape)
                    .clickable { onEditDock(0) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit dock",
                    tint = ElectricCyan.copy(alpha = 0.8f),
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isDockCustom) "CUSTOM" else "AUTO",
                    style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                    color = if (isDockCustom) ElectricCyan else TextMuted,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.35f), ChipShape)
                    .clickable(onClick = onOpenDrawer)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "ALL APPS · $appCount",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                    color = ElectricCyan,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        AppDock(
            apps = dockApps,
            onLaunch = onLaunchApp,
            onLongPress = { index -> onEditDock(index) },
        )
    }
}

@Composable
private fun VoiceBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF0A1A28),
                        Color(0xFF081420),
                        Color(0xFF0A1A28),
                    )
                ),
                ChipShape,
            )
            .border(1.dp, CyanDeep.copy(alpha = 0.55f), ChipShape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(ElectricCyan.copy(alpha = 0.10f), CircleShape)
                .border(1.dp, ElectricCyan.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = "Voice interface",
                tint = ElectricCyan.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "VOICE INTERFACE",
                style = HudType.value,
                color = TextPrimary,
            )
            Text(
                text = "Tap the orb, speak, tap again",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.8.sp),
                color = TextMuted,
            )
        }
        HudChip(text = "LIVE", accent = StatusGreen)
    }
}
