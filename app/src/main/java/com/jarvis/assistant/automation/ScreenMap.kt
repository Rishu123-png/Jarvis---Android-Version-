package com.jarvis.assistant.automation

/**
 * One readable thing on the screen, with enough context to act on it.
 *
 * The app used to hand the planner a bare list of strings. That is enough to say "tap Send" and
 * nothing else: an icon-only button has no text at all, two "Send" labels are indistinguishable, and
 * a control the accessibility tree exposes only by resource id was invisible. Coordinates were never
 * carried, so nothing could fall back to a tap when the label was ambiguous.
 *
 * This is the same idea, with the three extra facts the planner needs and nothing more: what kind of
 * widget it is, what Android calls it internally, and where it is. No text content of inputs, no
 * passwords, no screenshots — [JarvisAccessibilityService] fills exactly these fields.
 */
data class ScreenItem(
    val label: String,
    val className: String = "",
    val viewId: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val clickable: Boolean = false,
) {
    /** `#3 "Send" [Button] #com.app:id/send @(540,1200)` */
    fun line(index: Int): String = buildString {
        append('#').append(index).append(" \"").append(label).append('"')
        val kind = className.substringAfterLast('.')
        if (kind.isNotBlank()) append(" [").append(kind).append(']')
        val shortId = viewId.substringAfterLast('/')
        if (shortId.isNotBlank()) append(" #").append(shortId)
        if (x > 0 || y > 0) append(" @(").append(x).append(',').append(y).append(')')
        if (clickable) append(" tappable")
    }
}

/**
 * Rendering and parsing for the screen the planner is looking at.
 *
 * Kept out of the accessibility service so it can be tested without an Android device: this is the
 * part that turns a screen into text a model reads, and a coordinate string back into numbers, and
 * both are worth a test that does not need hardware.
 */
object ScreenMap {

    /** How many rows the planner is shown. More than this and the interesting row gets lost. */
    const val MAX_LINES = 40

    fun render(items: List<ScreenItem>, limit: Int = MAX_LINES): String {
        if (items.isEmpty()) return "(nothing readable)"
        return items.take(limit).mapIndexed { index, item -> item.line(index + 1) }.joinToString("\n")
    }

    /** `"540,1200"` → 540 to 1200. Null when the model wrote something that is not a point. */
    fun parsePoint(param: String): Pair<Int, Int>? {
        val parts = param.trim().removePrefix("(").removeSuffix(")").split(',', ' ', 'x', ':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        if (x < 0 || y < 0) return null
        return x to y
    }

    /**
     * Start and end of a swipe on a screen this wide and tall.
     *
     * Directions are what a person says; coordinates are what the platform needs. A vertical swipe
     * starts at 70% of the height and ends at 30%, which is far enough to move content and still
     * inside the screen on every phone shape, including the ones with gesture bars at the bottom.
     */
    fun swipePath(
        direction: String,
        width: Int,
        height: Int,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        if (width <= 0 || height <= 0) return null
        val midX = width / 2
        val midY = height / 2
        val nearX = width * 8 / 10
        val farX = width * 2 / 10
        val nearY = height * 7 / 10
        val farY = height * 3 / 10
        return when (direction.trim().lowercase()) {
            // A finger moving up moves the page down, which people call "scroll down".
            "down", "up_page", "scroll_down" -> (midX to nearY) to (midX to farY)
            "up", "down_page", "scroll_up" -> (midX to farY) to (midX to nearY)
            "left", "next" -> (nearX to midY) to (farX to midY)
            "right", "previous" -> (farX to midY) to (nearX to midY)
            else -> null
        }
    }
}
