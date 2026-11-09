package com.jarvis.assistant

import com.jarvis.assistant.automation.ScreenItem
import com.jarvis.assistant.automation.ScreenMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the planner is shown, and what it is allowed to send back.
 *
 * The screen is the only thing a model gets to see of the phone, so the shape of these lines is not
 * cosmetic: it decides whether an icon-only button can be pressed at all, and whether a coordinate
 * the model invents can reach the touch layer. Both are tested without a device.
 */
class ScreenMapTest {

    @Test
    fun `a line carries the four facts a planner needs`() {
        val line = ScreenItem(
            label = "Send",
            className = "android.widget.Button",
            viewId = "com.app:id/send",
            x = 540,
            y = 1200,
            clickable = true,
        ).line(3)

        assertEquals("#3 \"Send\" [Button] #send @(540,1200) tappable", line)
    }

    @Test
    fun `an icon button with only an id is still addressable`() {
        val line = ScreenItem(
            label = "send",
            className = "android.widget.ImageButton",
            viewId = "com.whatsapp:id/send",
            x = 1024,
            y = 2100,
        ).line(1)

        assertTrue(line.contains("#send"))
        assertTrue(line.contains("@(1024,2100)"))
    }

    @Test
    fun `a row with no coordinates does not pretend to have them`() {
        val line = ScreenItem(label = "Continue").line(2)
        assertEquals("#2 \"Continue\"", line)
    }

    @Test
    fun `rendering numbers every row and stops at the limit`() {
        val items = (1..5).map { ScreenItem(label = "row$it") }
        val rendered = ScreenMap.render(items, limit = 3)

        assertEquals(3, rendered.lines().size)
        assertTrue(rendered.startsWith("#1 \"row1\""))
        assertTrue(rendered.endsWith("#3 \"row3\""))
    }

    @Test
    fun `an empty screen says so instead of returning nothing`() {
        assertEquals("(nothing readable)", ScreenMap.render(emptyList()))
    }

    @Test
    fun `a point is read from the formats a model actually writes`() {
        assertEquals(540 to 1200, ScreenMap.parsePoint("540,1200"))
        assertEquals(540 to 1200, ScreenMap.parsePoint("540, 1200"))
        assertEquals(540 to 1200, ScreenMap.parsePoint("(540,1200)"))
        assertEquals(540 to 1200, ScreenMap.parsePoint("540 x 1200"))
        assertEquals(540 to 1200, ScreenMap.parsePoint("540:1200"))
    }

    @Test
    fun `a point that is not a point is refused`() {
        assertNull(ScreenMap.parsePoint(""))
        assertNull(ScreenMap.parsePoint("send"))
        assertNull(ScreenMap.parsePoint("540"))
        assertNull(ScreenMap.parsePoint("540,1200,3"))
        assertNull(ScreenMap.parsePoint("-5,10"))
        assertNull(ScreenMap.parsePoint("a,b"))
    }

    @Test
    fun `a scroll gesture moves the page the way a person expects`() {
        // "scroll down" must reveal content further down, which needs the finger to travel up.
        val down = ScreenMap.swipePath("down", width = 1080, height = 2000)
        assertEquals(540, down!!.first.first)
        assertTrue("finger starts low", down.first.second > down.second.second)

        val up = ScreenMap.swipePath("up", width = 1080, height = 2000)
        assertTrue("finger starts high", up!!.first.second < up.second.second)
    }

    @Test
    fun `a horizontal swipe travels across the screen`() {
        val left = ScreenMap.swipePath("left", 1080, 2000)!!
        assertTrue(left.first.first > left.second.first)
        assertEquals(1000, left.first.second)

        val right = ScreenMap.swipePath("right", 1080, 2000)!!
        assertTrue(right.first.first < right.second.first)
    }

    @Test
    fun `a direction nobody recognises is refused rather than guessed`() {
        assertNull(ScreenMap.swipePath("sideways", 1080, 2000))
        assertNull(ScreenMap.swipePath("", 1080, 2000))
    }

    @Test
    fun `a screen with no size cannot produce a gesture`() {
        assertNull(ScreenMap.swipePath("down", 0, 0))
    }

    @Test
    fun `every swipe stays inside the screen`() {
        listOf("up", "down", "left", "right").forEach { direction ->
            val (from, to) = ScreenMap.swipePath(direction, 1080, 2340)!!
            listOf(from, to).forEach { (x, y) ->
                assertTrue("$direction x", x in 0..1080)
                assertTrue("$direction y", y in 0..2340)
            }
        }
    }
}
