package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.AutomationFingerprint
import com.jarvis.assistant.automation.RoutineTrigger
import com.jarvis.assistant.automation.TriggerKind
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canary that catches a routine changing on its own.
 *
 * A routine is a program the phone runs later, sometimes while the operator is asleep. What is
 * hashed is deliberately narrow: the steps and the trigger, never the name or the on/off switch,
 * because renaming a routine is not a change to what it would do and a warning that fires for
 * harmless edits is a warning nobody reads.
 */
class AutomationFingerprintTest {

    private fun routine(
        name: String = "Morning",
        steps: List<WorkflowStep> = listOf(WorkflowStep(action = ActionType.TORCH_ON)),
        trigger: RoutineTrigger = RoutineTrigger(),
        enabled: Boolean = true,
    ) = Workflow(id = "w1", name = name, steps = steps, trigger = trigger, enabled = enabled)

    @Test
    fun `the same program always hashes the same`() {
        assertEquals(
            AutomationFingerprint.of(routine()),
            AutomationFingerprint.of(routine()),
        )
    }

    @Test
    fun `a hash is lowercase hex of sha-256`() {
        val hash = AutomationFingerprint.of(routine())
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `sha-256 matches the known vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AutomationFingerprint.sha256("abc"),
        )
    }

    @Test
    fun `changing a step changes the hash`() {
        val before = AutomationFingerprint.of(routine())
        val after = AutomationFingerprint.of(
            routine(steps = listOf(WorkflowStep(action = ActionType.TORCH_OFF))),
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `changing a parameter changes the hash`() {
        val before = AutomationFingerprint.of(
            routine(steps = listOf(WorkflowStep(action = ActionType.SAY, param = "hello"))),
        )
        val after = AutomationFingerprint.of(
            routine(steps = listOf(WorkflowStep(action = ActionType.SAY, param = "goodbye"))),
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `changing the trigger changes the hash`() {
        val before = AutomationFingerprint.of(routine())
        val after = AutomationFingerprint.of(
            routine(
                trigger = RoutineTrigger(kind = TriggerKind.DAILY, param = "07:30"),
            ),
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `renaming a routine is not a change to the program`() {
        assertEquals(
            AutomationFingerprint.of(routine(name = "Morning")),
            AutomationFingerprint.of(routine(name = "Evening")),
        )
    }

    @Test
    fun `switching a routine off is not a change to the program`() {
        assertEquals(
            AutomationFingerprint.of(routine(enabled = true)),
            AutomationFingerprint.of(routine(enabled = false)),
        )
    }

    @Test
    fun `a stamped routine matches itself`() {
        val stamped = routine().stamped()
        assertTrue(stamped.fingerprint.isNotBlank())
        assertTrue(AutomationFingerprint.matches(stamped))
    }

    @Test
    fun `an edited routine no longer matches its stamp`() {
        val stamped = routine().stamped()
        val tampered = stamped.copy(
            steps = stamped.steps + WorkflowStep(action = ActionType.MESSAGE, param = "sms|x|hi"),
        )
        assertFalse(AutomationFingerprint.matches(tampered))
    }

    @Test
    fun `a routine saved before fingerprints existed is not refused`() {
        // Blank means "never stamped". Refusing here would break every routine already on the
        // phone the first time this build started, which is a worse failure than the one the
        // check exists to catch.
        val legacy = routine()
        assertEquals("", legacy.fingerprint)
        assertTrue(AutomationFingerprint.matches(legacy))
    }

    @Test
    fun `the fingerprint survives a round trip through json`() {
        val stamped = routine().stamped()
        val restored = Workflow.fromJson(stamped.toJson())
        assertEquals(stamped.fingerprint, restored?.fingerprint)
        assertTrue(AutomationFingerprint.matches(restored!!))
    }

    @Test
    fun `the short form is the first eight characters`() {
        val stamped = routine()
        assertEquals(
            AutomationFingerprint.of(stamped).take(8),
            AutomationFingerprint.shortOf(stamped),
        )
    }
}
