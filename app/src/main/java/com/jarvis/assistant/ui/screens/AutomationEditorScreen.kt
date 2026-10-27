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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import com.jarvis.assistant.automation.Condition
import com.jarvis.assistant.automation.ConditionEvaluator
import com.jarvis.assistant.automation.ConditionType
import com.jarvis.assistant.automation.FailPolicy
import com.jarvis.assistant.automation.ParamKind
import com.jarvis.assistant.automation.RoutineTrigger
import com.jarvis.assistant.automation.RunState
import com.jarvis.assistant.automation.TriggerKind
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.ui.components.AppIcon
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudMeter
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
import com.jarvis.assistant.ui.theme.TextSecondary
import com.jarvis.assistant.ui.theme.TileShape

// ---------------------------------------------------------------------------------------------
// Path helpers: a step can live inside any block, so edits address a path, not an index.
// ---------------------------------------------------------------------------------------------

private enum class Branch(val label: String) {
    THEN("THEN"),
    ELSE("ELSE"),
}

/**
 * A step address.
 *
 * [containers] walks down through blocks: each entry names a block by its index in the current list
 * and which of its two child lists the next level lives in. [index] is then the position inside the
 * final list. Addresses are used instead of plain indexes because one routine now holds several
 * lists, and reordering or deleting must target the right one.
 */
private data class StepRef(val index: Int, val child: Branch = Branch.THEN)

private data class StepPath(val containers: List<StepRef>, val index: Int) {
    val childContainers: List<StepRef> get() = containers + StepRef(index, Branch.THEN)
}

private fun childSteps(step: WorkflowStep, branch: Branch): List<WorkflowStep> =
    if (branch == Branch.THEN) step.children else step.elseChildren

private fun withChildren(
    step: WorkflowStep,
    branch: Branch,
    children: List<WorkflowStep>,
): WorkflowStep = if (branch == Branch.THEN) {
    step.copy(children = children)
} else {
    step.copy(elseChildren = children)
}

/** Maps the list at [containers], rebuilding every block on the way down. */
private fun editContainer(
    steps: List<WorkflowStep>,
    containers: List<StepRef>,
    transform: (List<WorkflowStep>) -> List<WorkflowStep>,
): List<WorkflowStep> {
    if (containers.isEmpty()) return transform(steps)
    val head = containers.first()
    if (head.index !in steps.indices) return steps
    val step = steps[head.index]
    val updatedChild = editContainer(
        childSteps(step, head.child),
        containers.drop(1),
        transform,
    )
    val updatedStep = withChildren(step, head.child, updatedChild)
    return steps.toMutableList().also { it[head.index] = updatedStep }
}

private fun Workflow.listAt(containers: List<StepRef>): List<WorkflowStep> {
    var current = steps
    containers.forEach { ref ->
        val step = current.getOrNull(ref.index) ?: return emptyList()
        current = childSteps(step, ref.child)
    }
    return current
}

private fun Workflow.stepAt(path: StepPath): WorkflowStep? =
    listAt(path.containers).getOrNull(path.index)

private fun Workflow.updateStep(path: StepPath, transform: (WorkflowStep) -> WorkflowStep): Workflow {
    val newRoot = editContainer(steps, path.containers) { container ->
        if (path.index !in container.indices) {
            container
        } else {
            container.toMutableList().also { it[path.index] = transform(it[path.index]) }
        }
    }
    return copy(steps = newRoot)
}

private fun Workflow.removeStep(path: StepPath): Workflow {
    val newRoot = editContainer(steps, path.containers) { container ->
        container.filterIndexed { index, _ -> index != path.index }
    }
    return copy(steps = newRoot)
}

private fun Workflow.moveStep(path: StepPath, delta: Int): Workflow {
    val newRoot = editContainer(steps, path.containers) { container ->
        val to = path.index + delta
        if (path.index !in container.indices || to !in container.indices) {
            container
        } else {
            container.toMutableList().also { list ->
                list.add(to, list.removeAt(path.index))
            }
        }
    }
    return copy(steps = newRoot)
}

private fun Workflow.appendStep(step: WorkflowStep, containers: List<StepRef>): Workflow {
    val newRoot = editContainer(steps, containers) { container -> container + step }
    return copy(steps = newRoot)
}

