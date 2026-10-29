package com.jarvis.assistant

import com.jarvis.assistant.data.NeedsYou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NEEDS YOU card.
 *
 * A card that is always full is noise, and a card that shows a confident zero while its source is
 * switched off is a lie. Most of these tests exist to prove the card stays *empty*.
 */
class NeedsYouTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun nothingWaitingMeansNoCardAtAll() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(batteryPercent = 80, alertsAvailable = true, unreadMessages = 0),
            now,
        )
        assertTrue(rows.isEmpty())
        assertNull(NeedsYou.chip(NeedsYou.Facts(batteryPercent = 80), now))
    }

    @Test
    fun anEventInsideTheWindowIsTheTopRow() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(
                nextEventTitle = "Dentist",
                nextEventMillis = now + 20 * minute,
                batteryPercent = 80,
                unreadMessages = 3,
            ),
            now,
        )
        assertEquals(NeedsYou.Kind.EVENT, rows[0].kind)
        assertTrue(rows[0].title.contains("Dentist"))
    }

    @Test
    fun anEventFarAwayIsNotACardRow() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(
                nextEventTitle = "Dentist",
                nextEventMillis = now + 5 * 60 * minute,
                batteryPercent = 80,
            ),
            now,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun aDeadBatteryBeatsUnreadMessagesAndNotAVisitToTheDentist() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(
                nextEventTitle = "Dentist",
                nextEventMillis = now + 90 * minute,
                batteryPercent = 8,
                unreadMessages = 2,
                senders = listOf("Khushi"),
            ),
            now,
        )
        assertEquals(NeedsYou.Kind.BATTERY, rows[0].kind)
        assertTrue(rows[0].title.contains("8%"))
    }

    @Test
    fun aChargingPhoneIsNeverABatteryRow() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(batteryPercent = 5, charging = true),
            now,
        )
        assertTrue(rows.none { it.kind == NeedsYou.Kind.BATTERY })
    }

    @Test
    fun aHealthyBatteryIsNotARow() {
        val rows = NeedsYou.rows(NeedsYou.Facts(batteryPercent = 62), now)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun messagesShowWhoIsWaiting() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(unreadMessages = 3, senders = listOf("Khushi", "Amit", "Khushi")),
            now,
        )
        assertEquals(NeedsYou.Kind.MESSAGES, rows[0].kind)
        assertTrue(rows[0].title.startsWith("3 messages"))
        // Distinct, and no more than two names — the row is one line.
        assertEquals("Khushi, Amit", rows[0].detail)
    }

    @Test
    fun missingAlertAccessIsReportedNotZeroed() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(alertsAvailable = false, unreadMessages = 0),
            now,
        )
        assertEquals(1, rows.size)
        assertEquals(NeedsYou.Kind.ACCESS, rows[0].kind)
    }

    @Test
    fun noMoreThanThreeRowsEver() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(
                alertsAvailable = false,
                nextEventTitle = "Standup",
                nextEventMillis = now + 10 * minute,
                batteryPercent = 6,
                unreadMessages = 12,
                senders = listOf("Khushi"),
            ),
            now,
        )
        assertTrue(rows.size <= NeedsYou.MAX_ROWS)
        assertEquals(3, rows.size)
    }

    @Test
    fun anEventAlreadyStartedIsNotCounted() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(nextEventTitle = "Standup", nextEventMillis = now - 10 * minute),
            now,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun aBlankEventTitleIsIgnored() {
        val rows = NeedsYou.rows(
            NeedsYou.Facts(nextEventTitle = "   ", nextEventMillis = now + minute),
            now,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun theChipIsTheTopRow() {
        val facts = NeedsYou.Facts(
            nextEventTitle = "Dentist",
            nextEventMillis = now + 15 * minute,
            batteryPercent = 70,
        )
        assertEquals(NeedsYou.rows(facts, now).first().title, NeedsYou.chip(facts, now))
    }
}
