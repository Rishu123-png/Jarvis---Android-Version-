package com.jarvis.assistant

import com.jarvis.assistant.data.CrewSnapshot
import com.jarvis.assistant.data.Lane
import com.jarvis.assistant.data.LaneReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane rules, as values.
 *
 * The lock itself is exercised on a device, but what it *means* is decided here: which lanes are
 * exclusive, what the board says while one of them is waiting, and that an idle board never claims
 * to be busy.
 */
class CrewTest {

    @Test
    fun onlyTheScreenLanesAreExclusive() {
        assertTrue(Lane.DEVICE.exclusive)
        assertTrue(Lane.AUTOMATE.exclusive)
        assertFalse(Lane.RESEARCH.exclusive)
        assertFalse(Lane.NOTIFY.exclusive)
    }

    @Test
    fun theBoardIsOrderedWithThePhoneFirst() {
        assertEquals(
            listOf(Lane.DEVICE, Lane.AUTOMATE, Lane.RESEARCH, Lane.NOTIFY),
            CrewSnapshot.ORDER,
        )
    }

    @Test
    fun anIdleBoardSaysSo() {
        val snapshot = CrewSnapshot()
        assertEquals(4, snapshot.reports.size)
        assertEquals("ALL IDLE", snapshot.summary())
        assertTrue(snapshot.active.isEmpty())
        snapshot.reports.forEach { report ->
            assertEquals("IDLE", report.status)
            assertEquals("nothing running", report.line)
        }
    }

    @Test
    fun aBusyLaneReportsWhoHasIt() {
        val busy = LaneReport(Lane.DEVICE, busy = true, owner = "VOICE", detail = "OPEN GALLERY")
        val snapshot = CrewSnapshot(
            Lane.entries.map { if (it == Lane.DEVICE) busy else LaneReport(it) },
        )
        assertEquals("LIVE", snapshot.of(Lane.DEVICE).status)
        assertEquals("VOICE · OPEN GALLERY", snapshot.of(Lane.DEVICE).line)
        assertEquals("1 LIVE · PHONE IN USE", snapshot.summary())
        assertEquals(1, snapshot.active.size)
    }

    @Test
    fun aWaitingLaneSaysItIsWaiting() {
        val queued = LaneReport(Lane.AUTOMATE, queued = true, owner = "ROUTINE", detail = "Evening")
        assertEquals("QUEUED", queued.status)
        assertEquals("waiting for the phone", queued.line)
        // A queued lane is not active — it is not doing anything yet.
        val snapshot = CrewSnapshot(
            Lane.entries.map { if (it == Lane.AUTOMATE) queued else LaneReport(it) },
        )
        assertTrue(snapshot.active.isEmpty())
    }

    @Test
    fun readsRunAlongsideEachOther() {
        val research = LaneReport(Lane.RESEARCH, busy = true, owner = "LOOKUP", detail = "black hole")
        val notify = LaneReport(Lane.NOTIFY, busy = true, owner = "NOTIFY")
        val snapshot = CrewSnapshot(
            Lane.entries.map {
                when (it) {
                    Lane.RESEARCH -> research
                    Lane.NOTIFY -> notify
                    else -> LaneReport(it)
                }
            },
        )
        assertEquals(2, snapshot.active.size)
        assertEquals("2 LIVE", snapshot.summary())
    }

    @Test
    fun anUnknownIdIsNotALane() {
        assertNull(Lane.fromId("nonsense"))
        assertNull(Lane.fromId(null))
        assertEquals(Lane.DEVICE, Lane.fromId("device"))
    }

    @Test
    fun aSnapshotAlwaysHasAllFourLanes() {
        val partial = CrewSnapshot.from(mapOf(Lane.RESEARCH to LaneReport(Lane.RESEARCH, busy = true)))
        assertEquals(4, partial.reports.size)
        assertTrue(partial.of(Lane.RESEARCH).busy)
        assertFalse(partial.of(Lane.DEVICE).busy)
    }
}
