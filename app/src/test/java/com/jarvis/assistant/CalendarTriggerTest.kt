package com.jarvis.assistant

import com.jarvis.assistant.automation.ConditionEvaluator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar trigger: one event, one run.
 *
 * The service polls every minute, so "within fifteen minutes of the start" is a window the routine
 * sits inside for fifteen consecutive polls. Firing on every one of them would be a bug the operator
 * would blame on the routine, so the rule is arithmetic and lives here, where it can be checked
 * without waiting for a meeting.
 */
class CalendarTriggerTest {

    private val minute = 60_000L
    private val meeting = 1_000_000_000L

    @Test
    fun firesInsideTheWindow() {
        // Ten minutes before the event, a fifteen-minute window, and nothing fired yet.
        assertTrue(ConditionEvaluator.calendarDue(meeting, 15, meeting - 10 * minute, 0L))
    }

    @Test
    fun doesNotFireTooEarly() {
        assertFalse(ConditionEvaluator.calendarDue(meeting, 15, meeting - 16 * minute, 0L))
    }

    @Test
    fun doesNotFireAfterTheEventHasStarted() {
        assertFalse(ConditionEvaluator.calendarDue(meeting, 15, meeting + minute, 0L))
    }

    @Test
    fun oneEventRunsOnce() {
        val now = meeting - 10 * minute
        val windowStart = meeting - 15 * minute
        // The first poll fires; the next poll, a minute later, sees that it already ran.
        assertTrue(ConditionEvaluator.calendarDue(meeting, 15, now, 0L))
        assertFalse(ConditionEvaluator.calendarDue(meeting, 15, now + minute, now))
        // A run from before the window opened does not count as this event's run.
        assertTrue(ConditionEvaluator.calendarDue(meeting, 15, now, windowStart - minute))
    }

    @Test
    fun anEventAlwaysFiresExactlyAtItsStart() {
        assertTrue(ConditionEvaluator.calendarDue(meeting, 15, meeting, 0L))
        // But not after it — the moment has passed.
        assertFalse(ConditionEvaluator.calendarDue(meeting, 15, meeting + 1, 0L))
    }

    @Test
    fun nonsenseWindowsAreClampedInsteadOfTrusted() {
        // A window of zero would never fire and a window of a week would fire endlessly; both are
        // clamped to something an operator could plausibly have meant.
        assertTrue(ConditionEvaluator.calendarDue(meeting, 0, meeting - minute, 0L))
        assertFalse(ConditionEvaluator.calendarDue(meeting, 0, meeting - 2 * minute, 0L))
        assertFalse(ConditionEvaluator.calendarDue(meeting, 100_000, meeting - 300 * minute, 0L))
    }

    @Test
    fun anEventWithNoStartNeverFires() {
        assertFalse(ConditionEvaluator.calendarDue(0L, 15, System.currentTimeMillis(), 0L))
    }
}
