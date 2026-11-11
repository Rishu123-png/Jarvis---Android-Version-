package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.RetryPolicy
import com.jarvis.assistant.automation.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a retry is a second chance and when it is a second mistake.
 *
 * The engine has retried steps since it was written. It has also retried a failed `SHELL` command,
 * which is the one case where a timeout means "it probably worked" and a retry means "do it twice".
 */
class RetryPolicyTest {

    @Test
    fun reversibleWorkHonoursWhatTheStepAskedFor() {
        val step = WorkflowStep(action = ActionType.SET_VOLUME, param = "30", retries = 2)
        assertEquals(2, RetryPolicy.retriesFor(step))
        assertNull(RetryPolicy.overruledNote(step))
    }

    @Test
    fun irreversibleWorkIsAttemptedExactlyOnce() {
        val step = WorkflowStep(action = ActionType.SHELL, param = "rm -rf /sdcard/tmp", retries = 3)
        assertEquals(0, RetryPolicy.retriesFor(step))
        assertTrue(RetryPolicy.retryable(ActionType.SHELL).not())
    }

    @Test
    fun theCeilingIsTheEnginesCeiling() {
        val step = WorkflowStep(action = ActionType.SET_VOLUME, param = "10", retries = 99)
        assertEquals(RetryPolicy.MAX_ATTEMPTS - 1, RetryPolicy.retriesFor(step))
    }

    @Test
    fun aNegativeOrZeroAskingIsNoRetries() {
        assertEquals(0, RetryPolicy.retriesFor(WorkflowStep(action = ActionType.SET_VOLUME, param = "1", retries = 0)))
    }

    @Test
    fun theOperatorIsToldWhenTheRetryWasRefused() {
        val note = RetryPolicy.overruledNote(WorkflowStep(action = ActionType.SHELL, param = "ls", retries = 2))
        assertNotNull(note)
        assertTrue(note!!.contains("once"))
        // No retries asked for, no note: an unused warning is a warning nobody reads.
        assertNull(RetryPolicy.overruledNote(WorkflowStep(action = ActionType.SHELL, param = "ls", retries = 0)))
    }

    @Test
    fun thePauseGrowsAndStaysBounded() {
        assertTrue(RetryPolicy.backoffMs(1) < RetryPolicy.backoffMs(2))
        assertEquals(RetryPolicy.backoffMs(3), RetryPolicy.backoffMs(9))
        assertTrue(RetryPolicy.backoffMs(1) > 0)
    }
}
