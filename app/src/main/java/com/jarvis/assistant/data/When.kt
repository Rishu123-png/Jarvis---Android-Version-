package com.jarvis.assistant.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "tomorrow at 4", "kal shaam 6 baje", "next monday 10:30" → a real instant.
 *
 * This is the single place that turns spoken time into a timestamp, so a calendar entry, a reminder
 * and an alarm all agree with each other. Pure Kotlin with `now` injected, which is the only way to
 * test a clock without waiting for one.
 */
object When {

    data class Parsed(val at: LocalDateTime, val spoken: String)

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "somvar" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "mangalvar" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "budhvar" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "guruvar" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "shukravar" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY, "shanivar" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY, "ravivar" to DayOfWeek.SUNDAY,
    )

    /** Words that mean "the morning/evening", used when no clock time is spoken. */
    private val DAY_PARTS = mapOf(
        "morning" to LocalTime.of(9, 0), "subah" to LocalTime.of(9, 0), "savere" to LocalTime.of(7, 30),
        "afternoon" to LocalTime.of(14, 0), "dopahar" to LocalTime.of(14, 0),
        "evening" to LocalTime.of(18, 0), "shaam" to LocalTime.of(18, 0), "sham" to LocalTime.of(18, 0),
        "night" to LocalTime.of(21, 0), "raat" to LocalTime.of(21, 0),
    )

    /**
     * Parses the date and time inside a sentence, if there is one.
     *
     * Returns null when the sentence names no day and no clock, so callers can ask instead of
     * guessing "now" — which is how assistants end up creating a meeting for the moment you asked.
     */
    fun parse(
        text: String,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Parsed? {
        val clean = PhraseBook.clean(text)
        if (clean.isBlank()) return null

        val date = parseDate(clean, now.toLocalDate()) ?: return null
        val time = parseTime(clean) ?: LocalTime.of(9, 0)
        val at = LocalDateTime.of(date, time)

        if (date == now.toLocalDate() && at.isBefore(now) && parseTime(clean) == null) {
            // "today" with no clock and the morning already gone means the evening.
            return Parsed(at.plusHours(12), spoken(at, zone))
        }
        return Parsed(at, spoken(at = at, zone = zone))
    }

    /** The date a sentence refers to, or null when it names none. */
    fun parseDate(clean: String, today: LocalDate): LocalDate? {
        return when {
            clean.contains("day after tomorrow") ||
                clean.contains("parso") ||
                clean.contains("parson") -> today.plusDays(2)

            clean.contains("tomorrow") || clean.contains("kal") -> today.plusDays(1)

            clean.contains("today") || clean.contains("aaj") ||
                clean.contains("tonight") || clean.contains("aaj raat") -> today

            clean.contains("next week") || clean.contains("agle hafte") -> today.plusWeeks(1)

            else -> {
                val word = clean.split(' ', '-').firstOrNull { it in WEEKDAYS }
                    ?: return null
                val target = WEEKDAYS.getValue(word)
                var date = today.plusDays(1)
                while (date.dayOfWeek != target) date = date.plusDays(1)
                // "monday" said on a Monday evening should mean the coming one, not today.
                if (clean.contains("next")) date = date.plusWeeks(0)
                date
            }
        }
    }

    /** "4pm", "4 pm", "16:30", "6 baje", "shaam 6" — the clock inside a sentence. */
    fun parseTime(clean: String): LocalTime? {
        // 16:30 / 9:05
        Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?").find(clean)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val suffix = match.groupValues[3]
            return clock(hour, minute, suffix)
        }

        // "4 pm" / "4pm" / "at 4"
        Regex("\\b(?:at|by)?\\s*(\\d{1,2})\\s*(am|pm)\\b").find(clean)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            return clock(hour, 0, match.groupValues[2])
        }

        // "6 baje" / "6 baje shaam"
        Regex("\\b(\\d{1,2})\\s*baje?\\b").find(clean)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val eveningish = clean.contains("shaam") || clean.contains("sham") ||
                clean.contains("raat") || clean.contains("evening")
            val adjusted = if (eveningish && hour in 1..11) hour + 12 else hour
            return clock(adjusted, 0, "")
        }

        // "at 4" with no suffix is ambiguous; only used when a day word is present.
        Regex("\\bat\\s+(\\d{1,2})\\b").find(clean)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            return clock(hour, 0, "")
        }

        val part = DAY_PARTS.entries.firstOrNull { clean.contains(it.key) }
        return part?.value
    }

    private fun clock(hour: Int, minute: Int, suffix: String): LocalTime? {
        if (minute !in 0..59) return null
        return when (suffix.lowercase(Locale.US)) {
            "am" -> if (hour in 1..12) LocalTime.of(hour % 12, minute) else null
            "pm" -> if (hour in 1..12) LocalTime.of((hour % 12) + 12, minute) else null
            else -> if (hour in 0..23) LocalTime.of(hour, minute) else null
        }
    }

    /** "4:00 PM" / "tomorrow 4:00 PM" — short enough to speak. */
    fun spoken(at: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): String {
        val today = LocalDate.now(zone)
        val day = when (at.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> at.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US))
        }
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return "$day at $time"
    }

    /** "in 25 minutes", "in 2 hours" — for a brief that has to be heard, not read. */
    fun untilLabel(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = ((targetMillis - nowMillis) / 60_000L).toInt()
        return when {
            minutes <= 0 -> "now"
            minutes == 1 -> "in 1 minute"
            minutes < 60 -> "in $minutes minutes"
            minutes < 120 -> "in ${minutes / 60} hour"
            minutes < 60 * 24 -> "in ${minutes / 60} hours"
            else -> "in ${minutes / (60 * 24)} days"
        }
    }
}
