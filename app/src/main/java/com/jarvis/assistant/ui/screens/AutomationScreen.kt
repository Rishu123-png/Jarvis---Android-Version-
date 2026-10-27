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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Automation control surface.
 *
 * Three concerns kept visually separate: what access is granted (and its cost), the routines you can
 * run, and the live run log. Sensitive steps always stop here for a decision before they act.
 */
@Composable
fun AutomationScreen(
    workflows: List<Workflow>,
    runState: RunState,
    accessibilityEnabled: Boolean,
    screenPressAllowed: Boolean,
    notificationAccess: Boolean,
    onRun: (Workflow) -> Unit,
    onStop: () -> Unit,
    onDismissFinished: () -> Unit,
    onEdit: (Workflow) -> Unit,
    onNew: () -> Unit,
    onDelete: (Workflow) -> Unit,
    onOpenAccessibility: () -> Unit,
    onToggleScreenPress: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onActivateAll: () -> Unit,
    onBack: () -> Unit,
) {
    ModuleScaffold(
        title = "AUTOMATION",
        subtitle = "routine control",
        accent = AlertRed,
        statusLabel = "LIVE",
        onBack = onBack,
    ) {
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

            // Activates everything JARVIS is allowed to activate itself, then hands the one
            // remaining system switch to the operator with the right page already open.
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
                    filled = true,
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

            if (!allGranted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "JARVIS switches on the in-app grants itself. The accessibility and " +
                        "notification switches belong to Android, so those open the exact system " +
                        "page — Android will not let any app flip them silently, and that is the " +
                        "protection working as intended.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Accessibility access is a powerful grant. JARVIS uses it only for navigation " +
                    "gestures and for pressing a control whose exact label you typed in your own " +
                    "routine — always after a confirmation prompt. It never reads your screen in " +
                    "the background and never records input.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
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
                            text = "${state.stepIndex + 1}/${state.stepCount}",
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
                    text = state.currentAction,
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                    color = IceBlue,
                )
                Spacer(Modifier.height(10.dp))
                HudMeter(
                    progress = (state.stepIndex + 1).toFloat() / state.stepCount,
                    accent = ElectricCyan,
                    height = 5.dp,
                )
                Spacer(Modifier.height(12.dp))
                RunLog(log = state.log, maxLines = 6)
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
                // Answered on the shell's confirmation card, which stays reachable from any screen.
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.workflowName,
                        style = HudType.value.copy(fontSize = 13.sp),
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    HudChip(
                        text = if (state.stopped) "HALTED" else "DONE",
                        accent = if (state.stopped) AlertRed else StatusGreen,
                        filled = true,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ReadoutRow(label = "SUCCEEDED", value = "${state.succeeded}", valueColor = StatusGreen)
                ReadoutRow(label = "FAILED", value = "${state.failed}", valueColor = AlertRed)
                ReadoutRow(label = "SKIPPED", value = "${state.skipped}", valueColor = Amber)
                if (state.log.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HudDivider(accent = ElectricCyan)
                    Spacer(Modifier.height(8.dp))
                    RunLog(log = state.log, maxLines = 10)
                }
            }
        }

        // ---------------- routines ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ROUTINES",
                style = HudType.sectionTitle,
                color = ElectricCyan.copy(alpha = 0.9f),
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.35f), ChipShape)
                    .clickable(onClick = onNew)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "NEW",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                    color = ElectricCyan,
                )
            }
        }

        workflows.forEach { workflow ->
            WorkflowCard(
                workflow = workflow,
                busy = runState is RunState.Running || runState is RunState.AwaitingConfirmation,
                onRun = { onRun(workflow) },
                onEdit = { onEdit(workflow) },
                onDelete = { onDelete(workflow) },
            )
        }

        HudPanel(label = "SCOPE", accent = TextMuted) {
            ReadoutRow(label = "ROUTINE LIMIT", value = "20 STEPS")
            ReadoutRow(label = "CONCURRENCY", value = "ONE AT A TIME")
            ReadoutRow(label = "SENSITIVE STEPS", value = "ALWAYS CONFIRMED", valueColor = Amber)
            ReadoutRow(label = "STORAGE", value = "ON DEVICE ONLY", valueColor = StatusGreen)
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Automation can only use what Android exposes. Calls are never placed, " +
                    "messages are never sent, and payments are impossible — those steps open the " +
                    "relevant screen so you stay in control of the final action.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun AccessRow(title: String, value: String, ok: Boolean, caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelNavy.copy(alpha = 0.6f), ChipShape)
            .border(1.dp, (if (ok) StatusGreen else Amber).copy(alpha = 0.22f), ChipShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color = if (ok) StatusGreen else Amber, diameter = 5)
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.3.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = caption,
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.1.sp),
            color = if (ok) StatusGreen else Amber,
            maxLines = 1,
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    busy: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(PanelNavy, Color(0xFF091421))),
                TileShape,
            )
            .border(1.dp, ElectricCyan.copy(alpha = 0.16f), TileShape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workflow.name,
                    style = HudType.value.copy(fontSize = 13.sp, letterSpacing = 0.8.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${workflow.steps.size} STEPS" +
                        if (workflow.sensitiveCount > 0) " · ${workflow.sensitiveCount} SENSITIVE" else "",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.1.sp),
                    color = if (workflow.sensitiveCount > 0) Amber else TextMuted,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButtonBox(
                    icon = Icons.Outlined.PlayArrow,
                    tint = if (busy) TextMuted else StatusGreen,
                    enabled = !busy && workflow.steps.isNotEmpty(),
                    onClick = onRun,
                )
                IconButtonBox(
                    icon = Icons.Outlined.Edit,
                    tint = ElectricCyan,
                    enabled = true,
                    onClick = onEdit,
                )
                if (!workflow.builtIn) {
                    IconButtonBox(
                        icon = Icons.Outlined.Delete,
                        tint = AlertRed.copy(alpha = 0.85f),
                        enabled = true,
                        onClick = onDelete,
                    )
                }
            }
        }

        if (workflow.steps.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = workflow.steps.joinToString("  ›  ") { it.action.label },
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (workflow.requiresAccessibility) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(Amber),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "REQUIRES ACCESSIBILITY",
                    style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.2.sp),
                    color = Amber,
                )
            }
        }
    }
}

@Composable
private fun RunLog(
    log: List<com.jarvis.assistant.automation.RunLogEntry>,
    maxLines: Int,
) {
    Column {
        log.takeLast(maxLines).forEach { entry ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = entry.timeLabel,
                    style = HudType.terminal.copy(fontSize = 9.sp),
                    color = TextMuted.copy(alpha = 0.8f),
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(4.dp)
                        .background(if (entry.ok) StatusGreen else AlertRed),
                )
                Spacer(Modifier.width(7.dp))
                Column {
                    Text(
                        text = entry.actionLabel,
                        style = HudType.terminal.copy(fontSize = 9.sp),
                        color = TextSecondary,
                    )
                    Text(
                        text = entry.outcome,
                        style = HudType.terminal.copy(fontSize = 9.sp),
                        color = if (entry.ok) TextMuted else AlertRed.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

@Composable
private fun IconButtonBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(if (enabled) tint.copy(alpha = 0.10f) else Color(0xFF0A1520), ChipShape)
            .border(1.dp, tint.copy(alpha = if (enabled) 0.35f else 0.12f), ChipShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
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
            .background(
                if (filled) accent.copy(alpha = 0.16f) else Color.Transparent,
                ChipShape,
            )
            .border(1.dp, accent.copy(alpha = if (filled) 0.6f else 0.32f), ChipShape)
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
