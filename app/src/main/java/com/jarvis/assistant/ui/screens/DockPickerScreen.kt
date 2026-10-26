package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.DockPrefs
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.ui.components.AppIcon
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary

/**
 * Dock editor.
 *
 * Choose which slot to change, then pick the app that fills it. Choices persist in SharedPreferences
 * via [DockPrefs]; *Reset to auto* returns to the recognised-system-app heuristic.
 */
@Composable
fun DockPickerScreen(
    apps: List<InstalledApp>,
    currentDock: List<InstalledApp>,
    isCustom: Boolean,
    initialSlot: Int,
    onPick: (slot: Int, app: InstalledApp?) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedSlot by remember { mutableStateOf(initialSlot.coerceIn(0, DockPrefs.SLOT_COUNT - 1)) }

    ModuleScaffold(
        title = "DOCK",
        subtitle = "edit quick access",
        accent = ElectricCyan,
        phaseLabel = "P3",
        onBack = onBack,
    ) {
        HudPanel(
            label = "SLOTS",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = if (isCustom) ElectricCyan else StatusGreen, diameter = 4)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isCustom) "CUSTOM" else "AUTO",
                        style = HudType.label.copy(fontSize = 8.sp),
                        color = if (isCustom) ElectricCyan else StatusGreen,
                    )
                }
            },
        ) {
            Text(
                text = "Select a slot, then choose the app that should fill it.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                repeat(DockPrefs.SLOT_COUNT) { index ->
                    val app = currentDock.getOrNull(index)
                    val selected = index == selectedSlot
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (selected) ElectricCyan.copy(alpha = 0.14f)
                                else PanelNavy.copy(alpha = 0.7f),
                                ChipShape,
                            )
                            .border(
                                1.dp,
                                if (selected) ElectricCyan.copy(alpha = 0.7f)
                                else ElectricCyan.copy(alpha = 0.15f),
                                ChipShape,
                            )
                            .clickable { selectedSlot = index }
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (app != null) {
                            AppIcon(app = app, size = 26.dp)
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .background(PanelNavy, ChipShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+",
                                    style = HudType.value,
                                    color = TextMuted,
                                )
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = app?.label?.take(8)?.uppercase() ?: "EMPTY",
                            style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.4.sp),
                            color = if (selected) ElectricCyan else TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(TextMuted.copy(alpha = 0.12f), ChipShape)
                        .border(1.dp, TextMuted.copy(alpha = 0.35f), ChipShape)
                        .clickable { onPick(selectedSlot, null) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "CLEAR SLOT ${selectedSlot + 1}",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = TextMuted,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.35f), ChipShape)
                        .clickable(onClick = onReset)
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "RESET TO AUTO",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = ElectricCyan,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FILL SLOT ${selectedSlot + 1} WITH",
                style = HudType.sectionTitle,
                color = ElectricCyan.copy(alpha = 0.9f),
            )
            Spacer(Modifier.weight(1f))
            HudChip(text = "${apps.size} APPS", accent = TextMuted)
        }

        // Fixed height so the whole editor stays scrollable inside the module scaffold.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items = apps, key = { it.key }) { app ->
                val alreadyInSlot = currentDock.indexOfFirst { it.key == app.key }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PanelNavy.copy(alpha = 0.6f), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.10f), ChipShape)
                        .clickable { onPick(selectedSlot, app) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app = app, size = 26.dp)
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = HudType.value.copy(fontSize = 12.sp),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = app.packageName,
                            style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.4.sp),
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (alreadyInSlot >= 0) {
                        Text(
                            text = "SLOT ${alreadyInSlot + 1}",
                            style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                            color = ElectricCyan.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        HudPanel(label = "NOTE", accent = TextMuted) {
            ReadoutRow(label = "STORAGE", value = "SHARED PREFERENCES")
            ReadoutRow(label = "SCOPE", value = "THIS DEVICE ONLY", valueColor = StatusGreen)
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Picks are matched by package and activity. If an app is uninstalled, its " +
                    "slot falls back to auto-fill rather than leaving a hole.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
        }
    }
}
