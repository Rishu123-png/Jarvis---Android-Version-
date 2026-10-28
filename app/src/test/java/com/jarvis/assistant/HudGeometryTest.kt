package com.jarvis.assistant

import com.jarvis.assistant.data.HudGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring and the ruler, as arithmetic.
 *
 * Both are the kind of code that looks right and is off by a quarter turn. A label that points at
 * the module next door, or a cursor that sits two hours behind the wall clock, would not be caught
 * by any integration test — so the angles and the fractions are pinned down here instead.
 */
class HudGeometryTest {

    private val radius = 100f
    private val inner = 0.80f
    private val outer = 1.0f

    @Test
    fun theTopOfTheRingIsSectorZero() {
        // Screen coordinates: y grows downwards, so "up" is negative y.
        assertEquals(0, HudGeometry.sectorAt(0f, -90f, radius, inner, outer, 10))
    }

    @Test
    fun theRingRunsClockwise() {
        assertEquals(3, HudGeometry.sectorAt(90f, 0f, radius, inner, outer, 10))
        assertEquals(5, HudGeometry.sectorAt(0f, 90f, radius, inner, outer, 10))
        assertEquals(8, HudGeometry.sectorAt(-90f, 0f, radius, inner, outer, 10))
    }

    @Test
    fun theCentreOfTheDialIsNotOnTheRing() {
        // This is the one that protects the orb: a tap in the middle must never open a module.
        assertNull(HudGeometry.sectorAt(0f, 0f, radius, inner, outer, 10))
        assertNull(HudGeometry.sectorAt(10f, 10f, radius, inner, outer, 10))
    }

    @Test
    fun outsideTheDialIsNothing() {
        assertNull(HudGeometry.sectorAt(0f, -220f, radius, inner, outer, 10))
    }

    @Test
    fun elevenModulesStillCoverTheCircle() {
        val seen = (0 until 360 step 5).mapNotNull { degrees ->
            val radians = Math.toRadians(degrees.toDouble() - 90.0)
            val x = (kotlin.math.cos(radians) * 90.0).toFloat()
            val y = (kotlin.math.sin(radians) * 90.0).toFloat()
            HudGeometry.sectorAt(x, y, radius, inner, outer, 11)
        }.toSet()
        assertEquals(11, seen.size)
        assertTrue(seen.all { it in 0..10 })
    }

    @Test
    fun sectorCentresAreWhereTheLabelsGo() {
        assertEquals(0f, HudGeometry.centerDegrees(0, 10), 0.001f)
        assertEquals(36f, HudGeometry.centerDegrees(1, 10), 0.001f)
        assertEquals(324f, HudGeometry.centerDegrees(9, 10), 0.001f)
        assertEquals(0f, HudGeometry.centerDegrees(7, 0), 0.001f)
    }

    @Test
    fun labelsAreFlippedSoTheyAreNeverUpSideDown() {
        assertEquals(0f, HudGeometry.labelRotation(0f), 0.001f)
        assertEquals(90f, HudGeometry.labelRotation(90f), 0.001f)
        assertEquals(360f, HudGeometry.labelRotation(180f), 0.001f)
        assertEquals(90f, HudGeometry.labelRotation(270f), 0.001f)
        assertEquals(306f, HudGeometry.labelRotation(306f), 0.001f)
        assertEquals(54f, HudGeometry.labelRotation(54f), 0.001f)
        assertEquals(306f, HudGeometry.labelRotation(126f), 0.001f)
    }

    @Test
    fun aPointAtZeroDegreesIsStraightUp() {
        val (x, y) = HudGeometry.pointOn(0f, 50f)
        assertEquals(0f, x, 0.01f)
        assertEquals(-50f, y, 0.01f)

        val (sx, sy) = HudGeometry.pointOn(90f, 50f)
        assertEquals(50f, sx, 0.01f)
        assertEquals(0f, sy, 0.01f)
    }

