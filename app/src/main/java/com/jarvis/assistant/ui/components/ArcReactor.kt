package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.CyanDeep
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Arc Reactor HUD.
 *
 * Built entirely from vector drawing so it stays crisp at any size and costs almost nothing
 * per frame. Layers, from outside in:
 *
 *  1. outer halo + 72-tick bezel (every 6th tick long)
 *  2. power arc — the cell charge, drawn as an arc with a soft under-glow
 *  3. clockwise segmented ring (slow) and counter-clockwise dashed ring (faster)
 *  4. radial scale marks and a scanning comet arc
 *  5. dark inner lens with rim light
 *  6. breathing core bloom behind the caller's [content]
 *
 * [content] is centred in the lens, which is where the clock lives.
 */
@Composable
fun ArcReactor(
    modifier: Modifier = Modifier,
    diameter: Dp = 268.dp,
    powerLevel: Int = 100,
    isCharging: Boolean = false,
    accent: Color = ElectricCyan,
    /** 0..1 loudness from [com.jarvis.assistant.voice.Presence]. 0 leaves the orb as it was. */
    level: Float = 0f,
    /** True while he is working something out: an extra fast arc, nothing else. */
    thinking: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "arc-reactor")

    val outerSpin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(46_000, easing = LinearEasing)),
        label = "outer-spin",
    )
    val innerSpin by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(17_000, easing = LinearEasing)),
        label = "inner-spin",
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7_800, easing = LinearEasing)),
        label = "scan",
    )
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.09f,
        animationSpec = infiniteRepeatable(
            tween(2_200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing)),
        label = "orbit",
    )
    // Only drawn while thinking, so the extra motion is a signal rather than decoration.
    val thought by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "thought",
    )

    // A voice lifts the whole disc a little and brightens the halo. Smoothly, so a syllable does not
    // make it jump: the recorder publishes ~20 times a second and this interpolates between them.
    val lift by animateFloatAsState(
        targetValue = 1f + level.coerceIn(0f, 1f) * 0.09f,
        animationSpec = tween(120, easing = LinearEasing),
        label = "lift",
    )

    val powerColor = when {
        isCharging -> StatusGreen
        powerLevel <= 15 -> AlertRed
        powerLevel <= 35 -> Color(0xFFFFC24B)
        else -> accent
    }

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            val stroke = 1.dp.toPx()

            drawReactorHalo(r, c, accent, breathe * lift)
            drawBezelTicks(r, c, accent, stroke)
            drawPowerArc(r, c, powerLevel, powerColor, stroke)
            drawSegmentedRing(r, c, accent, outerSpin, stroke)
            drawDashedRing(r, c, innerSpin)
            drawRadialScale(r, c, accent)
            drawScanComet(r, c, accent, scan)
            drawLens(r, c, accent, breathe * lift)
            drawOrbitingDots(r, c, accent, orbit)
            if (thinking) drawThoughtArc(r, c, accent, thought, stroke)
        }

        Box(
            modifier = Modifier.size(diameter * 0.70f),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/**
 * A fast, short arc that only exists while he is thinking.
 *
 * It is a state, not decoration: the same three phases the card shows, on the disc itself, so a
 * glance at the orb says whether he heard you, is working, or is done.
 */
private fun DrawScope.drawThoughtArc(r: Float, c: Offset, accent: Color, angle: Float, stroke: Float) {
    rotate(angle, pivot = c) {
        drawArc(
            color = accent.copy(alpha = 0.75f),
            startAngle = 0f,
            sweepAngle = 52f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawReactorHalo(r: Float, c: Offset, accent: Color, breathe: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.16f * breathe),
                accent.copy(alpha = 0.05f),
                Color.Transparent,
            ),
            center = c,
            radius = r,
        ),
        radius = r,
        center = c,
    )
}

