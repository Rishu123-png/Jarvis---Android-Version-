package com.jarvis.assistant.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * The calendar the operator already uses.
 *
 * Reads use `CalendarContract.Events`, which is the same list the phone's own calendar app shows —
 * no separate JARVIS calendar, because a second calendar is a second thing to keep in sync and a
 * second place to be wrong. Writes insert into the operator's default calendar, so an event created
 * by voice appears exactly where they would have created it by hand.
 *
 * Both directions need a runtime permission; without it this returns null and the caller says what
 * to grant instead of pretending the calendar is empty.
 */
object CalendarBridge {

    data class Event(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val location: String,
    )

    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    fun canRead(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.WRITE_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    /** Events between now and the end of [days] from now, soonest first. */
    suspend fun upcoming(context: Context, days: Int = 1, limit: Int = 10): List<Event>? =
        withContext(Dispatchers.IO) {
            if (!canRead(context)) return@withContext null
            query(context, System.currentTimeMillis(), System.currentTimeMillis() + days * 86_400_000L, limit)
        }

    /** Everything left today, including something already running. */
    suspend fun today(context: Context, limit: Int = 10): List<Event>? =
        withContext(Dispatchers.IO) {
            if (!canRead(context)) return@withContext null
            val zone = ZoneId.systemDefault()
            val startOfDay = LocalDateTime.now(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            query(context, startOfDay, startOfDay + 86_400_000L, limit)
        }

    /** The next event that has not finished yet, or null. */
    suspend fun next(context: Context): Event? = upcoming(context, days = 30, limit = 1)?.firstOrNull()

    private fun query(context: Context, from: Long, to: Long, limit: Int): List<Event> {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
            "${CalendarContract.Events.DTSTART} <= ? AND " +
            "${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(from.toString(), to.toString())
        val sort = "${CalendarContract.Events.DTSTART} ASC"

        val events = mutableListOf<Event>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                sort,
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIndex = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIndex = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val allDayIndex = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
                val placeIndex = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

                while (cursor.moveToNext() && events.size < limit) {
                    val start = cursor.getLong(startIndex)
                    events.add(
                        Event(
                            title = cursor.getString(titleIndex)?.trim().orEmpty().ifBlank { "Untitled" },
                            startMillis = start,
                            endMillis = if (endIndex >= 0 && !cursor.isNull(endIndex)) {
                                cursor.getLong(endIndex)
                            } else {
                                start + 3_600_000L
                            },
                            allDay = allDayIndex >= 0 && cursor.getInt(allDayIndex) == 1,
                            location = if (placeIndex >= 0) cursor.getString(placeIndex).orEmpty() else "",
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return events
    }

    /**
     * Creates an event in the operator's primary calendar.
     *
     * Returns the event id, or null when writing is not allowed. The id is what makes it possible to
     * offer "undo" later without guessing which of two identical titles was meant.
     */
    suspend fun add(
        context: Context,
        title: String,
        startMillis: Long,
        durationMinutes: Int = 60,
        location: String = "",
    ): Long? = withContext(Dispatchers.IO) {
        if (!canWrite(context)) return@withContext null
        val calendarId = primaryCalendarId(context) ?: return@withContext null

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title.take(200))
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + durationMinutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
        }

        try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.let { ContentUris.parseId(it) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun primaryCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )?.use { cursor ->
                var fallback: Long? = null
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    if (primaryIndex >= 0 && cursor.getInt(primaryIndex) == 1) return@use id
                    if (fallback == null) fallback = id
                }
                fallback
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun openCalendar(context: Context): Intent? {
        val millis = System.currentTimeMillis()
        return try {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, millis),
                    "vnd.android.cursor.item/event",
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (t: Throwable) {
            null
        }
    }

    /** "4:00 PM" — the clock part only, for a line that already says the day. */
    fun clockOf(millis: Long): String = try {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    } catch (t: Throwable) {
        ""
    }
}
