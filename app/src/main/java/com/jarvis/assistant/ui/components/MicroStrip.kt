package com.jarvis.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.TextMuted

/** One reading: a field name and its value, both in the HUD's smallest voice. */
data class MicroReading(val label: String, val value: String, val accent: Color = ElectricCyan)

/**
 * The strip of tiny live numbers that makes a HUD feel like an instrument instead of a page.
 *
 * The rule for what goes here is strict: **every value is measured, never invented.** A reading
 * that is not available is dropped, not filled with a plausible number — the whole point of a
 * telemetry row is that you can trust a glance at it.
 */
@Composable
fun MicroStrip(
    readings: List<MicroReading>,
    modifier: Modifier = Modifier,
    leadingDot: Color? = null,
) {
    if (readings.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PanelNavy.copy(alpha = 0.55f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.14f), ChipShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (leadingDot != null) {
            PulseDot(color = leadingDot, diameter = 4)
            Spacer(Modifier.width(7.dp))
        }
        readings.forEachIndexed { index, reading ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 7.dp)
                        .size(2.dp)
                        .background(TextMuted.copy(alpha = 0.6f)),
                )
            }
            Text(
                text = reading.label,
                style = HudType.label.copy(fontSize = 7.5.sp, letterSpacing = 0.8.sp),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = reading.value,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.8.sp),
                color = reading.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
