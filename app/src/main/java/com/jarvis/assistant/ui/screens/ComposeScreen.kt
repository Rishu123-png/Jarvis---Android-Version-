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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ai.AiPlanner
import com.jarvis.assistant.ai.PlanResult
import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudPanel
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
import kotlinx.coroutines.launch

private val EXAMPLES = listOf(
    "every night at 11 turn the torch off and set volume to 20",
    "when the battery drops below 20 say battery low, dim the volume and turn off the torch",
    "on weekdays at 8 speak good morning and set a 25 minute timer",
)

/**
 * Author a routine from a sentence.
 *
 * Nothing here trusts the model: the plan it returns is validated against the real action list by
 * [AiPlanner], every adjustment is listed, and the routine is only stored when the operator taps
 * save. Sensitive steps stay gated after saving, because they are the same action ids.
 */
@Composable
fun ComposeScreen(
    aiConfigured: Boolean,
    onSave: (Workflow) -> Unit,
    onOpenAiSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var request by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<PlanResult?>(null) }

    fun compose() {
        if (busy) return
        val ask = request.trim()
        if (ask.isEmpty()) return
        busy = true
        plan = null
        scope.launch {
            plan = AiPlanner.plan(context, ask)
            busy = false
        }
    }

    ModuleScaffold(
        title = "COMPOSE",
        subtitle = "describe a routine",
        accent = IceBlue,
        statusLabel = if (aiConfigured) "AI READY" else "NO KEY",
        onBack = onBack,
    ) {
        if (!aiConfigured) {
            HudPanel(label = "INTELLIGENCE REQUIRED", accent = Amber) {
                Text(
                    text = "Composing needs the AI key you store in settings. Local routines still " +
                        "work without it — you can build the same thing by hand in the editor.",
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                SolidPill("ADD API KEY", ElectricCyan, onOpenAiSettings)
            }
            return@ModuleScaffold
        }

        HudPanel(label = "WHAT SHOULD IT DO?", accent = ElectricCyan) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Color(0xFF08131F), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.25f), ChipShape)
                    .padding(11.dp),
            ) {
                if (request.isEmpty()) {
                    Text(
                        text = "e.g. ${EXAMPLES.first()}",
                        style = HudType.terminal.copy(fontSize = 11.sp),
                        color = TextMuted,
                    )
                }
                BasicTextField(
                    value = request,
                    onValueChange = { request = it.take(400) },
                    textStyle = HudType.terminal.copy(fontSize = 11.sp, color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            SolidPill(
                label = if (busy) "PLANNING…" else "PLAN IT",
                accent = if (busy) TextMuted else StatusGreen,
                onClick = { if (!busy) compose() },
            )
            Spacer(Modifier.height(10.dp))
            EXAMPLES.forEach { example ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp)
                        .background(PanelNavy.copy(alpha = 0.6f), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.12f), ChipShape)
                        .clickable { request = example }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = example,
                        style = HudType.terminal.copy(fontSize = 10.sp),
                        color = TextSecondary,
                    )
                }
            }
        }

        when (val result = plan) {
            null -> Unit

            is PlanResult.Error -> HudPanel(label = "NOT PLANNED", accent = AlertRed) {
                Text(
                    text = result.message,
                    style = HudType.terminal.copy(fontSize = 11.sp),
                    color = Color(0xFFFF8A98),
                )
                result.hint?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = HudType.terminal.copy(fontSize = 10.sp),
                        color = TextMuted,
                    )
                }
            }

            is PlanResult.Ok -> {
                HudPanel(label = "PROPOSED ROUTINE", accent = StatusGreen) {
                    Text(
                        text = result.workflow.name,
                        style = HudType.value.copy(fontSize = 14.sp),
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "TRIGGER · ${result.workflow.trigger.display}",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = if (result.workflow.trigger.kind.isSilent) TextMuted else StatusGreen,
                    )
                    if (result.rationale.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = result.rationale,
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    PlanSteps(result.workflow.steps, depth = 0)
                }

                if (result.notes.isNotEmpty()) {
                    HudPanel(label = "CHECKED AND ADJUSTED", accent = Amber) {
                        result.notes.forEach { note ->
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    text = "•",
                                    style = HudType.terminal.copy(fontSize = 10.sp),
                                    color = Amber,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = note,
                                    style = HudType.terminal.copy(fontSize = 10.sp),
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }

                HudPanel(label = "GATES", accent = TextMuted) {
                    Text(
                        text = if (result.workflow.sensitiveCount > 0) {
                            "${result.workflow.sensitiveCount} step(s) in this plan are sensitive. " +
                                "They will still stop for your approval every time the routine runs — " +
                                "the model cannot remove that."
                        } else {
                            "No sensitive steps. Every action here is something JARVIS may do " +
                                "without asking each time."
                        },
                        style = HudType.terminal.copy(fontSize = 10.sp),
                        color = TextSecondary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        SolidPill(
                            label = "DISCARD",
                            accent = TextMuted,
                            onClick = { plan = null },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        SolidPill(
                            label = "SAVE ROUTINE",
                            accent = StatusGreen,
                            onClick = { onSave(result.workflow) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanSteps(steps: List<WorkflowStep>, depth: Int) {
    steps.forEachIndexed { index, step ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 14).dp, bottom = 5.dp)
                .background(PanelNavy.copy(alpha = 0.55f), TileShape)
                .border(
                    1.dp,
                    (if (step.action.sensitive) Amber else ElectricCyan).copy(alpha = 0.15f),
                    TileShape,
                )
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}".padStart(2, '0'),
                style = HudType.label.copy(fontSize = 8.sp),
                color = TextMuted,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = step.action.label,
                        style = HudType.value.copy(fontSize = 11.sp),
                        color = TextPrimary,
                    )
                    if (step.action.sensitive) {
                        Spacer(Modifier.width(6.dp))
                        HudChip(text = "CONFIRM", accent = Amber)
                    }
                    if (step.onFail == com.jarvis.assistant.automation.FailPolicy.HALT) {
                        Spacer(Modifier.width(6.dp))
                        HudChip(text = "HALT", accent = AlertRed)
                    }
                }
                Text(
                    text = step.displayParam,
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.5.sp),
                    color = IceBlue,
                )
            }
        }
        if (step.action == ActionType.IF_BLOCK || step.action == ActionType.REPEAT_BLOCK) {
            PlanSteps(step.children, depth + 1)
            if (step.elseChildren.isNotEmpty()) {
                Text(
                    text = "ELSE",
                    style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.4.sp),
                    color = TextMuted,
                    modifier = Modifier.padding(start = ((depth + 1) * 14).dp, top = 2.dp, bottom = 3.dp),
                )
                PlanSteps(step.elseChildren, depth + 1)
            }
        }
    }
}

@Composable
private fun SolidPill(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.13f), ChipShape)
            .border(1.dp, accent.copy(alpha = 0.45f), ChipShape)
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
