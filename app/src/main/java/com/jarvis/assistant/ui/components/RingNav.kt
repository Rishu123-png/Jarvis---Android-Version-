package com.jarvis.assistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.HudGeometry
import com.jarvis.assistant.data.HudModule
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import kotlin.math.roundToInt

/**
 * The ring: every module, arranged around the reactor and tappable.
 *
 * This is the one idea taken from the AUGMA concept that is worth its cost. Before it, the modules
 * lived in a grid half a screen down and the reactor was decoration; now the hero is the navigation,
 * and opening the gallery is one gesture from the home screen instead of two.
 *
 * Three decisions keep it honest:
 *
 *  - **Draw once, not every frame.** The canvas reads no animation state, so it paints when the
 *    module list changes and not otherwise. The spinning rings inside [ArcReactor] are untouched.
 *  - **Ten real touch targets, not one clever hit test.** Each segment gets its own 46dp box placed
 *    by trigonometry, so the centre of the dial — the orb, the microphone — is *not* covered and
 *    cannot have its tap stolen. That was the one regression this feature could have caused.
 *  - **Labels are rotated, then flipped.** The bottom half is turned by 180° so nothing on the ring
 *    is read upside down; see [HudGeometry.labelRotation].
 */
@Composable
fun RingNav(
    modules: List<HudModule>,
    onOpen: (HudModule) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = ElectricCyan,
    badges: Map<String, String> = emptyMap(),
    /** Radius of the label/segment band, as a fraction of the outer radius. */
    bandFraction: Float = 0.93f,
    /** Fraction of the outer radius that the inner reactor occupies. Labels sit clear of it. */
    coreFraction: Float = 0.80f,
    /** A segment drawn as chosen — used when this ring is the active navigation. */
    activeId: String? = null,
    /**
     * Turns the whole dial. Landscape passes 90 so the ring reads sideways, level with a phone held
     * in two hands; zero is the portrait default and changes nothing.
     */
    rotationDegrees: Float = 0f,
) {
    if (modules.isEmpty()) return

    var pressedId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val radiusPx = with(density) { minOf(maxWidth, maxHeight).toPx() / 2f }
        val step = 360f / modules.size
        val measurer = rememberTextMeasurer()

        // Measured once per module list, not per frame: laying out text inside onDraw would cost a
        // layout pass sixty times a second.
        val labels = remember(modules) {
            modules.associate { module ->
                module.id to measurer.measure(
                    AnnotatedString(module.title.uppercase()),
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.6.sp),
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = this.center
            val bandRadius = radiusPx * bandFraction
            val bandWidth = radiusPx * (1f - coreFraction) * 0.42f
            val labelRadius = radiusPx * ((coreFraction + bandFraction) / 2f) - bandWidth * 0.1f

            // Crosshair through the whole dial, and four cardinal ticks just outside the band.
            // A single hairline pass: the cheapest way to make the circle read as an instrument.
            val cross = accent.copy(alpha = 0.055f)
            drawLine(cross, Offset(0f, centre.y), Offset(size.width, centre.y), 1.dp.toPx())
            drawLine(cross, Offset(centre.x, 0f), Offset(centre.x, size.height), 1.dp.toPx())
            listOf(0f, 90f, 180f, 270f).forEach { degrees ->
                val (ix, iy) = HudGeometry.pointOn(degrees, bandRadius + bandWidth * 0.75f)
                val (ox, oy) = HudGeometry.pointOn(degrees, radiusPx * 0.995f)
                drawLine(
                    color = accent.copy(alpha = 0.35f),
                    start = Offset(centre.x + ix, centre.y + iy),
                    end = Offset(centre.x + ox, centre.y + oy),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            modules.forEachIndexed { index, module ->
                val centreDegrees = HudGeometry.centerDegrees(index, modules.size) + rotationDegrees
                val selected = module.id == pressedId || module.id == activeId
                val tint = if (selected) IceBlue else module.accent

                // The segment: a thin arc with a gap, so the ring reads as ten keys, not a circle.
                drawArc(
                    color = tint.copy(alpha = if (selected) 0.95f else 0.30f),
                    startAngle = centreDegrees - 90f - step / 2f + 2.5f,
                    sweepAngle = step - 5f,
                    useCenter = false,
                    topLeft = Offset(centre.x - bandRadius, centre.y - bandRadius),
                    size = Size(bandRadius * 2f, bandRadius * 2f),
                    style = Stroke(width = bandWidth, cap = StrokeCap.Butt),
                )

                // A brighter leading edge on the selected key only: one extra arc, no animation.
                if (selected) {
                    drawArc(
                        color = IceBlue,
                        startAngle = centreDegrees - 90f - step / 2f + 2.5f,
                        sweepAngle = 6f,
                        useCenter = false,
                        topLeft = Offset(centre.x - bandRadius, centre.y - bandRadius),
                        size = Size(bandRadius * 2f, bandRadius * 2f),
                        style = Stroke(width = bandWidth, cap = StrokeCap.Round),
                    )
                }

                // The label, lying along the ring and flipped on the lower half.
                val label = labels[module.id]
                val (dx, dy) = HudGeometry.pointOn(centreDegrees, labelRadius)
                val anchor = Offset(centre.x + dx, centre.y + dy)
                if (label != null) {
                    rotate(HudGeometry.labelRotation(centreDegrees), pivot = anchor) {
                        drawText(
                            textLayoutResult = label,
                            color = if (selected) IceBlue else tint.copy(alpha = 0.78f),
                            topLeft = Offset(
                                anchor.x - label.size.width / 2f,
                                anchor.y - label.size.height / 2f,
                            ),
                        )
                    }
                }

                // A badge, e.g. unread count, on the outer edge of its segment.
                val badge = badges[module.id]
                if (!badge.isNullOrBlank()) {
                    val (bx, by) = HudGeometry.pointOn(centreDegrees, bandRadius + bandWidth * 0.7f)
                    drawCircle(
                        color = IceBlue,
                        radius = 2.6.dp.toPx(),
                        center = Offset(centre.x + bx, centre.y + by),
                    )
                }
            }
        }

        // Touch targets. Deliberately not one full-size hit test: the centre of the dial must stay
        // free for the orb underneath.
        modules.forEachIndexed { index, module ->
            val centreDegrees = HudGeometry.centerDegrees(index, modules.size) + rotationDegrees
            val source = remember(module.id) { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            LaunchedEffect(pressed) {
                if (pressed) {
                    pressedId = module.id
                } else if (pressedId == module.id) {
                    pressedId = null
                }
            }

            val touchRadiusPx = radiusPx * ((coreFraction + 1f) / 2f)
            val (tx, ty) = HudGeometry.pointOn(centreDegrees, touchRadiusPx)

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            (tx - 23.dp.toPx()).roundToInt(),
                            (ty - 23.dp.toPx()).roundToInt(),
                        )
                    }
                    .size(46.dp)
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        onClickLabel = "Open ${module.title}",
                        onClick = { onOpen(module) },
                    ),
            )
        }
    }
}
