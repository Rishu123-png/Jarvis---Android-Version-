package com.jarvis.assistant.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HairLine
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import com.jarvis.assistant.voice.StepPhase
import com.jarvis.assistant.voice.VoicePhase
import com.jarvis.assistant.voice.VoiceTask

/**
 * The short screen that appears while a command runs.
 *
 * One panel, no scrolling, no navigation: what was heard, what JARVIS is doing step by step, and
 * what it said back. It disappears by itself on success; a failure stays until it is tapped away,
 * because a silent failure is worse than a card that sits there.
 */
@Composable
fun VoiceTaskCard(
    task: VoiceTask,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (task.phase) {
        VoicePhase.FAILED -> AlertRed
        VoicePhase.DONE -> StatusGreen
        VoicePhase.LISTENING -> Amber
        else -> ElectricCyan
    }

    HudPanel(
        modifier = modifier.clickable { if (task.phase.isBusy) onCancel() else onDismiss() },
        label = task.title.ifBlank { "VOICE" },
        accent = accent,
        trailing = { HudChip(text = task.chip, accent = accent, filled = true) },
    ) {
        if (task.heard.isNotBlank()) {
            Text(
                text = "\u201C${task.heard}\u201D",
                style = HudType.valueSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (task.steps.isEmpty() && task.subtitle.isNotBlank()) {
            Text(
                text = task.subtitle,
                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                color = TextMuted,
            )
        }

        task.steps.forEach { step ->
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = glyph(step.phase),
                    style = HudType.valueSmall,
                    color = colorFor(step.phase),
                    modifier = Modifier.width(16.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.label,
                        style = HudType.valueSmall,
                        color = if (step.phase == StepPhase.PENDING) TextMuted else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (step.detail.isNotBlank()) {
                        Text(
                            text = step.detail,
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.6.sp),
                            color = if (step.phase == StepPhase.FAILED) AlertRed.copy(alpha = 0.85f) else TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (task.steps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(HairLine),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(accent),
                )
            }
        }

        if (task.reply.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = task.reply,
                style = HudType.valueSmall,
                color = if (task.phase == VoicePhase.FAILED) AlertRed else IceBlue,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (task.source.isNotBlank()) task.source else "VOICE",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = TextMuted,
            )
            Text(
                text = if (task.phase.isBusy) "TAP TO STOP" else "TAP TO CLOSE",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = accent.copy(alpha = 0.75f),
            )
        }
    }
}

private fun glyph(phase: StepPhase): String = when (phase) {
    StepPhase.PENDING -> "\u00B7"
    StepPhase.ACTIVE -> "\u25B8"
    StepPhase.DONE -> "\u2713"
    StepPhase.FAILED -> "\u2715"
    StepPhase.SKIPPED -> "\u2013"
}

private fun colorFor(phase: StepPhase): Color = when (phase) {
    StepPhase.PENDING -> TextMuted
    StepPhase.ACTIVE -> Amber
    StepPhase.DONE -> StatusGreen
    StepPhase.FAILED -> AlertRed
    StepPhase.SKIPPED -> TextMuted
}