private fun Workflow.containerSize(containers: List<StepRef>): Int = listAt(containers).size

/** Default value for a newly added action, so it is never born unconfigured. */
private fun defaultParam(action: ActionType): String = when (action) {
    ActionType.SET_VOLUME -> "50"
    ActionType.WAIT -> "2"
    ActionType.SET_TIMER -> "300"
    ActionType.SET_ALARM -> "07:30"
    ActionType.REPEAT_BLOCK -> "2"
    ActionType.GLOBAL_ACTION -> "home"
    ActionType.OPEN_PANEL -> "wifi"
    else -> ""
}

/**
 * Routine editor.
 *
 * Blocks can hold steps, so the list renders recursively with an indent per level. Editing is
 * address-based (a path, not an index) because one routine now contains several containers.
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
    var triggerOpen by remember { mutableStateOf(false) }

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
        // ---- name + summary ----
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
                text = "${workflow.totalSteps} step(s) · ${workflow.sensitiveCount} confirm · " +
                    workflow.trigger.display,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = if (workflow.steps.isEmpty()) Amber else TextMuted,
            )
        }

        // ---- trigger ----
        HudPanel(
            label = "TRIGGER",
            accent = if (workflow.trigger.kind.isSilent) TextMuted else StatusGreen,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workflow.trigger.kind.label,
                        style = HudType.value.copy(fontSize = 12.sp),
                        color = TextPrimary,
                    )
                    Text(
                        text = if (workflow.trigger.kind.isSilent) {
                            "Runs only when you tap it"
                        } else {
                            workflow.trigger.display
                        },
                        style = HudType.terminal.copy(fontSize = 10.sp),
                        color = if (workflow.trigger.kind.isSilent) TextMuted else StatusGreen,
                    )
                }
                MiniButton(
                    icon = Icons.Outlined.Add,
                    enabled = true,
                    onClick = { triggerOpen = !triggerOpen },
                    tint = ElectricCyan,
                )
            }

            if (triggerOpen) {
                Spacer(Modifier.height(10.dp))
                TriggerKind.entries.forEach { kind ->
                    val selected = kind == workflow.trigger.kind
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 5.dp)
                            .background(
                                if (selected) ElectricCyan.copy(alpha = 0.12f) else PanelNavy.copy(alpha = 0.6f),
                                ChipShape,
                            )
                            .border(
                                1.dp,
                                ElectricCyan.copy(alpha = if (selected) 0.45f else 0.12f),
                                ChipShape,
                            )
                            .clickable {
                                onChange(
                                    workflow.copy(
                                        trigger = RoutineTrigger(kind, kind.defaultParam())
                                    )
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = kind.label,
                                style = HudType.value.copy(fontSize = 11.sp),
                                color = if (selected) TextPrimary else TextSecondary,
                            )
                            Text(
                                text = kind.blurb,
                                style = HudType.terminal.copy(fontSize = 9.sp),
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (workflow.trigger.kind.needsParam) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = workflow.trigger.kind.paramLabel?.uppercase() ?: "VALUE",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    ParamField(
                        value = workflow.trigger.param,
                        keyboardType = if (workflow.trigger.kind.isTimeBased) {
                            KeyboardType.Text
                        } else {
                            KeyboardType.Number
                        },
                        hint = when (workflow.trigger.kind) {
                            TriggerKind.DAILY, TriggerKind.WEEKDAYS -> "HH:mm"
                            TriggerKind.BATTERY_LOW -> "percent"
                            TriggerKind.NOTIFICATION -> "com.example.app"
                            else -> "value"
                        },
                        onChange = { text ->
                            onChange(workflow.copy(trigger = workflow.trigger.copy(param = text.take(60))))
                        },
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Triggers fire only while automation is armed on the previous screen. " +
                        "Time triggers can be a few minutes late unless exact alarms are allowed.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            }
        }

        // ---- steps ----
        HudPanel(label = "STEPS", accent = IceBlue) {
            if (workflow.steps.isEmpty()) {
                Text(
                    text = "No steps yet. Add one below — each step runs top to bottom when the " +
                        "trigger fires or you run the routine.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            } else {
                StepList(
                    steps = workflow.steps,
                    containers = emptyList(),
                    depth = 0,
                    testEnabled = !testRunning,
                    onEdit = { path -> sheet = EditorSheet.Param(path) },
                    onTest = onTestStep,
                    onMove = { path, delta -> onChange(workflow.moveStep(path, delta)) },
                    onRemove = { path -> onChange(workflow.removeStep(path)) },
                    onAddInside = { containers -> sheet = EditorSheet.AddStep(containers) },
                )
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.4f), ChipShape)
                    .clickable { sheet = EditorSheet.AddStep(emptyList()) }
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

        // ---- step test ----
        if (testRunning || testFinished) {
            val accent = when {
                runState is RunState.AwaitingConfirmation -> Amber
                testFinished && runState is RunState.Finished && runState.failed == 0 -> StatusGreen
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
                            text = "Approve it on the confirmation card.",
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
                            text = "A failing test usually means a missing permission or a target " +
                                "that is not on screen right now.",
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

        // ---- sheets ----
        when (val current = sheet) {
            null -> Unit

            is EditorSheet.AddStep -> AddStepSheet(
                insideBlock = current.containers.isNotEmpty(),
                onPick = { action ->
                    val step = WorkflowStep(action = action, param = defaultParam(action))
                    // Capture the position before appending: it is where the new step lands.
                    val newIndex = workflow.containerSize(current.containers)
                    onChange(workflow.appendStep(step, current.containers))
                    sheet = EditorSheet.Param(StepPath(current.containers, newIndex))
                },
                onClose = { sheet = null },
            )

            is EditorSheet.Param -> {
                val step = workflow.stepAt(current.path)
                if (step == null) {
                    sheet = null
                } else {
                    ParamSheet(
                        step = step,
                        apps = apps,
                        accessibilityEnabled = accessibilityEnabled,
                        screenPressAllowed = screenPressAllowed,
                        onChange = { updated ->
                            onChange(workflow.updateStep(current.path) { updated })
                        },
                        onActivateAccessibility = onActivateAccessibility,
                        onAllowScreenPress = onAllowScreenPress,
                        onClose = { sheet = null },
                    )
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

        HudPanel(label = "LIMITS", accent = TextMuted) {
            ReadoutRow(label = "BUDGET", value = "${AutomationEngine.MAX_TOTAL_STEPS} STEPS / RUN")
            ReadoutRow(label = "NESTING", value = "${AutomationEngine.MAX_DEPTH} LEVELS")
            ReadoutRow(label = "ORDER", value = "TOP TO BOTTOM")
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Each step has its own timeout and failure policy, so one stuck action " +
                    "cannot stall the routine unless you told it to halt.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

private fun TriggerKind.defaultParam(): String = when (this) {
    TriggerKind.DAILY, TriggerKind.WEEKDAYS -> "07:00"
    TriggerKind.BATTERY_LOW -> "20"
    else -> ""
}

private sealed interface EditorSheet {
    data class AddStep(val containers: List<StepRef>) : EditorSheet
    data class Param(val path: StepPath) : EditorSheet
}

// ---------------------------------------------------------------------------------------------
// Recursive step list
// ---------------------------------------------------------------------------------------------

@Composable
private fun StepList(
    steps: List<WorkflowStep>,
    containers: List<StepRef>,
    depth: Int,
    testEnabled: Boolean,
    onEdit: (StepPath) -> Unit,
    onTest: (WorkflowStep) -> Unit,
    onMove: (StepPath, Int) -> Unit,
    onRemove: (StepPath) -> Unit,
    onAddInside: (List<StepRef>) -> Unit,
) {
    steps.forEachIndexed { index, step ->
        val path = StepPath(containers, index)
        StepRow(
            number = index + 1,
            step = step,
            depth = depth,
            canMoveUp = index > 0,
            canMoveDown = index < steps.lastIndex,
            testEnabled = testEnabled,
            onEdit = { onEdit(path) },
            onTest = { onTest(step) },
            onMoveUp = { onMove(path, -1) },
            onMoveDown = { onMove(path, 1) },
            onRemove = { onRemove(path) },
        )

        when (step.action) {
            ActionType.IF_BLOCK -> {
                BlockBody(
                    label = Branch.THEN.label,
                    steps = step.children,
                    containers = path.containers + StepRef(path.index, Branch.THEN),
                    depth = depth,
                    testEnabled = testEnabled,
                    onEdit = onEdit,
                    onTest = onTest,
                    onMove = onMove,
                    onRemove = onRemove,
                    onAddInside = onAddInside,
                )
                BlockBody(
                    label = Branch.ELSE.label,
                    steps = step.elseChildren,
                    containers = path.containers + StepRef(path.index, Branch.ELSE),
                    depth = depth,
                    testEnabled = testEnabled,
                    onEdit = onEdit,
                    onTest = onTest,
                    onMove = onMove,
                    onRemove = onRemove,
                    onAddInside = onAddInside,
                )
            }

            ActionType.REPEAT_BLOCK -> BlockBody(
                label = "REPEAT",
                steps = step.children,
                containers = path.containers + StepRef(path.index, Branch.THEN),
                depth = depth,
                testEnabled = testEnabled,
                onEdit = onEdit,
                onTest = onTest,
                onMove = onMove,
                onRemove = onRemove,
                onAddInside = onAddInside,
            )

            else -> Unit
        }

        if (index != steps.lastIndex) Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun BlockBody(
    label: String,
    steps: List<WorkflowStep>,
    containers: List<StepRef>,
    depth: Int,
    testEnabled: Boolean,
    onEdit: (StepPath) -> Unit,
    onTest: (WorkflowStep) -> Unit,
    onMove: (StepPath, Int) -> Unit,
    onRemove: (StepPath) -> Unit,
    onAddInside: (List<StepRef>) -> Unit,
) {
    Row(modifier = Modifier.padding(start = 14.dp, top = 6.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.6.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(4.dp))
            if (steps.isEmpty()) {
                Text(
                    text = "empty",
                    style = HudType.terminal.copy(fontSize = 9.sp),
                    color = TextMuted,
                )
            } else {
                StepList(
                    steps = steps,
                    containers = containers,
                    depth = depth + 1,
                    testEnabled = testEnabled,
                    onEdit = onEdit,
                    onTest = onTest,
                    onMove = onMove,
                    onRemove = onRemove,
                    onAddInside = onAddInside,
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF0A1A28), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.18f), ChipShape)
                    .clickable { onAddInside(containers) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "+ INSIDE",
                    style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.2.sp),
                    color = ElectricCyan,
                )
            }
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    step: WorkflowStep,
    depth: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    testEnabled: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val policyColor = if (step.onFail == FailPolicy.HALT) AlertRed else TextMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = depth * 14.dp)
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
            text = number.toString().padStart(2, '0'),
            style = HudType.label.copy(fontSize = 9.sp),
            color = TextMuted,
        )
        Spacer(Modifier.width(9.dp))

        Column(modifier = Modifier.weight(1f).clickable(onClick = onEdit)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.action.label,
                    style = HudType.value.copy(fontSize = 11.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (step.retries > 0) {
                    Spacer(Modifier.width(5.dp))
                    HudChip(text = "×${step.retries}", accent = IceBlue)
                }
                if (step.onFail == FailPolicy.HALT) {
                    Spacer(Modifier.width(5.dp))
                    HudChip(text = "HALT", accent = AlertRed)
                }
            }
            Text(
                text = step.displayParam,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.5.sp),
                color = if (step.isConfigured) IceBlue else Amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!step.action.blocks) {
            MiniButton(Icons.Outlined.PlayArrow, testEnabled, onTest, tint = StatusGreen)
            Spacer(Modifier.width(5.dp))
        }
        MiniButton(Icons.Outlined.ArrowUpward, canMoveUp, onMoveUp)
        Spacer(Modifier.width(5.dp))
        MiniButton(Icons.Outlined.ArrowDownward, canMoveDown, onMoveDown)
        Spacer(Modifier.width(5.dp))
        MiniButton(Icons.Outlined.Close, true, onRemove, tint = policyColor.takeIf { it == AlertRed } ?: TextMuted)
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------------------------

@Composable
private fun AddStepSheet(
    insideBlock: Boolean,
    onPick: (ActionType) -> Unit,
    onClose: () -> Unit,
) {
    HudPanel(label = if (insideBlock) "ADD INSIDE BLOCK" else "AVAILABLE ACTIONS", accent = IceBlue) {
        SheetHeader(onClose = onClose)
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
                    .clickable { onPick(action) }
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
                        if (action.blocks) {
                            Spacer(Modifier.width(7.dp))
                            HudChip(text = "BLOCK", accent = ElectricCyan)
                        }
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
}

@Composable
private fun ParamSheet(
    step: WorkflowStep,
    apps: List<InstalledApp>,
    accessibilityEnabled: Boolean,
    screenPressAllowed: Boolean,
    onChange: (WorkflowStep) -> Unit,
    onActivateAccessibility: () -> Unit,
    onAllowScreenPress: () -> Unit,
    onClose: () -> Unit,
) {
    HudPanel(label = "STEP · ${step.action.label}", accent = ElectricCyan) {
        SheetHeader(onClose = onClose)
        Spacer(Modifier.height(10.dp))

        // ---- condition for blocks and WAIT UNTIL ----
        if (step.action.blocks && step.action != ActionType.REPEAT_BLOCK) {
            ConditionPicker(
                condition = step.condition,
                onChange = { condition -> onChange(step.copy(condition = condition)) },
            )
            Spacer(Modifier.height(10.dp))
        }

        // ---- value ----
        when (step.action.paramKind) {
            ParamKind.APP -> AppParamPicker(
                apps = apps,
                selectedKey = step.param,
                onPick = { app -> onChange(step.copy(param = app.key)) },
            )

            ParamKind.NUMBER -> ParamField(
                value = step.param,
                keyboardType = KeyboardType.Number,
                hint = when (step.action) {
                    ActionType.SET_VOLUME -> "0–100"
                    ActionType.WAIT -> "seconds (max 120)"
                    ActionType.REPEAT_BLOCK -> "times (1–10)"
                    else -> "seconds"
                },
                onChange = { text ->
                    onChange(step.copy(param = text.filter { it.isDigit() }.take(5)))
                },
            )

            ParamKind.TEXT -> ParamField(
                value = step.param,
                keyboardType = if (step.action == ActionType.WEBHOOK) {
                    KeyboardType.Uri
                } else {
                    KeyboardType.Text
                },
                hint = when (step.action) {
                    ActionType.OPEN_URL -> "example.com"
                    ActionType.DEEP_LINK -> "app://screen"
                    ActionType.DIAL_NUMBER, ActionType.COMPOSE_SMS -> "+91…"
                    ActionType.OPEN_PANEL -> "wifi · bluetooth · dnd · display · volume · battery"
                    ActionType.GLOBAL_ACTION -> "back · home · recents · notifications · quick · lock"
                    ActionType.PRESS_TEXT -> "exact button label"
                    ActionType.SET_ALARM -> "07:30"
                    ActionType.WEBHOOK -> "https://host/path or POST https://host/path"
                    ActionType.SAY -> "what should JARVIS say"
                    ActionType.NOTIFY -> "notification text"
                    else -> "value"
                },
                onChange = { text -> onChange(step.copy(param = text.take(300))) },
            )

            ParamKind.NONE -> if (!step.action.blocks) {
                Text(
                    text = "This action needs no value.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextMuted,
                )
            }
        }

        // ---- permission affordances ----
        if (step.action.needsAccessibility && !accessibilityEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "This step needs the accessibility service.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = Amber,
            )
            Spacer(Modifier.height(6.dp))
            SolidPill("ACTIVATE ACCESSIBILITY", Amber, onActivateAccessibility)
        }
        if (step.action == ActionType.PRESS_TEXT && !screenPressAllowed) {
            Spacer(Modifier.height(8.dp))
            SolidPill("ALLOW SCREEN PRESSING", IceBlue, onAllowScreenPress)
        }

        // ---- failure policy ----
        Spacer(Modifier.height(12.dp))
        Text(
            text = "ON FAILURE",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FailPolicy.entries.forEach { policy ->
                val selected = policy == step.onFail
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            (if (selected) ElectricCyan else TextMuted).copy(alpha = if (selected) 0.14f else 0.06f),
                            ChipShape,
                        )
                        .border(
                            1.dp,
                            (if (selected) ElectricCyan else TextMuted).copy(alpha = if (selected) 0.5f else 0.15f),
                            ChipShape,
                        )
                        .clickable { onChange(step.copy(onFail = policy)) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = policy.label,
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                        color = if (selected) ElectricCyan else TextMuted,
                    )
                }
            }
        }

        // ---- retries + timeout ----
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stepper(
                label = "RETRIES",
                value = step.retries.toString(),
                onChange = { onChange(step.copy(retries = it.coerceIn(0, 3))) },
                modifier = Modifier.weight(1f),
            )
            Stepper(
                label = "TIMEOUT S",
                value = step.timeoutSec.toString(),
                onChange = { onChange(step.copy(timeoutSec = it.coerceIn(5, 600))) },
                step = 5,
                modifier = Modifier.weight(1f),
            )
        }

        if (step.action.sensitive) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Sensitive: the routine pauses here and waits for your approval every time " +
                    "it runs, including when a trigger started it.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = Amber,
            )
        }
    }
}

@Composable
private fun ConditionPicker(
    condition: Condition?,
    onChange: (Condition?) -> Unit,
) {
    Text(
        text = "CONDITION",
        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
        color = TextMuted,
    )
    Spacer(Modifier.height(6.dp))
    ConditionType.entries.forEach { type ->
        val selected = condition?.type == type
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
                .background(
                    if (selected) ElectricCyan.copy(alpha = 0.12f) else PanelNavy.copy(alpha = 0.6f),
                    ChipShape,
                )
                .border(
                    1.dp,
                    ElectricCyan.copy(alpha = if (selected) 0.45f else 0.12f),
                    ChipShape,
                )
                .clickable { onChange(Condition(type)) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = type.label,
                style = HudType.value.copy(fontSize = 10.sp),
                color = if (selected) TextPrimary else TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (type.needsValue) {
                Text(
                    text = type.valueLabel,
                    style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.8.sp),
                    color = TextMuted,
                )
            }
        }
    }

    if (condition != null && condition.type.needsValue) {
        Spacer(Modifier.height(6.dp))
        ParamField(
            value = condition.param,
            keyboardType = if (condition.type == ConditionType.TIME_WINDOW) {
                KeyboardType.Text
            } else {
                KeyboardType.Text
            },
            hint = condition.type.valueLabel,
            onChange = { text -> onChange(condition.copy(param = text.take(60))) },
        )
        if (condition.type == ConditionType.TIME_WINDOW &&
            ConditionEvaluator.parseWindow(condition.param) == null
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Use HH:mm-HH:mm, e.g. 22:00-06:00",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = Amber,
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    step: Int = 1,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .background(PanelNavy.copy(alpha = 0.6f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.15f), ChipShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                color = TextMuted,
            )
            Text(
                text = value,
                style = HudType.value.copy(fontSize = 12.sp),
                color = TextPrimary,
            )
        }
        StepButton(label = "+", tint = ElectricCyan) {
            onChange((value.toIntOrNull() ?: 0) + step)
        }
        Spacer(Modifier.width(4.dp))
        StepButton(label = "−", tint = TextMuted) {
            onChange((value.toIntOrNull() ?: 0) - step)
        }
    }
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
    if (apps.isEmpty()) {
        Text(
            text = "No installed apps indexed yet. Open the drawer once, then come back.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
        return
    }
    Text(
        text = "CHOOSE AN APP",
        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
        color = TextMuted,
    )
    Spacer(Modifier.height(8.dp))
    LazyColumn(modifier = Modifier.height(280.dp)) {
        items(items = apps, key = { it.key }) { app ->
            val selected = app.key == selectedKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(
                        if (selected) ElectricCyan.copy(alpha = 0.12f) else PanelNavy.copy(alpha = 0.6f),
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

@Composable
private fun SolidPill(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), ChipShape)
            .border(1.dp, accent.copy(alpha = 0.45f), ChipShape)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
            color = accent,
        )
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

@Composable
private fun StepButton(
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(tint.copy(alpha = 0.10f), ChipShape)
            .border(1.dp, tint.copy(alpha = 0.35f), ChipShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 11.sp),
            color = tint,
        )
    }
}
