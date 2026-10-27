package com.jarvis.assistant

import com.jarvis.assistant.ui.components.ClockSnapshot
import com.jarvis.assistant.voice.VoiceParsing
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * Twelve-hour display.
 *
 * The HUD, reminders and alarms all speak the operator's clock. A 24-hour form here is the kind of
 * bug that ships silently and annoys every time the phone is unlocked, so the conversion is pinned.
 */
class TimeFormatTest {

    @Test
    fun clockSnapshotUsesTwelveHourAndMeridiem() {
        val morning = ClockSnapshot.from(LocalDateTime.of(2026, 10, 27, 9, 5, 7))
        assertEquals("9:05", morning.time)
        assertEquals("AM", morning.meridiem)
        assertEquals("07", morning.seconds)
        assertEquals("09:05", morning.time24)
        assertEquals("9:05 AM", morning.label)
        assertEquals("9:05:07 AM", morning.labelWithSeconds)
    }

    @Test
    fun midnightAndNoonAreNotZero() {
        val midnight = ClockSnapshot.from(LocalDateTime.of(2026, 10, 27, 0, 0, 0))
        assertEquals("12:00", midnight.time)
        assertEquals("AM", midnight.meridiem)

        val noon = ClockSnapshot.from(LocalDateTime.of(2026, 10, 27, 12, 0, 0))
        assertEquals("12:00", noon.time)
        assertEquals("PM", noon.meridiem)
    }

    @Test
    fun afternoonIsConverted() {
        val afternoon = ClockSnapshot.from(LocalDateTime.of(2026, 10, 27, 14, 52, 4))
        assertEquals("2:52", afternoon.time)
        assertEquals("PM", afternoon.meridiem)
        assertEquals("14:52", afternoon.time24)
    }

    @Test
    fun storedTimesDisplayAsTwelveHour() {
        assertEquals("6:30 PM", VoiceParsing.clock12("18:30"))
        assertEquals("12:15 AM", VoiceParsing.clock12("00:15"))
        assertEquals("12:00 PM", VoiceParsing.clock12("12:00"))
        assertEquals("7:05 AM", VoiceParsing.clock12("07:05"))
    }

    @Test
    fun invalidTimesAreLeftAlone() {
        assertEquals("soon", VoiceParsing.clock12("soon"))
        assertEquals("25:00", VoiceParsing.clock12("25:00"))
    }
}
