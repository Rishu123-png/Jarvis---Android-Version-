package com.jarvis.assistant

import com.jarvis.assistant.automation.ActionType
import com.jarvis.assistant.automation.AutomationStore
import com.jarvis.assistant.automation.RunRecord
import com.jarvis.assistant.automation.RoutineLinks
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.automation.WorkflowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A routine leaving the app, and coming back as a row in the log.
 *
 * Two seams that are easy to break silently: the link a share hands out (`jarvis://run/<id>`) has to
 * carry the same id the store keys on, and a run's history row has to survive the JSON that used to
 * persist it — still the import path for anyone upgrading from a preference-era build.
 */
class RoutineExportTest {

    private fun routine(id: String = "abc-123", enabled: Boolean = true) = Workflow(
        id = id,
        name = "Morning",
        steps = listOf(WorkflowStep(action = ActionType.OPEN_APP, param = "com.example.app/Main")),
        enabled = enabled,
    )

    @Test
    fun theLinkIsTheSchemeTheManifestClaims() {
        // If these two drift, every shortcut and QR code an operator made stops resolving.
        assertEquals("jarvis://run/abc-123", RoutineLinks.linkFor(routine()))
        assertEquals("jarvis", RoutineLinks.SCHEME)
        assertEquals("run", RoutineLinks.HOST_RUN)
    }

    @Test
    fun anIdWithSpacesIsStillTheStoresId() {
        // Ids are UUIDs in practice, but a hand-written routine can carry anything; the link must not
        // quietly trim or re-encode it, because the lookup is an exact match.
        val awkward = routine(id = "a b")
        assertTrue(RoutineLinks.linkFor(awkward).endsWith("/a b"))
    }

    @Test
    fun aRunRecordSurvivesTheJsonThatUsedToBeItsHome() {
        val original = RunRecord(
            id = "r1",
            workflowId = "w1",
            workflowName = "Morning",
            at = 1_700_000_000_000,
            succeeded = 3,
            failed = 1,
            skipped = 2,
            stopped = false,
            triggerLabel = "Link",
        )
        val back = RunRecord.fromJson(original.toJson())
        assertEquals(original, back)
    }

    @Test
    fun aRecordWithoutAnIdIsDroppedRatherThanImportedBlank() {
        assertNull(RunRecord.fromJson(org.json.JSONObject().put("workflowId", "w1")))
    }

    @Test
    fun okayMeansNothingFailedAndNothingStoppedIt() {
        val clean = RunRecord("a", "w", "n", 0L, 2, 0, 0, false, "Manual")
        assertTrue(clean.ok)
        assertFalse(clean.copy(failed = 1).ok)
        assertFalse(clean.copy(stopped = true).ok)
        // Skipped steps are not failures: a step that decided not to act is behaving.
        assertTrue(clean.copy(skipped = 4).ok)
    }

    @Test
    fun thePresetsAreWellFormedEnoughToRunOrShare() {
        val presets = AutomationStore.presets()
        assertTrue(presets.isNotEmpty())
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        presets.forEach { preset ->
            assertTrue(preset.id.isNotBlank())
            assertTrue(preset.name.isNotBlank())
            assertTrue("${preset.name} has no steps", preset.steps.isNotEmpty())
        }
    }
}
