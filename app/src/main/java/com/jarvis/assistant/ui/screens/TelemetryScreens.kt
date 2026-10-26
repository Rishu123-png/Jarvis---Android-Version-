package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.device.DeviceInfo
import com.jarvis.assistant.device.StorageStatus
import com.jarvis.assistant.device.SystemStatus
import com.jarvis.assistant.ui.components.ClockSnapshot
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudMeter
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// POWER
// ---------------------------------------------------------------------------------------------

@Composable
fun PowerScreen(status: SystemStatus) {
    val battery = status.battery
    val levelColor = when {
        battery.isCharging -> StatusGreen
        battery.percent <= 15 -> AlertRed
        else -> ElectricCyan
    }

    HudPanel(label = "CELL STATUS", accent = levelColor) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${battery.percent}",
                style = HudType.clock.copy(fontSize = 48.sp),
                color = TextPrimary,
            )
            Text(
                text = "%",
                style = HudType.value.copy(fontSize = 18.sp),
                color = levelColor,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                HudChip(
                    text = battery.statusLabel,
                    accent = levelColor,
                    filled = true,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = levelColor, diameter = 5)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "SRC ${battery.plugLabel}",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = TextMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HudMeter(progress = battery.percent / 100f, accent = levelColor, height = 8.dp)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0%", style = HudType.label.copy(fontSize = 8.sp), color = TextMuted)
            Text("50%", style = HudType.label.copy(fontSize = 8.sp), color = TextMuted)
            Text("100%", style = HudType.label.copy(fontSize = 8.sp), color = TextMuted)
        }
    }

    HudPanel(label = "CELL DETAIL") {
        ReadoutRow(label = "STATE", value = battery.statusLabel)
        ReadoutRow(label = "POWER SOURCE", value = battery.plugLabel)
        ReadoutRow(
            label = "HEALTH",
            value = battery.healthLabel,
            valueColor = if (battery.healthLabel == "GOOD") StatusGreen else AlertRed,
        )
        ReadoutRow(
            label = "TEMPERATURE",
            value = String.format(Locale.US, "%.1f °C", battery.temperatureC),
        )
        ReadoutRow(label = "VOLTAGE", value = "${battery.voltageMv} mV")
        ReadoutRow(label = "TECHNOLOGY", value = battery.technology)
        HudDivider(modifier = Modifier.padding(vertical = 8.dp), accent = levelColor)
        Text(
            text = "Read from the ACTION_BATTERY_CHANGED system broadcast. " +
                "JARVIS only reads this data — nothing is changed.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// NETWORK
// ---------------------------------------------------------------------------------------------

@Composable
fun NetworkScreen(status: SystemStatus) {
    val network = status.network
    val accent = if (network.connected) ElectricCyan else AlertRed

    HudPanel(label = "LINK STATUS", accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = network.transport,
                    style = HudType.clock.copy(fontSize = 30.sp),
                    color = TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (network.connected) "ACTIVE LINK DETECTED" else "NO ACTIVE LINK",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                    color = accent.copy(alpha = 0.9f),
                )
            }
            PulseDot(color = accent, diameter = 10)
        }
        Spacer(Modifier.height(12.dp))
        HudMeter(
            progress = if (network.connected) 1f else 0f,
            accent = accent,
            height = 6.dp,
        )
    }

    HudPanel(label = "CAPABILITIES") {
        ReadoutRow(
            label = "TRANSPORT",
            value = network.transport,
            valueColor = accent,
        )
        ReadoutRow(
            label = "STATE",
            value = if (network.connected) "CONNECTED" else "OFFLINE",
        )
        ReadoutRow(
            label = "INTERNET",
            value = if (network.validated) "VALIDATED" else "UNVERIFIED",
            valueColor = if (network.validated) StatusGreen else TextSecondary,
        )
        ReadoutRow(
            label = "DOWNSTREAM",
            value = if (network.downstreamKbps > 0) {
                String.format(Locale.US, "%.1f Mbps", network.downstreamKbps / 1000f)
            } else {
                "—"
            },
        )
        HudDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "Capabilities queried through ConnectivityManager. " +
                "Per-app and SSID-level detail requires the location permission and is not " +
                "requested in phase 1.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
    }

    HudPanel(label = "NEXT IN THIS MODULE", accent = IceBlue) {
        Text(
            text = "PHASE 3 adds notification listening, per-app data awareness and supported " +
                "quick toggles, each behind its own explicit permission request.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// DIAGNOSTICS
// ---------------------------------------------------------------------------------------------

@Composable
fun DiagnosticsScreen(
    status: SystemStatus,
    device: DeviceInfo,
    clock: ClockSnapshot,
    storage: StorageStatus,
) {
    val memory = status.memory

    HudPanel(label = "DEVICE") {
        ReadoutRow(label = "MODEL", value = device.model)
        ReadoutRow(label = "MAKER", value = device.manufacturer.uppercase(Locale.US))
        ReadoutRow(label = "ANDROID", value = device.androidRelease)
        ReadoutRow(label = "API LEVEL", value = device.sdkInt.toString())
        ReadoutRow(label = "ABI", value = device.abi)
    }

    HudPanel(label = "MEMORY", accent = IceBlue) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = String.format(Locale.US, "%.1f", memory.usedGb),
                style = HudType.clock.copy(fontSize = 30.sp),
                color = TextPrimary,
            )
            Text(
                text = " / ${String.format(Locale.US, "%.1f", memory.totalGb)} GB",
                style = HudType.value,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${memory.percent}% USED",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = IceBlue,
            )
        }
        Spacer(Modifier.height(10.dp))
        HudMeter(progress = memory.percent / 100f, accent = IceBlue, height = 6.dp)
    }

    HudPanel(label = "STORAGE", accent = StatusGreen) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = storage.usedLabel,
                style = HudType.clock.copy(fontSize = 26.sp),
                color = TextPrimary,
            )
            Text(
                text = " USED OF ${storage.totalLabel}",
                style = HudType.value,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        HudMeter(progress = storage.percentUsed / 100f, accent = StatusGreen, height = 6.dp)
        Spacer(Modifier.height(6.dp))
        ReadoutRow(label = "FREE", value = storage.freeLabel)
        ReadoutRow(label = "USED", value = storage.usedLabel)
        ReadoutRow(
            label = "HEADROOM",
            value = if (storage.percentUsed >= 90) "LOW" else "NOMINAL",
            valueColor = if (storage.percentUsed >= 90) AlertRed else StatusGreen,
        )
    }

    HudPanel(label = "RUNTIME") {
        ReadoutRow(label = "UPTIME", value = status.uptimeLabel)
        ReadoutRow(label = "LOCAL TIME", value = "${clock.time}:${clock.seconds}")
        ReadoutRow(label = "DATE", value = clock.date)
        ReadoutRow(label = "REFRESH", value = "1 Hz CLOCK / 2 s TELEMETRY")
        ReadoutRow(label = "RENDER", value = "COMPOSE · GPU CANVAS")
    }

    HudPanel(label = "MODULE MATRIX", accent = StatusGreen) {
        ReadoutRow(label = "HUD + THEME", value = "PHASE 1 · LIVE", valueColor = StatusGreen)
        ReadoutRow(label = "LAUNCHER", value = "PHASE 2 · LIVE", valueColor = StatusGreen)
        ReadoutRow(label = "DEVICE CONTROL", value = "PHASE 3 · LIVE", valueColor = StatusGreen)
        ReadoutRow(label = "VOICE", value = "PHASE 4 · STAGED", valueColor = TextMuted)
        ReadoutRow(label = "AI CORE", value = "PHASE 5 · STAGED", valueColor = TextMuted)
        ReadoutRow(label = "AUTOMATION", value = "PHASE 6 · LIVE", valueColor = StatusGreen)
    }
}