    // ---------------------------------------------------------------------------------------------
    // The day
    // ---------------------------------------------------------------------------------------------

    private val dayStart = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    @Test
    fun fractionsRunFromMidnightToMidnight() {
        assertEquals(0f, HudGeometry.dayFraction(dayStart, dayStart, dayMs), 0.0001f)
        assertEquals(0.5f, HudGeometry.dayFraction(dayStart + dayMs / 2, dayStart, dayMs), 0.0001f)
        assertEquals(1f, HudGeometry.dayFraction(dayStart + dayMs, dayStart, dayMs), 0.0001f)
    }

    @Test
    fun theCursorIsClampedRatherThanHangingOffTheEnd() {
        assertEquals(0f, HudGeometry.clampFraction(-2f), 0.0001f)
        assertEquals(1f, HudGeometry.clampFraction(9f), 0.0001f)
        assertEquals(0f, HudGeometry.clampFraction(Float.NaN), 0.0001f)
        assertEquals(0.3f, HudGeometry.clampFraction(0.3f), 0.0001f)
    }

    @Test
    fun anEventBecomesABlockAtTheRightPlace() {
        val spans = listOf(
            HudGeometry.Span(dayStart + 9 * 3_600_000L, dayStart + 10 * 3_600_000L),
        )
        val blocks = HudGeometry.dayBlocks(spans, dayStart, dayMs)
        assertEquals(1, blocks.size)
        assertEquals(9f / 24f, blocks[0].startFraction, 0.001f)
        assertEquals(10f / 24f, blocks[0].endFraction, 0.001f)
    }

    @Test
    fun anEventRunningOverMidnightIsClippedNotDropped() {
        val spans = listOf(HudGeometry.Span(dayStart + 23 * 3_600_000L, dayStart + 30 * 3_600_000L))
        val blocks = HudGeometry.dayBlocks(spans, dayStart, dayMs)
        assertEquals(1, blocks.size)
        assertEquals(1f, blocks[0].endFraction, 0.0001f)
    }

    @Test
    fun anEventOutsideTheDayDisappears() {
        val spans = listOf(HudGeometry.Span(dayStart - 7_200_000L, dayStart - 3_600_000L))
        assertTrue(HudGeometry.dayBlocks(spans, dayStart, dayMs).isEmpty())
    }

    @Test
    fun anInstantEventStillGetsAMark() {
        val spans = listOf(HudGeometry.Span(dayStart + 12 * 3_600_000L, dayStart + 12 * 3_600_000L))
        val blocks = HudGeometry.dayBlocks(spans, dayStart, dayMs)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].endFraction > blocks[0].startFraction)
    }

    @Test
    fun blocksComeBackInOrderRegardlessOfInput() {
        val spans = listOf(
            HudGeometry.Span(dayStart + 18 * 3_600_000L, dayStart + 19 * 3_600_000L),
            HudGeometry.Span(dayStart + 6 * 3_600_000L, dayStart + 7 * 3_600_000L),
        )
        val blocks = HudGeometry.dayBlocks(spans, dayStart, dayMs)
        assertTrue(blocks[0].startFraction < blocks[1].startFraction)
    }

    @Test
    fun hourMarksCrossTheWholeDay() {
        assertEquals(listOf(0, 6, 12, 18, 24), HudGeometry.hourMarks(24, 5))
        assertEquals(listOf(0), HudGeometry.hourMarks(24, 1))
    }

    @Test
    fun theDayStartsAtLocalMidnight() {
        // Five and a half hours ahead of UTC, which is where this is being written.
        val offset = (5 * 60 + 30) * 60 * 1000
        val noon = 1_700_000_000_000L
        val start = HudGeometry.startOfDay(noon, offset)
        assertTrue(start <= noon)
        assertTrue(noon - start < dayMs)
        assertEquals(0L, (start + offset) % dayMs)
    }
}
