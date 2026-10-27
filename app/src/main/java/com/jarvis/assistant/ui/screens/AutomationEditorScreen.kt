package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.AutomationEngine
import com.jarvis.assistant.automation.RunState
import com.jarvis.assistant.automation.ParamKind
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.ui.components.AppIcon
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudMeter
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
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
import com.jarvis.assistant.ui.theme.TileShape

/**
 * Routine editor.
 *
 * Deliberately modal-free: one inline sheet at a time for adding a step or editing a parameter, so
 * the whole flow stays legible on a phone. Steps are edited in place with explicit up/down controls
 * rather than drag-and-drop, which is unreliable on tall lists.
 */
@Composable
fun AutomationEditorScreen(
    workflow: Workflow,
    apps: List<InstalledApp>,
    runState: RunState,
    accessibilityEnabled: Boolean,
    screenPressAllowed: Boolean,
    onChange: (Workflow) -> Unit,
    onSave: () -> Unit,
    onTestStep: (WorkflowStep) -> Unit,
    onStopTest: () -> Unit,
    onActivateAccessibility: () -> Unit,
    onAllowScreenPress: () -> Unit,
    onBack: () -> Unit,
) {
    var sheet by remember { mutableStateOf<EditorSheet?>(null) }

    // The engine names its throwaway workflow STEP TEST, so its state is distinguishable here.
    val stateName: String? = when (runState) {
        is RunState.Running -> runState.workflowName
        is RunState.AwaitingConfirmation -> runState.workflowName
        is RunState.Finished -> runState.workflowName
        RunState.Idle -> null
    }
    val isStepTest = stateName == AutomationEngine.STEP_TEST_NAME
    val testRunning = isStepTest &&
        (runState is RunState.Running || runState is RunState.AwaitingConfirmation)
    val testFinished = isStepTest && runState is RunState.Finished

    ModuleScaffold(
        title = "ROUTINE EDITOR",
        subtitle = "build a workflow",
        accent = ElectricCyan,
        statusLabel = "DRAFT",
        onBack = onBack,
    ) {
        // ---- name ----
        HudPanel(label = "ROUTINE NAME") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF08131F), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.25f), ChipShape)
                    .padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = workflow.name,
                    onValueChange = { onChange(workflow.copy(name = it.take(28))) },
                    singleLine = true,
                    textStyle = HudType.value.copy(fontSize = 13.sp, color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${workflow.steps.size} step(s) · ${workflow.sensitiveCount} sensitive",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = if (workflow.steps.isEmpty()) Amber else TextMuted,
            )
        }

        // ---- steps ----
        HudPanel(label = "STEPS", accent = IceBlue) {
            if (workflow.steps.isEmpty()) {
                Text(
                    text = "No steps yet. Add one below — each step runs top to bottom when you " +
                        "start the routine.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            } else {
                workflow.steps.forEachIndexed { index, step ->
                    StepRow(
                        index = index,
                        step = step,
                        canMoveUp = index > 0,
                        canMoveDown = index < workflow.steps.lastIndex,
                        onEditParam = { sheet = EditorSheet.Param(index) },
                        onMoveUp = { onChange(workflow.moveStep(index, index - 1)) },
                        onMoveDown = { onChange(workflow.moveStep(index, index + 1)) },
                        onRemove = { onChange(workflow.removeStep(index)) },
                        onTest = { onTestStep(step) },
                        testEnabled = !testRunning,
                    )
                    if (index != workflow.steps.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.4f), ChipShape)
                    .clickable { sheet = EditorSheet.Actions }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+ ADD STEP",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.8.sp),
                    color = ElectricCyan,
                )
            }
        }

        // ---- single-step test ----
        if (testRunning || testFinished) {
            val accent = when {
                runState is RunState.AwaitingConfirmation -> Amber
                testFinished && (runState as RunState.Finished).failed == 0 -> StatusGreen
                testFinished -> AlertRed
                else -> ElectricCyan
            }
            HudPanel(label = "STEP TEST", accent = accent) {
                when (runState) {
                    is RunState.AwaitingConfirmation -> {
                        Text(
                            text = "WAITING FOR APPROVAL",
                            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                            color = Amber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${runState.actionLabel} · ${runState.detail}",
                            style = HudType.value.copy(fontSize = 11.sp),
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "This step is gated. Approve it on the confirmation card.",
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted,
                        )
                    }

                    is RunState.Running -> {
                        Text(
                            text = "RUNNING · ${runState.currentAction}",
                            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                            color = ElectricCyan,
                        )
                        Spacer(Modifier.height(10.dp))
                        HudMeter(progress = 0.5f, accent = ElectricCyan, height = 5.dp)
                    }

                    is RunState.Finished -> {
                        runState.log.forEach { entry ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = if (entry.ok) "PASS" else "FAIL",
                                    style = HudType.label.copy(fontSize = 8.sp),
                                    color = if (entry.ok) StatusGreen else AlertRed,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.actionLabel,
                                        style = HudType.value.copy(fontSize = 11.sp),
                                        color = TextPrimary,
                                    )
                                    Text(
                                        text = entry.outcome,
                                        style = HudType.terminal.copy(fontSize = 10.sp),
                                        color = if (entry.ok) TextSecondary else Color(0xFFFF8A98),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "A failing test usually means the step needs a permission or " +
                                "a target that does not exist right now.",
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted,
                        )
                    }

                    else -> Unit
                }
                if (testRunning) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AlertRed.copy(alpha = 0.12f), ChipShape)
                            .border(1.dp, AlertRed.copy(alpha = 0.4f), ChipShape)
                            .clickable(onClick = onStopTest)
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "STOP TEST",
                            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                            color = AlertRed,
                        )
                    }
                }
            }
        }

        // ---- inline sheets ----
        when (val current = sheet) {
            null -> Unit

            is EditorSheet.Actions -> HudPanel(label = "AVAILABLE ACTIONS", accent = IceBlue) {
                SheetHeader(onClose = { sheet = null })
                Spacer(Modifier.height(10.dp))
                ActionType.entries.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .background(PanelNavy.copy(alpha = 0.6f), ChipShape)
                            .border(
                                1.dp,
                                (if (action.sensitive) Amber else ElectricCyan).copy(alpha = 0.18f),
                                ChipShape,
                            )
                            .clickable {
                                val newStep = WorkflowStep(
                                    action = action,
                                    param = when (action) {
                                        ActionType.SET_VOLUME -> "50"
                                        ActionType.WAIT -> "2"
                                        ActionType.SET_TIMER -> "300"
                                        else -> ""
                                    },
                                )
                                val updated = workflow.copy(steps = workflow.steps + newStep)
                                onChange(updated)
                                sheet = if (action.paramKind == ParamKind.NONE) {
                                    null
                                } else {
                                    EditorSheet.Param(updated.steps.lastIndex)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = action.label,
                                    style = HudType.value.copy(fontSize = 11.sp),
                                    color = TextPrimary,
                                )
                                if (action.sensitive) {
                                    Spacer(Modifier.width(7.dp))
                                    HudChip(text = "CONFIRM", accent = Amber)
                                }
                                if (action.needsAccessibility) {
                                    Spacer(Modifier.width(6.dp))
                                    HudChip(text = "A11Y", accent = IceBlue)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = action.blurb,
                                style = HudType.terminal.copy(fontSize = 9.sp),
                                color = TextMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            is EditorSheet.Param -> {
                val step = workflow.steps.getOrNull(current.index) ?: return@ModuleScaffold
                HudPanel(label = "STEP ${current.index + 1} · ${step.action.label}", accent = ElectricCyan) {
                    SheetHeader(onClose = { sheet = null })
                    Spacer(Modifier.height(10.dp))

                    when (step.action.paramKind) {
                        ParamKind.APP -> AppParamPicker(
                            apps = apps,
                            selectedKey = step.param,
                            onPick = { app ->
                                onChange(workflow.replaceStep(current.index, step.copy(param = app.key)))
                                sheet = null
                            },
                        )

                        ParamKind.NUMBER -> ParamField(
                            value = step.param,
                            keyboardType = KeyboardType.Number,
                            hint = when (step.action) {
                                ActionType.SET_VOLUME -> "0–100"
                                ActionType.WAIT -> "seconds (max 120)"
                                else -> "seconds"
                            },
                            onChange = { text ->
                                val filtered = text.filter { it.isDigit() }.take(5)
                                onChange(workflow.replaceStep(current.index, step.copy(param = filtered)))
                            },
                        )

                        ParamKind.TEXT -> ParamField(
                            value = step.param,
                            keyboardType = KeyboardType.Text,
                            hint = when (step.action) {
                                ActionType.OPEN_URL -> "example.com"
                                ActionType.DIAL_NUMBER -> "+91…"
                                ActionType.COMPOSE_SMS -> "+91…"
                                ActionType.OPEN_PANEL -> "wifi · bluetooth · dnd · display · volume · battery"
                                ActionType.GLOBAL_ACTION -> "back · home · recents · notifications · quick · lock"
                                ActionType.PRESS_TEXT -> "exact button label"
                                else -> "value"
                            },
                            onChange = { text ->
                                onChange(workflow.replaceStep(current.index, step.copy(param = text.take(80))))
                            },
                        )

                        ParamKind.NONE -> Text(
                            text = "This action needs no value.",
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted,
                        )
                    }

                    if (step.action.needsAccessibility && !accessibilityEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "This step needs the accessibility service.",
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = Amber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Amber.copy(alpha = 0.12f), ChipShape)
                                .border(1.dp, Amber.copy(alpha = 0.45f), ChipShape)
                                .clickable(onClick = onActivateAccessibility)
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "ACTIVATE ACCESSIBILITY",
                                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                                color = Amber,
                            )
                        }
                    }

                    if (step.action == ActionType.PRESS_TEXT && !screenPressAllowed) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(IceBlue.copy(alpha = 0.12f), ChipShape)
                                .border(1.dp, IceBlue.copy(alpha = 0.45f), ChipShape)
                                .clickable(onClick = onAllowScreenPress)
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "ALLOW SCREEN PRESSING",
                                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                                color = IceBlue,
                            )
                        }
                    }

                    if (step.action.sensitive) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(4.dp)
                                    .background(Amber),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = "Sensitive: the routine pauses here and waits for your " +
                                    "approval every time it runs.",
                                style = HudType.terminal.copy(fontSize = 10.sp),
                                color = Amber,
                            )
                        }
                    }
                }
            }
        }

        // ---- save ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(StatusGreen.copy(alpha = 0.14f), ChipShape)
                .border(1.dp, StatusGreen.copy(alpha = 0.5f), ChipShape)
                .clickable(onClick = onSave)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "SAVE ROUTINE",
                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.8.sp),
                color = StatusGreen,
            )
        }

        HudPanel(label = "STEP LIMIT", accent = TextMuted) {
            ReadoutRow(label = "MAX STEPS", value = "20")
            ReadoutRow(label = "ORDER", value = "TOP TO BOTTOM")
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "A failing step is logged and the routine continues, so one unavailable " +
                    "action does not strand the rest.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

/** One inline sheet at a time. */
private sealed interface EditorSheet {
    data object Actions : EditorSheet
    data class Param(val index: Int) : EditorSheet
}

private fun Workflow.moveStep(from: Int, to: Int): Workflow {
    if (to !in steps.indices || from !in steps.indices) return this
    val mutable = steps.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return copy(steps = mutable)
}

private fun Workflow.removeStep(index: Int): Workflow =
    if (index in steps.indices) copy(steps = steps.filterIndexed { i, _ -> i != index }) else this

private fun Workflow.replaceStep(index: Int, step: WorkflowStep): Workflow =
    if (index in steps.indices) {
        copy(steps = steps.toMutableList().also { it[index] = step })
    } else {
        this
    }

@Composable
private fun SheetHeader(onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "EDIT",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
            color = TextMuted,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF10202F), ChipShape)
                .border(1.dp, TextMuted.copy(alpha = 0.35f), ChipShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun ParamField(
    value: String,
    keyboardType: KeyboardType,
    hint: String,
    onChange: (String) -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF08131F), ChipShape)
                .border(1.dp, ElectricCyan.copy(alpha = 0.3f), ChipShape)
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(text = hint, style = HudType.value.copy(fontSize = 12.sp), color = TextMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = HudType.value.copy(fontSize = 12.sp, color = TextPrimary),
                cursorBrush = SolidColor(ElectricCyan),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint.uppercase(),
            style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.1.sp),
            color = TextMuted,
        )
    }
}

