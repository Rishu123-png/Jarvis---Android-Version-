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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.automation.RunRecord
import com.jarvis.assistant.automation.RunState
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudMeter
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
 * Automation control surface.
 *
 * Four concerns kept visually separate: the grant tiers (and their cost), whether triggers are
 * armed, the routines themselves with their own trigger and last result, and what actually ran.
 * Sensitive steps always stop for a decision before they act — including triggered runs.
 */
@Composable
fun AutomationScreen(
    workflows: List<Workflow>,
    runState: RunState,
    accessibilityEnabled: Boolean,
    screenPressAllowed: Boolean,
    notificationAccess: Boolean,
    armed: Boolean,
    exactAlarmsAllowed: Boolean,
    notificationsAllowed: Boolean,
    history: List<RunRecord>,
    onRun: (Workflow) -> Unit,
    onStop: () -> Unit,
    onDismissFinished: () -> Unit,
    onEdit: (Workflow) -> Unit,
    onNew: () -> Unit,
    onDelete: (Workflow) -> Unit,
    onToggleEnabled: (Workflow, Boolean) -> Unit,
    onArm: (Boolean) -> Unit,
    onRequestExactAlarms: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onCompose: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onToggleScreenPress: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onActivateAll: () -> Unit,
    onBack: () -> Unit,
) {
    val armedCount = workflows.count { it.isArmed }
    val busy = runState is RunState.Running || runState is RunState.AwaitingConfirmation

    ModuleScaffold(
        title = "AUTOMATION",
        subtitle = "routines · triggers · control flow",
        accent = AlertRed,
        statusLabel = if (armed) "ARMED" else "SAFE",
        onBack = onBack,
    ) {
        // ---------------- authoring ----------------
        HudPanel(label = "COMPOSE WITH AI", accent = IceBlue) {
            Text(
                text = "Describe the routine in a sentence. The model proposes a plan; JARVIS " +
                    "checks every action against what it can actually do, then shows it to you " +
                    "before anything is saved.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            ActionPill(
                label = "DESCRIBE A ROUTINE",
                accent = IceBlue,
                filled = true,
                onClick = onCompose,
            )
        }

        // ---------------- triggers ----------------
        HudPanel(
            label = "TRIGGERS",
            accent = if (armed) StatusGreen else TextMuted,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = if (armed) StatusGreen else TextMuted, diameter = 4)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (armed) "ARMED" else "OFF",
                        style = HudType.label,
                        color = if (armed) StatusGreen else TextMuted,
                    )
                }
            },
        ) {
            ActionPill(
                label = if (armed) "DISARM ALL TRIGGERS" else "ARM TRIGGERS",
                accent = if (armed) AlertRed else StatusGreen,
                filled = !armed,
                onClick = { onArm(!armed) },
            )
            Spacer(Modifier.height(8.dp))
            ReadoutRow(
                label = "ROUTINES WITH A TRIGGER",
                value = "$armedCount OF ${workflows.size}",
                valueColor = if (armedCount == 0) TextMuted else TextPrimary,
            )
            ReadoutRow(
                label = "TIME ACCURACY",
                value = if (exactAlarmsAllowed) "EXACT" else "APPROXIMATE",
                valueColor = if (exactAlarmsAllowed) StatusGreen else Amber,
            )
            if (!exactAlarmsAllowed) {
                Spacer(Modifier.height(8.dp))
                ActionPill(
                    label = "ALLOW EXACT ALARMS",
                    accent = Amber,
                    filled = false,
                    onClick = onRequestExactAlarms,
                )
            }
            ReadoutRow(
                label = "BACKGROUND NOTIFICATION",
                value = if (notificationsAllowed) "ALLOWED" else "BLOCKED",
                valueColor = if (notificationsAllowed) StatusGreen else Amber,
            )
            if (!notificationsAllowed) {
                Spacer(Modifier.height(8.dp))
                ActionPill(
                    label = "ALLOW NOTIFICATIONS",
                    accent = Amber,
                    filled = false,
                    onClick = onRequestNotificationPermission,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Armed triggers need a background service, and Android requires a quiet " +
                    "persistent notification while it runs — that is what you see in the shade. " +
                    "Time triggers can drift a few minutes inside Doze unless exact alarms are " +
                    "allowed above. Disarming removes the service and the notification.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }

        // ---------------- access ----------------
        HudPanel(
            label = "AUTOMATION ACCESS",
            accent = if (accessibilityEnabled) StatusGreen else Amber,
        ) {
            AccessRow(
                title = "ACCESSIBILITY SERVICE",
                value = if (accessibilityEnabled) "ENABLED" else "DISABLED",
                ok = accessibilityEnabled,
                caption = if (accessibilityEnabled) {
                    "System gestures are available"
                } else {
                    "Required for back / home / recents gestures"
                },
            )
            Spacer(Modifier.height(8.dp))
            AccessRow(
                title = "PRESS ON SCREEN",
                value = if (screenPressAllowed) "ALLOWED" else "BLOCKED",
                ok = screenPressAllowed,
                caption = if (screenPressAllowed) {
                    "Routines may tap labelled controls, with confirmation"
                } else {
                    "Optional second tier — off unless you enable it"
                },
            )
            Spacer(Modifier.height(8.dp))
            AccessRow(
                title = "NOTIFICATIONS",
                value = if (notificationAccess) "GRANTED" else "NOT GRANTED",
                ok = notificationAccess,
                caption = "Needed by media and clear-alert steps",
            )

            Spacer(Modifier.height(12.dp))

            val allGranted = accessibilityEnabled && screenPressAllowed && notificationAccess
            ActionPill(
                label = when {
                    allGranted -> "ALL ACCESS ACTIVE"
                    accessibilityEnabled -> "ACTIVATE REMAINING ACCESS"
                    else -> "ACTIVATE ALL ACCESS"
                },
                accent = if (allGranted) StatusGreen else ElectricCyan,
                filled = !allGranted,
                onClick = { if (!allGranted) onActivateAll() },
            )
            Spacer(Modifier.height(8.dp))

            if (!accessibilityEnabled) {
                ActionPill(
                    label = "ENABLE IN SYSTEM SETTINGS",
                    accent = Amber,
                    filled = false,
                    onClick = onOpenAccessibility,
                )
                Spacer(Modifier.height(8.dp))
            }

            ActionPill(
                label = if (screenPressAllowed) {
                    "DISALLOW SCREEN PRESSING"
                } else {
                    "ALLOW SCREEN PRESSING"
                },
                accent = if (screenPressAllowed) AlertRed else IceBlue,
                filled = false,
                onClick = { onToggleScreenPress(!screenPressAllowed) },
            )

            if (!notificationAccess) {
                Spacer(Modifier.height(8.dp))
                ActionPill(
                    label = "OPEN NOTIFICATION ACCESS",
                    accent = IceBlue,
                    filled = false,
                    onClick = onOpenNotificationAccess,
                )
            }
        }

        // ---------------- live run ----------------
        when (val state = runState) {
            is RunState.Idle -> Unit

            is RunState.Running -> HudPanel(
                label = "RUNNING",
                accent = ElectricCyan,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(color = ElectricCyan, diameter = 4)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "${state.stepIndex}/${state.stepCount}",
                            style = HudType.label.copy(fontSize = 8.sp),
                            color = ElectricCyan,
                        )
                    }
                },
            ) {
                Text(
                    text = state.workflowName,
                    style = HudType.value.copy(fontSize = 13.sp),
                    color = TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (state.depth > 0) {
                        "${state.currentAction} · nested level ${state.depth}"
                    } else {
                        state.currentAction
                    },
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                    color = IceBlue,
                )
                Spacer(Modifier.height(10.dp))
                HudMeter(
                    progress = if (state.stepCount == 0) {
                        0f
                    } else {
                        (state.stepIndex.toFloat() / state.stepCount).coerceIn(0f, 1f)
                    },
                    accent = ElectricCyan,
                    height = 5.dp,
                )
                Spacer(Modifier.height(12.dp))
                RunLog(log = state.log, maxLines = 8)
                Spacer(Modifier.height(12.dp))
                ActionPill(label = "STOP ROUTINE", accent = AlertRed, filled = true, onClick = onStop)
            }

            is RunState.AwaitingConfirmation -> HudPanel(
                label = "CONFIRMATION REQUIRED",
                accent = Amber,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = Amber, diameter = 5)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "SENSITIVE STEP HELD",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                        color = Amber,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.actionLabel,
                    style = HudType.value.copy(fontSize = 14.sp, letterSpacing = 1.2.sp),
                    color = TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Target: ${state.detail}",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Step ${state.stepIndex + 1} of ${state.stepCount} in ${state.workflowName}. " +
                        "Nothing has happened yet.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Approve or decline on the confirmation card.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = Amber,
                )
            }

            is RunState.Finished -> HudPanel(
                label = "RUN COMPLETE",
                accent = if (state.stopped) AlertRed else StatusGreen,
                trailing = {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0B1929), ChipShape)
                            .border(1.dp, TextMuted.copy(alpha = 0.4f), ChipShape)
                            .clickable(onClick = onDismissFinished)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "CLEAR",
                            style = HudType.label.copy(fontSize = 8.sp),
                            color = TextMuted,
                        )
                    }
                },
            ) {
                Text(
                    text = state.workflowName,
                    style = HudType.value.copy(fontSize = 13.sp),
                    color = TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("${state.succeeded} ok · ${state.failed} failed")
                        if (state.skipped > 0) append(" · ${state.skipped} skipped")
                        if (state.stopped) append(" · halted")
                    },
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                    color = if (state.stopped || state.failed > 0) AlertRed else StatusGreen,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Started by: ${state.triggerLabel}",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                RunLog(log = state.log, maxLines = 10)
            }
        }

        // ---------------- routines ----------------
        HudPanel(label = "ROUTINES · ${workflows.size}", accent = IceBlue) {
            if (workflows.isEmpty()) {
                Text(
                    text = "No routines yet. Describe one above, or build it by hand.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            }
            workflows.forEach { workflow ->
                RoutineCard(
                    workflow = workflow,
                    busy = busy,
                    lastRun = history.firstOrNull { it.workflowId == workflow.id },
                    onRun = { onRun(workflow) },
                    onEdit = { onEdit(workflow) },
                    onDelete = { onDelete(workflow) },
                    onToggleEnabled = { enabled -> onToggleEnabled(workflow, enabled) },
                )
                Spacer(Modifier.height(8.dp))
            }

            ActionPill(
                label = "+ NEW ROUTINE",
                accent = ElectricCyan,
                filled = false,
                onClick = onNew,
            )
        }

        // ---------------- history ----------------
        HudPanel(
            label = "RUN HISTORY · ${history.size}",
            accent = TextMuted,
            trailing = if (history.isEmpty()) {
                null
            } else {
                {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF0B1929), ChipShape)
                            .border(1.dp, TextMuted.copy(alpha = 0.4f), ChipShape)
                            .clickable(onClick = onClearHistory)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "CLEAR",
                            style = HudType.label.copy(fontSize = 8.sp),
                            color = TextMuted,
                        )
                    }
                }
            },
        ) {
            if (history.isEmpty()) {
                Text(
                    text = "Nothing has run yet. History is kept for the last 50 runs, whether " +
                        "they were started by you or by a trigger.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            } else {
                history.take(8).forEach { record ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (record.ok) "OK" else "FAIL",
                            style = HudType.label.copy(fontSize = 8.sp),
                            color = if (record.ok) StatusGreen else AlertRed,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = record.workflowName,
                                style = HudType.value.copy(fontSize = 11.sp),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${stamp(record.at)} · ${record.triggerLabel} · " +
                                    "${record.succeeded} ok / ${record.failed} failed",
                                style = HudType.terminal.copy(fontSize = 9.sp),
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        HudPanel(label = "SCOPE", accent = TextMuted) {
            ReadoutRow(label = "PER RUN", value = "60 STEPS · 3 NESTING LEVELS")
            ReadoutRow(label = "CONCURRENCY", value = "ONE AT A TIME")
            ReadoutRow(label = "COOLDOWN", value = "5 MIN PER ROUTINE")
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "A trigger never skips a confirmation. If a triggered routine reaches a " +
                    "sensitive step, it waits for you and declines itself after five minutes.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

private fun stamp(at: Long): String =
    SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(at))

@Composable
private fun RoutineCard(
    workflow: Workflow,
    busy: Boolean,
    lastRun: RunRecord?,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelNavy.copy(alpha = 0.65f), TileShape)
            .border(
                1.dp,
                when {
                    !workflow.enabled -> TextMuted.copy(alpha = 0.25f)
                    workflow.trigger.kind.isSilent -> ElectricCyan.copy(alpha = 0.18f)
                    else -> StatusGreen.copy(alpha = 0.28f)
                },
                TileShape,
            )
            .padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workflow.name,
                    style = HudType.value.copy(fontSize = 13.sp),
                    color = if (workflow.enabled) TextPrimary else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(workflow.trigger.display)
                        append(" · ${workflow.totalSteps} steps")
                        if (workflow.sensitiveCount > 0) append(" · ${workflow.sensitiveCount} confirm")
                    },
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.6.sp),
                    color = if (workflow.isArmed) StatusGreen else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (workflow.sensitiveCount > 0) {
                HudChip(text = "GATED", accent = Amber)
                Spacer(Modifier.width(6.dp))
            }
            HudChip(
                text = if (workflow.enabled) "ON" else "OFF",
                accent = if (workflow.enabled) StatusGreen else TextMuted,
                filled = workflow.enabled,
            )
        }

        if (lastRun != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "last ${stamp(lastRun.at)} · ${if (lastRun.ok) "ok" else "failed"} · ${lastRun.triggerLabel}",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = if (lastRun.ok) TextMuted else Color(0xFFFF8A98),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallAction("RUN", StatusGreen, enabled = !busy, onClick = onRun)
            SmallAction("EDIT", ElectricCyan, enabled = true, onClick = onEdit)
            SmallAction(
                if (workflow.enabled) "DISABLE" else "ENABLE",
                if (workflow.enabled) TextMuted else StatusGreen,
                enabled = true,
                onClick = { onToggleEnabled(!workflow.enabled) },
            )
            SmallAction("DELETE", AlertRed, enabled = true, onClick = onDelete)
        }
    }
}