private fun DrawScope.drawBezelTicks(r: Float, c: Offset, accent: Color, stroke: Float) {
    val ticks = 72
    for (i in 0 until ticks) {
        val angle = Math.toRadians(i * 360.0 / ticks - 90.0)
        val isMajor = i % 6 == 0
        val inner = r * if (isMajor) 0.925f else 0.945f
        val outer = r * 0.995f
        val color = accent.copy(alpha = if (isMajor) 0.55f else 0.22f)
        drawLine(
            color = color,
            start = Offset(
                c.x + (cos(angle) * inner).toFloat(),
                c.y + (sin(angle) * inner).toFloat(),
            ),
            end = Offset(
                c.x + (cos(angle) * outer).toFloat(),
                c.y + (sin(angle) * outer).toFloat(),
            ),
            strokeWidth = if (isMajor) stroke * 1.4f else stroke * 0.8f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawPowerArc(
    r: Float,
    c: Offset,
    powerLevel: Int,
    powerColor: Color,
    stroke: Float,
) {
    val radius = r * 0.90f
    val width = r * 0.030f
    val topLeft = Offset(c.x - radius, c.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    val sweep = (powerLevel.coerceIn(0, 100) / 100f) * 360f

    // Track
    drawArc(
        color = Color(0xFF0E2334),
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = width, cap = StrokeCap.Round),
    )

    if (sweep > 0f) {
        // Under-glow, then the sharp arc on top.
        drawArc(
            color = powerColor.copy(alpha = 0.22f),
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = width * 2.6f, cap = StrokeCap.Round),
        )
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(powerColor.copy(alpha = 0.35f), powerColor),
                center = c,
            ),
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = width, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawSegmentedRing(
    r: Float,
    c: Offset,
    accent: Color,
    spin: Float,
    stroke: Float,
) {
    val radius = r * 0.855f
    val inset = Offset(c.x - radius, c.y - radius)
    val ringSize = Size(radius * 2f, radius * 2f)

    drawCircle(
        color = accent.copy(alpha = 0.10f),
        radius = radius,
        center = c,
        style = Stroke(width = stroke),
    )

    rotate(spin, pivot = c) {
        val segments = 3
        val sweep = 84f
        for (i in 0 until segments) {
            drawArc(
                color = accent.copy(alpha = 0.55f),
                startAngle = i * (360f / segments),
                sweepAngle = sweep,
                useCenter = false,
                topLeft = inset,
                size = ringSize,
                style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round),
            )
            drawArc(
                color = IceBlue.copy(alpha = 0.9f),
                startAngle = i * (360f / segments) + sweep - 8f,
                sweepAngle = 8f,
                useCenter = false,
                topLeft = inset,
                size = ringSize,
                style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawDashedRing(r: Float, c: Offset, spin: Float) {
    val radius = r * 0.735f
    rotate(spin, pivot = c) {
        drawCircle(
            color = IceBlue.copy(alpha = 0.32f),
            radius = radius,
            center = c,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(5.dp.toPx(), 9.dp.toPx()),
                ),
            ),
        )
    }
}

private fun DrawScope.drawRadialScale(r: Float, c: Offset, accent: Color) {
    val marks = 36
    for (i in 0 until marks) {
        val angle = Math.toRadians(i * 360.0 / marks - 90.0)
        val isMajor = i % 6 == 0
        val inner = r * 0.665f
        val outer = r * if (isMajor) 0.695f else 0.678f
        drawLine(
            color = accent.copy(alpha = if (isMajor) 0.35f else 0.16f),
            start = Offset(
                c.x + (cos(angle) * inner).toFloat(),
                c.y + (sin(angle) * inner).toFloat(),
            ),
            end = Offset(
                c.x + (cos(angle) * outer).toFloat(),
                c.y + (sin(angle) * outer).toFloat(),
            ),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.drawScanComet(r: Float, c: Offset, accent: Color, scan: Float) {
    val radius = r * 0.805f
    val inset = Offset(c.x - radius, c.y - radius)
    val ringSize = Size(radius * 2f, radius * 2f)

    rotate(scan, pivot = c) {
        drawArc(
            color = accent.copy(alpha = 0.16f),
            startAngle = 0f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = inset,
            size = ringSize,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawArc(
            color = IceBlue.copy(alpha = 0.85f),
            startAngle = 60f,
            sweepAngle = 10f,
            useCenter = false,
            topLeft = inset,
            size = ringSize,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = IceBlue.copy(alpha = 0.35f),
            radius = 4.dp.toPx(),
            center = Offset(c.x + radius, c.y),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 1.8.dp.toPx(),
            center = Offset(c.x + radius, c.y),
        )
    }
}

private fun DrawScope.drawLens(r: Float, c: Offset, accent: Color, breathe: Float) {
    // Dark glass interior
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF0D2E45),
                Color(0xFF071A29),
                Color(0xFF040F18),
            ),
            center = c,
            radius = r * 0.645f,
        ),
        radius = r * 0.645f,
        center = c,
    )

    // Rim light
    drawCircle(
        color = accent.copy(alpha = 0.42f),
        radius = r * 0.645f,
        center = c,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = accent.copy(alpha = 0.12f),
        radius = r * 0.60f,
        center = c,
        style = Stroke(width = 1.dp.toPx()),
    )

    // Breathing core bloom behind the content
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CyanDeep.copy(alpha = 0.55f * breathe),
                ElectricCyan.copy(alpha = 0.10f),
                Color.Transparent,
            ),
            center = c,
            radius = r * 0.52f,
        ),
        radius = r * 0.52f,
        center = c,
    )

    // Coil ring
    drawCircle(
        color = accent.copy(alpha = 0.22f + 0.08f * (breathe - 1f) * 10f),
        radius = r * 0.47f,
        center = c,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
        ),
    )
}

private fun DrawScope.drawOrbitingDots(r: Float, c: Offset, accent: Color, orbit: Float) {
    val radius = r * 0.735f
    listOf(0f, 120f, 240f).forEachIndexed { index, offset ->
        val angle = Math.toRadians((orbit + offset).toDouble() - 90.0)
        val pos = Offset(
            c.x + (cos(angle) * radius).toFloat(),
            c.y + (sin(angle) * radius).toFloat(),
        )
        val isLead = index == 0
        val dotColor = if (isLead) IceBlue else accent
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    dotColor.copy(alpha = 0.55f),
                    dotColor.copy(alpha = 0f),
                ),
                center = pos,
                radius = 9.dp.toPx(),
            ),
            radius = 9.dp.toPx(),
            center = pos,
        )
        drawCircle(
            color = dotColor.copy(alpha = if (isLead) 0.95f else 0.6f),
            radius = if (isLead) 2.6.dp.toPx() else 1.8.dp.toPx(),
            center = pos,
        )
    }
}
