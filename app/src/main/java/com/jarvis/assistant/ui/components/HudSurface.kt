package com.jarvis.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.DeepNavy
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HairLine
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.PanelShape
import com.jarvis.assistant.ui.theme.TextMuted

/**
 * The standard JARVIS panel: chamfered silhouette, hairline gradient border, machined corner
 * brackets, and an optional section header. Every screen is assembled from this primitive so
 * the HUD stays visually consistent.
 */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Color = ElectricCyan,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(PanelNavy, DeepNavy)), PanelShape)
            .border(1.dp, borderBrush(accent), PanelShape)
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawCornerBrackets(accent, length = 12.dp.toPx(), stroke = 1.5.dp.toPx())
                }
            }
            .padding(contentPadding),
    ) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .background(accent.copy(alpha = 0.9f)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = HudType.sectionTitle,
                    color = accent.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (trailing != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        trailing()
                    }
                }
            }
            HudDivider(modifier = Modifier.padding(top = 9.dp, bottom = 11.dp), accent = accent)
        }
        content()
    }
}

private fun borderBrush(accent: Color): Brush = Brush.verticalGradient(
    listOf(
        accent.copy(alpha = 0.45f),
        HairLine,
        HairLine,
        accent.copy(alpha = 0.14f),
    )
)

/** Hairline rule with a fading gradient — used inside panels instead of a solid divider. */
@Composable
fun HudDivider(
    modifier: Modifier = Modifier,
    accent: Color = ElectricCyan,
    alpha: Float = 0.35f,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = alpha),
                        accent.copy(alpha = alpha * 0.35f),
                        Color.Transparent,
                    )
                )
            ),
    )
}

/** Draws four L-shaped brackets just inside the panel edge. */
fun DrawScope.drawCornerBrackets(
    color: Color,
    length: Float = 12f,
    stroke: Float = 1.5f,
    inset: Float = 0f,
    alpha: Float = 0.75f,
) {
    val w = size.width
    val h = size.height
    val l = minOf(length, w / 3f, h / 3f)
    val c = color.copy(alpha = alpha)
    val s = stroke

    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(c, Offset(x, y), Offset(x + dx * l, y), strokeWidth = s)
        drawLine(c, Offset(x, y), Offset(x, y + dy * l), strokeWidth = s)
    }

    corner(inset, inset, 1f, 1f)
    corner(w - inset, inset, -1f, 1f)
    corner(inset, h - inset, 1f, -1f)
    corner(w - inset, h - inset, -1f, -1f)
}

/**
 * Full-screen HUD backdrop: faint technical grid, a cyan bloom behind the reactor, and a
 * vignette that darkens the edges so the centre reads as the focal plane.
 */
@Composable
fun HudBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF050B14),
                    0.35f to Color(0xFF040A11),
                    1f to Color(0xFF010306),
                )
            )
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 44.dp.toPx()
            val line = Color(0xFF16C8FF).copy(alpha = 0.045f)
            var x = 0f
            while (x <= size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }

            // Bloom behind the reactor.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF16C8FF).copy(alpha = 0.13f),
                        Color(0xFF16C8FF).copy(alpha = 0.04f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * 0.30f),
                    radius = size.width * 0.85f,
                ),
                radius = size.width * 0.85f,
                center = Offset(size.width / 2f, size.height * 0.30f),
            )

            // Vignette.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0xCC01040A)),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.height * 0.78f,
                )
            )

            // Corner reticles for a framed, instrument-panel feel.
            drawCornerBrackets(
                color = Color(0xFF16C8FF),
                length = 26.dp.toPx(),
                stroke = 1.dp.toPx(),
                inset = 10.dp.toPx(),
                alpha = 0.28f,
            )
        }
    }
}

/** Small caps status pill used in headers. */
@Composable
fun HudChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = ElectricCyan,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .background(
                if (filled) accent.copy(alpha = 0.16f) else Color.Transparent,
                com.jarvis.assistant.ui.theme.ChipShape,
            )
            .border(1.dp, accent.copy(alpha = 0.45f), com.jarvis.assistant.ui.theme.ChipShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = HudType.label, color = accent)
    }
}

/** Label on the left, monospace value on the right — the workhorse readout row. */
@Composable
fun ReadoutRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFFE7F6FF),
    accent: Color = ElectricCyan,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = HudType.label,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(1.dp)
                .background(accent.copy(alpha = 0.25f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = HudType.value,
            color = valueColor,
            maxLines = 1,
        )
    }
}

/**
 * Horizontal capability/level bar with a soft leading glow. Values are clamped to 0..1.
 */
@Composable
fun HudMeter(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = ElectricCyan,
    trackColor: Color = Color(0xFF0F2436),
    height: Dp = 5.dp,
) {
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(p)
                .height(height)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.35f), accent)
                    )
                ),
        )
        // Tick marks over the track.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val ticks = 20
            for (i in 0..ticks) {
                val x = size.width * i / ticks
                drawLine(
                    color = Color(0xFF03070D).copy(alpha = 0.55f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
        }
    }
}

/** A slim animated "scan" line sweeping down a container. */
@Composable
fun ScanlineOverlay(
    modifier: Modifier = Modifier,
    progress: Float,
    color: Color = ElectricCyan,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val y = size.height * progress.coerceIn(0f, 1f)
        val bandHeight = 46.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                startY = y - bandHeight,
                endY = y + bandHeight,
            ),
            size = Size(size.width, bandHeight * 2f),
            topLeft = Offset(0f, y - bandHeight),
        )
        drawLine(
            color = color.copy(alpha = 0.30f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
}

/** Simple SVG-ish arrow / caret used by back affordances. */
@Composable
fun BackChevron(color: Color = ElectricCyan, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(16.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.16f)
            lineTo(size.width * 0.30f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.84f)
        }
        drawPath(path, color, style = Stroke(width = 1.8.dp.toPx()))
    }
}
