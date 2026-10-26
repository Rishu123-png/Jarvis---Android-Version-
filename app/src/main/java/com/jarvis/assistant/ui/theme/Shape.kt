package com.jarvis.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A rectangular shape with chamfered (45° cut) corners — the signature silhouette of the
 * JARVIS panels. [cutTopLeft], [cutTopRight], [cutBottomRight] and [cutBottomLeft] let a
 * panel chamfer only selected corners, which is how the HUD keeps panels from looking
 * like plain rounded cards.
 */
class ChamferedShape(
    private val cutTopLeft: Dp = 0.dp,
    private val cutTopRight: Dp = 0.dp,
    private val cutBottomRight: Dp = 0.dp,
    private val cutBottomLeft: Dp = 0.dp,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val maxCut = minOf(size.width, size.height) / 2f
        fun Dp.px(): Float = with(density) { toPx() }.coerceIn(0f, maxCut)

        val tl = cutTopLeft.px()
        val tr = cutTopRight.px()
        val br = cutBottomRight.px()
        val bl = cutBottomLeft.px()

        val path = Path().apply {
            moveTo(tl, 0f)
            lineTo(size.width - tr, 0f)
            lineTo(size.width, tr)
            lineTo(size.width, size.height - br)
            lineTo(size.width - br, size.height)
            lineTo(bl, size.height)
            lineTo(0f, size.height - bl)
            lineTo(0f, tl)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChamferedShape) return false
        return cutTopLeft == other.cutTopLeft &&
            cutTopRight == other.cutTopRight &&
            cutBottomRight == other.cutBottomRight &&
            cutBottomLeft == other.cutBottomLeft
    }

    override fun hashCode(): Int {
        var result = cutTopLeft.hashCode()
        result = 31 * result + cutTopRight.hashCode()
        result = 31 * result + cutBottomRight.hashCode()
        result = 31 * result + cutBottomLeft.hashCode()
        return result
    }
}

/** Fully chamfered panel shape. */
val PanelShape = ChamferedShape(10.dp, 10.dp, 10.dp, 10.dp)

/** Chamfered on the top-left and bottom-right only — used for tiles and chips. */
val TileShape = ChamferedShape(12.dp, 2.dp, 12.dp, 2.dp)

/** Small chip / button shape. */
val ChipShape = ChamferedShape(6.dp, 6.dp, 6.dp, 6.dp)

/**
 * Material 3's [Shapes] only accepts [androidx.compose.foundation.shape.CornerBasedShape]s, so
 * this scale is rounded on purpose. Every JARVIS surface that should be chamfered asks for
 * [PanelShape] / [TileShape] / [ChipShape] explicitly instead of going through the theme.
 */
val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
