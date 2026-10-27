package com.jarvis.assistant

import com.jarvis.assistant.data.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Spoken time to a real timestamp. Everything is relative to a fixed `now`, never the wall clock. */
class WhenTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 10, 27, 10, 0) // a Tuesday morning

    @Test
    fun tomorrowAtFourIsTomorrowAtSixteenHundred() {
        val parsed = When.parse("meeting tomorrow at 4 pm", now)
        assertNotNull(parsed)
        assertEquals(LocalDateTime.of(2026, 10, 28, 16, 0), parsed?.at)
    }

    @Test
    fun hinglishWorksTheSameWay() {
        val parsed = When.parse("kal shaam 6 baje", now)
        assertNotNull(parsed)
        assertEquals(LocalDate.of(2026, 10, 28), parsed?.at?.toLocalDate())
        assertEquals(LocalTime.of(18, 0), parsed?.at?.toLocalTime())
    }

    @Test
    fun clockTimesAreParsedOnTheirOwn() {
        assertEquals(LocalTime.of(16, 30), When.parseTime("16:30"))
        assertEquals(LocalTime.of(9, 5), When.parseTime("9:05 am"))
        assertEquals(LocalTime.of(21, 0), When.parseTime("9 pm"))
    }

    @Test
    fun weekdaysBecomeTheNextMatchingDay() {
        // 27 Oct 2026 is a Tuesday, so "monday" is the following Monday.
        assertEquals(LocalDate.of(2026, 11, 2), When.parseDate("monday", now.toLocalDate()))
        assertEquals(LocalDate.of(2026, 10, 28), When.parseDate("wednesday", now.toLocalDate()))
    }

    @Test
    fun aDayPartIsBetterThanNothing() {
        val parsed = When.parse("dinner tomorrow evening", now)
        assertEquals(LocalTime.of(18, 0), parsed?.at?.toLocalTime())
    }

    @Test
    fun aSentenceWithNoDayAndNoClockIsNotGuessed() {
        // Guessing "now" is how assistants book meetings for the instant you asked.
        assertNull(When.parse("hello there", now))
        assertNull(When.parse("open the gallery", now))
    }

    @Test
    fun untilLabelsReadLikeSpeech() {
        val base = 1_000_000_000_000L
        assertEquals("in 5 minutes", When.untilLabel(base + 5 * 60_000L, base))
        assertEquals("in 1 minute", When.untilLabel(base + 60_000L, base))
        assertEquals("in 2 hours", When.untilLabel(base + 2 * 3_600_000L, base))
        assertEquals("now", When.untilLabel(base - 60_000L, base))
    }
}
