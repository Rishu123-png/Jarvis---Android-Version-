package com.jarvis.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import com.jarvis.assistant.ui.theme.TileShape
import com.jarvis.assistant.ui.theme.VoidBlack

/**
 * Approval gate for a sensitive automated step.
 *
 * Rendered by the shell rather than by one screen, because a suspended routine must stay
 * answerable from wherever the operator happens to be. When this was drawn only inside the
 * automation screen, navigating away left the engine suspended with nobody able to answer it —
 * a routine that could never finish and could never be started again.
 */
@Composable
fun ConfirmationOverlay(
    workflowName: String,
    actionLabel: String,
    detail: String,
    stepIndex: Int,
    stepCount: Int,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack.copy(alpha = 0.86f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xFF0B1520), TileShape)
                .border(1.dp, Amber.copy(alpha = 0.55f), TileShape)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(color = Amber, diameter = 6)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "CONFIRM ACTION",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.8.sp),
                    color = Amber,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${stepIndex + 1}/$stepCount",
                    style = HudType.label.copy(fontSize = 8.sp),
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = actionLabel,
                style = HudType.value.copy(fontSize = 15.sp),
                color = TextPrimary,
            )
            if (detail.isNotBlank() && detail != "—") {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = detail,
                    style = HudType.terminal.copy(fontSize = 11.sp),
                    color = TextSecondary,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Routine: $workflowName · nothing has happened yet",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.8.sp),
                color = TextMuted,
            )

            Spacer(Modifier.height(14.dp))
            Row {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF14202C), ChipShape)
                        .border(1.dp, TextMuted.copy(alpha = 0.35f), ChipShape)
                        .clickable(onClick = onDecline)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "DECLINE",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Amber.copy(alpha = 0.16f), ChipShape)
                        .border(1.dp, Amber.copy(alpha = 0.55f), ChipShape)
                        .clickable(onClick = onApprove)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "APPROVE",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                        color = Amber,
                    )
                }
            }
        }
    }
}
