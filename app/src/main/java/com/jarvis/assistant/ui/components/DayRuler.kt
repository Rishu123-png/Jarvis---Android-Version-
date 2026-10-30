package com.jarvis.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.HudGeometry
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.TextMuted

/**
 * The whole day in one bar: six in the morning is a sixth of the way along, the dentist is a block,
 * and the cursor is now.
 *
 * The dashboard used to answer "what is next" and nothing else — which is the least useful form of
 * the question at four in the afternoon, when what you want to know is how much of the day is left
 * and what is sitting in it. One bar answers all of it, and it costs a single canvas pass whenever
 * the clock ticks.
 *
 * Nothing here is invented: blocks come from the calendar, and when the calendar cannot be read the
 * bar simply draws empty and says so above it.
 */
@Composable
fun DayRuler(
    blocks: List<HudGeometry.DayBlock>,
    nowFraction: Float,
    modifier: Modifier = Modifier,
    accent: Color = ElectricCyan,
    hasCalendar: Boolean = true,
    caption: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val marks = remember { HudGeometry.hourMarks(24, 5) }
    val markLabels = remember(marks) {
        marks.map { hour ->
            measurer.measure(
                AnnotatedString(String.format(java.util.Locale.US, "%02d", hour)),
                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.4.sp),
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        ) {
            val height = size.height
            val width = size.width
            val barTop = 8.dp.toPx()
            val barHeight = 11.dp.toPx()
            val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

            // Track.
            drawRoundRect(
                color = PanelNavy,
                topLeft = Offset(0f, barTop),
                size = Size(width, barHeight),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.16f),
                topLeft = Offset(0f, barTop),
                size = Size(width, barHeight),
                cornerRadius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )

            // Quarter marks, so the eye can find noon without reading the labels.
            listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
                drawLine(
                    color = accent.copy(alpha = 0.14f),
                    start = Offset(width * fraction, barTop),
                    end = Offset(width * fraction, barTop + barHeight),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Events.
            blocks.forEach { block ->
                val left = width * HudGeometry.clampFraction(block.startFraction)
                val right = width * HudGeometry.clampFraction(block.endFraction)
                val blockWidth = (right - left).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    topLeft = Offset(left, barTop + 1.dp.toPx()),
                    size = Size(blockWidth, barHeight - 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
                )
            }

            // Now.
            val nowX = width * HudGeometry.clampFraction(nowFraction)
            drawLine(
                color = IceBlue,
                start = Offset(nowX, barTop - 4.dp.toPx()),
                end = Offset(nowX, barTop + barHeight + 2.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
            drawCircle(
                color = IceBlue,
                radius = 2.dp.toPx(),
                center = Offset(nowX, barTop - 4.dp.toPx()),
            )

            // Hour labels under the bar.
            if (markLabels.isNotEmpty()) {
                val y = barTop + barHeight + 2.dp.toPx()
                markLabels.forEachIndexed { index, label ->
                    val fraction = index.toFloat() / (markLabels.size - 1).coerceAtLeast(1)
                    val x = (width * fraction - label.size.width / 2f)
                        .coerceIn(0f, width - label.size.width)
                    drawText(
                        textLayoutResult = label,
                        color = TextMuted,
                        topLeft = Offset(x, y),
                    )
                }
            }
        }

        val note = caption ?: when {
            !hasCalendar -> "CALENDAR ACCESS OFF \u00B7 BLOCKS HIDDEN"
            blocks.isEmpty() -> "NOTHING ON THE CALENDAR TODAY"
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note,
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                    color = if (hasCalendar) TextMuted else Color(0xFFFFC24B),
                )
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}