@Composable
private fun SmallAction(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = if (enabled) 0.10f else 0.04f), ChipShape)
            .border(1.dp, accent.copy(alpha = if (enabled) 0.35f else 0.12f), ChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
            color = if (enabled) accent else TextMuted,
        )
    }
}

@Composable
private fun AccessRow(
    title: String,
    value: String,
    ok: Boolean,
    caption: String,
) {
    val accent = if (ok) StatusGreen else Amber
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(accent),
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = HudType.value.copy(fontSize = 11.sp),
                color = TextPrimary,
            )
            Text(
                text = caption,
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
            )
        }
        Text(
            text = value,
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
            color = accent,
        )
    }
}

@Composable
private fun RunLog(log: List<com.jarvis.assistant.automation.RunLogEntry>, maxLines: Int) {
    if (log.isEmpty()) {
        Text(
            text = "waiting for the first step…",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
        return
    }
    log.takeLast(maxLines).forEach { entry ->
        Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text(
                text = entry.timeLabel,
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (entry.ok) "·" else "×",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = if (entry.ok) StatusGreen else AlertRed,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${entry.actionLabel}: ${entry.outcome}",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = if (entry.ok) TextSecondary else Color(0xFFFF8A98),
            )
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = if (filled) 0.16f else 0.07f), ChipShape)
            .border(1.dp, accent.copy(alpha = if (filled) 0.5f else 0.3f), ChipShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
            color = accent,
        )
    }
}