@Composable
private fun AppParamPicker(
    apps: List<InstalledApp>,
    selectedKey: String,
    onPick: (InstalledApp) -> Unit,
) {
    Column {
        if (apps.isEmpty()) {
            Text(
                text = "No installed apps indexed yet. Open the drawer once, then come back.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
            return@Column
        }
        Text(
            text = "CHOOSE AN APP",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.height(300.dp)) {
            items(items = apps, key = { it.key }) { app ->
                val selected = app.key == selectedKey
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .background(
                            if (selected) ElectricCyan.copy(alpha = 0.12f)
                            else PanelNavy.copy(alpha = 0.6f),
                            TileShape,
                        )
                        .border(
                            1.dp,
                            ElectricCyan.copy(alpha = if (selected) 0.5f else 0.12f),
                            TileShape,
                        )
                        .clickable { onPick(app) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app = app, size = 24.dp)
                    Spacer(Modifier.width(10.dp))
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
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: WorkflowStep,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEditParam: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
    testEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelNavy.copy(alpha = 0.6f), TileShape)
            .border(
                1.dp,
                (if (step.action.sensitive) Amber else ElectricCyan).copy(alpha = 0.16f),
                TileShape,
            )
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(index + 1),
            style = HudType.label.copy(fontSize = 9.sp),
            color = TextMuted,
        )
        Spacer(Modifier.width(9.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = step.action.paramKind != ParamKind.NONE, onClick = onEditParam),
        ) {
            Text(
                text = step.action.label,
                style = HudType.value.copy(fontSize = 11.sp),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = step.displayParam,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.5.sp),
                color = if (step.isConfigured) IceBlue else Amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        MiniButton(Icons.Outlined.PlayArrow, testEnabled, onTest, tint = StatusGreen)
        Spacer(Modifier.width(5.dp))
        MiniButton(Icons.Outlined.ArrowUpward, canMoveUp, onMoveUp)
        Spacer(Modifier.width(5.dp))
        MiniButton(Icons.Outlined.ArrowDownward, canMoveDown, onMoveDown)
        Spacer(Modifier.width(5.dp))
        MiniButton(Icons.Outlined.Close, true, onRemove, tint = AlertRed)
    }
}

@Composable
private fun MiniButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = ElectricCyan,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(if (enabled) tint.copy(alpha = 0.08f) else Color.Transparent, ChipShape)
            .border(1.dp, tint.copy(alpha = if (enabled) 0.3f else 0.1f), ChipShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (enabled) 0.9f else 0.25f),
            modifier = Modifier.size(11.dp),
        )
    }
}
