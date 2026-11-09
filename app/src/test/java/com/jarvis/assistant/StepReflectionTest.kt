package com.jarvis.assistant

import com.jarvis.assistant.automation.ScreenItem
import com.jarvis.assistant.automation.StepReflection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Did the tap do anything?
 *
 * The rule that matters is the boring one: an action that must change the screen and did not is a
 * miss, and saying so early is worth more than any amount of cleverness later.
 */
class StepReflectionTest {

    private fun item(label: String, id: String = "", clickable: Boolean = true) =
        ScreenItem(label = label, className = "android.widget.TextView", viewId = id, x = 10, y = 20, clickable = clickable)

    // ---------------------------------------------------------------------------------------------
    // The verdict
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aDifferentScreenIsProgress() {
        assertEquals(
            StepReflection.Verdict.PROGRESSED,
            StepReflection.of("press", "a|b", "a|b|c"),
        )
    }

    @Test
    fun theSameScreenAfterAPressIsAMiss() {
        assertEquals(
            StepReflection.Verdict.NO_EFFECT,
            StepReflection.of("press", "a|b", "a|b"),
        )
    }

    @Test
    fun theSameScreenAfterAWaitIsFine() {
        // Waiting for a spinner and finding the same page is a success, not a miss.
        assertEquals(
            StepReflection.Verdict.PROGRESSED,
            StepReflection.of("wait", "a|b", "a|b"),
        )
    }

    @Test
    fun aNewAppInFrontIsProgressEvenWhenTheTextMatches() {
        assertEquals(
            StepReflection.Verdict.PROGRESSED,
            StepReflection.of("open_app", "Settings", "Settings", "com.android.settings", "com.whatsapp"),
        )
    }

    @Test
    fun anUnreadableScreenIsBlindNotAFailure() {
        assertEquals(StepReflection.Verdict.BLIND, StepReflection.of("press", "a|b", ""))
        assertEquals(StepReflection.Verdict.BLIND, StepReflection.of("press", "a|b", null))
    }

    @Test
    fun withoutABeforeThereIsNothingToJudge() {
        assertEquals(StepReflection.Verdict.NOT_CHECKED, StepReflection.of("press", null, "a|b"))
        assertEquals(StepReflection.Verdict.NOT_CHECKED, StepReflection.of("press", "", "a|b"))
    }

    // ---------------------------------------------------------------------------------------------
    // The second attempt
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theRetryUsesTheResourceIdOfTheClickedRow() {
        val items = listOf(
            item("Send", "com.whatsapp:id/send", clickable = true),
            item("Search", "com.whatsapp:id/search", clickable = true),
        )
        assertEquals("#send", StepReflection.retryTarget("Send", items))
    }

    @Test
    fun anAmbiguousLabelPrefersTheClickableRow() {
        val items = listOf(
            item("Divik", "com.app:id/row_title", clickable = false),
            item("Divik", "com.app:id/row", clickable = true),
        )
        assertEquals("#row", StepReflection.retryTarget("Divik", items))
    }

    @Test
    fun nothingBetterToTryIsNull() {
        assertNull(StepReflection.retryTarget("Send", emptyList()))
        assertNull(StepReflection.retryTarget("Send", listOf(item("Send", id = ""))))
        // Already the exact form: retrying it identically would just fail identically.
        assertNull(StepReflection.retryTarget("#send", listOf(item("Send", "com.app:id/send"))))
        assertNull(StepReflection.retryTarget("", listOf(item("Send", "com.app:id/send"))))
    }

    @Test
    fun theShortIdIsWhatTheServiceMatchesOn() {
        val items = listOf(item("Play", "com.spotify.music:id/play_button"))
        assertEquals("#play_button", StepReflection.retryTarget("Play", items))
    }
}
