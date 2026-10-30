package com.jarvis.assistant.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The maths behind the two new pieces of the HUD: the ring around the reactor, and the day ruler.
 *
 * Kept here, with no Android in it, for one reason — this is the part that is easy to get subtly
 * wrong. Which segment did the finger land on? Is a quarter past midnight at the left edge or the
 * right? Those questions have exact answers, and they are covered by `HudGeometryTest` rather than
 * discovered by tapping a phone.
 */
object HudGeometry {

    /** One event on the day ruler, in wall-clock milliseconds. */
    data class Span(val start: Long, val end: Long)

    /** A block on the ruler, already reduced to 0..1 of the day. */
    data class DayBlock(val startFraction: Float, val endFraction: Float)

    private const val DAY_MS = 86_400_000L

    // ---------------------------------------------------------------------------------------------
    // The ring
    // ---------------------------------------------------------------------------------------------

    /**
     * Which sector a touch at ([x], [y]) belongs to, or null when it is not on the ring at all.
     *
     * Coordinates are relative to the centre of the circle. Sector 0 is centred at the **top** and
     * the ring runs clockwise, which is how a dial reads and how the labels are drawn — the two
     * must agree or the labels point at the wrong thing.
     *
     * The band check matters as much as the angle: without it, a tap in the middle of the reactor
     * (where the orb lives) would open a module instead of starting a conversation.
     */
    fun sectorAt(
        x: Float,
        y: Float,
        radius: Float,
        innerFraction: Float,
        outerFraction: Float,
        sectors: Int,
    ): Int? {
        if (sectors <= 0 || radius <= 0f) return null
        val distance = hypot(x, y)
        if (distance < radius * innerFraction || distance > radius * outerFraction) return null

        // atan2 with the y axis flipped: 0 degrees at the top, growing clockwise.
        val degrees = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        val normalised = ((degrees % 360f) + 360f) % 360f
        val step = 360f / sectors
        val index = ((normalised + step / 2f) / step).toInt() % sectors
        return index
    }

    /** The centre of a sector, in degrees clockwise from the top. */
    fun centerDegrees(index: Int, sectors: Int): Float {
        if (sectors <= 0) return 0f
        val step = 360f / sectors
        return ((index.coerceIn(0, sectors - 1) * step) % 360f + 360f) % 360f
    }

    /**
     * How far to rotate a label so it lies along the ring.
     *
     * A dial label that is simply rotated by its angle ends up upside down on the bottom half. So
     * the lower half is flipped by 180 degrees — the convention used on coins and watch faces —
     * which keeps every label readable without moving it.
     */
    fun labelRotation(centerDegrees: Float): Float {
        val normalised = ((centerDegrees % 360f) + 360f) % 360f
        return if (normalised > 90f && normalised < 270f) normalised + 180f else normalised
    }

    /** Where a point on the ring sits, relative to its centre. */
    fun pointOn(centerDegrees: Float, radius: Float): Pair<Float, Float> {
        val radians = Math.toRadians(centerDegrees.toDouble())
        val x = (sin(radians) * radius).toFloat()
        val y = (-cos(radians) * radius).toFloat()
        return x to y
    }

    // ---------------------------------------------------------------------------------------------
    // The day ruler
    // ---------------------------------------------------------------------------------------------

    /** A value clamped to 0..1 — every fraction on the ruler goes through this. */
    fun clampFraction(value: Float): Float = when {
        value.isNaN() -> 0f
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /** Where a moment sits in a day, as 0..1. */
    fun dayFraction(millis: Long, dayStart: Long, dayLength: Long = DAY_MS): Float {
        if (dayLength <= 0L) return 0f
        return clampFraction((millis - dayStart).toFloat() / dayLength.toFloat())
    }

    /**
     * Events reduced to blocks on the ruler.
     *
     * Long events are clipped to the day rather than allowed to hang off the end, zero-length
     * events become a minimum-width mark so they are not invisible, and spans that fall entirely
     * outside the day are dropped instead of piling up at the edges.
     */
    fun dayBlocks(
        spans: List<Span>,
        dayStart: Long,
        dayLength: Long = DAY_MS,
        minWidth: Float = 0.012f,
    ): List<DayBlock> {
        if (dayLength <= 0L) return emptyList()
        val dayEnd = dayStart + dayLength
        return spans.mapNotNull { span ->
            val start = maxOf(span.start, dayStart)
            val end = minOf(maxOf(span.end, span.start), dayEnd)
            if (end <= dayStart || start >= dayEnd) return@mapNotNull null
            val from = dayFraction(start, dayStart, dayLength)
            val to = dayFraction(end, dayStart, dayLength)
            val widened = if (to - from < minWidth) clampFraction(from + minWidth) else to
            DayBlock(from, widened)
        }.sortedBy { it.startFraction }
    }

    /** Hour marks to label under the ruler. Four is the most a phone can show without crowding. */
    fun hourMarks(dayLengthHours: Int = 24, count: Int = 5): List<Int> {
        if (count < 2) return listOf(0)
        return (0 until count).map { index ->
            (index * dayLengthHours / (count - 1))
        }
    }

    /** The start of the local day containing [millis], in epoch milliseconds. */
    fun startOfDay(millis: Long, zoneOffsetMillis: Int): Long {
        val shifted = millis + zoneOffsetMillis
        val midnight = shifted - Math.floorMod(shifted, DAY_MS)
        return midnight - zoneOffsetMillis
    }
}
