package com.jarvis.assistant.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.Locale

/**
 * Every conversation he has had, kept on this phone.
 *
 * One line of JSON per exchange, appended to a file in the app's private storage. Appending is
 * the cheapest write there is, and a line that half-wrote after a crash costs one entry, not the
 * whole history, because the reader skips any line it cannot parse.
 *
 * Stability rules this file follows:
 *  - Reads only ever touch the tail of the file ([TAIL_BYTES]), so a year of conversation does
 *    not make the next reply slower. The full file is read only when exporting.
 *  - Writes happen off the main thread (the callers use Dispatchers.IO) and every failure is
 *    swallowed: a memory that cannot be written must never break the reply the operator is
 *    waiting for.
 *  - Nothing polls. The screen that shows the count reads it once when it is opened.
 *
 * Habits are the small part that learns the operator's routine: how often each kind of request
 * comes in, grouped by time of day. They are a few counters, not a model, and they live in
 * SharedPreferences so the archive carries them with no special handling.
 */
object ConversationLog {

    private const val FILE = "conversations.jsonl"
    private const val PREFS = "jarvis_conversations"
    private const val KEY_PAUSED = "paused"
    private const val KEY_HABITS = "habits"

    /** The newest bytes read for a reply. About 250 KB is hundreds of exchanges. */
    const val TAIL_BYTES = 256L * 1024L
    private const val MAX_FIELD = 400
    private const val MAX_HABITS = 200
    private const val MIN_HABIT_COUNT = 3

    private val lock = Any()

    data class Entry(
        val at: Long,
        val heard: String,
        val reply: String,
        val title: String,
        val command: Boolean,
    )

    data class Habit(val title: String, val period: String, val count: Int)

    // ---------------------------------------------------------------------------------------------
    // Pure codec: no Context, so the rules can be tested on the JVM
    // ---------------------------------------------------------------------------------------------

    fun encodeLine(entry: Entry): String = JSONObject()
        .put("t", entry.at)
        .put("h", entry.heard.take(MAX_FIELD))
        .put("r", entry.reply.take(MAX_FIELD))
        .put("c", entry.title.take(28))
        .put("k", if (entry.command) "c" else "x")
        .toString()

    /** Null for anything that is not a usable exchange, including a line cut off by a crash. */
    fun decodeLine(line: String): Entry? {
        if (line.isBlank()) return null
        val item = try {
            JSONObject(line)
        } catch (t: Throwable) {
            return null
        }
        val heard = item.optString("h")
        if (heard.isBlank()) return null
        return Entry(
            at = item.optLong("t", 0L),
            heard = heard,
            reply = item.optString("r"),
            title = item.optString("c"),
            command = item.optString("k") == "c",
        )
    }

    /**
     * The last [limit] usable entries, oldest first.
     *
     * When the read started part-way through the file, its first line is probably a fragment, so it
     * is dropped rather than parsed.
     */
    fun parseTail(text: String, limit: Int, startedMidFile: Boolean): List<Entry> {
        if (limit <= 0) return emptyList()
        val lines = text.split('\n')
        val usable = if (startedMidFile) lines.drop(1) else lines
        return usable.mapNotNull { decodeLine(it) }.takeLast(limit)
    }

    /** The lines in [incoming] that [existing] does not already hold, so a restore never doubles up. */
    fun newLinesFor(existing: String, incoming: String): List<String> {
        val seen = existing.split('\n').mapNotNull { decodeLine(it) }
            .map { it.at.toString() + "|" + it.heard }
            .toMutableSet()
        return incoming.split('\n').mapNotNull { decodeLine(it) }
            .filter { seen.add(it.at.toString() + "|" + it.heard) }
            .map { encodeLine(it) }
    }

    fun periodOf(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    /** Adds one request to the counters, keeping only the most used when there are too many. */
    fun bumpHabits(counts: Map<String, Int>, title: String, period: String): Map<String, Int> {
        val key = title.trim().lowercase(Locale.US).take(28) + "|" + period
        if (key.startsWith("|")) return counts
        val updated = counts.toMutableMap()
        updated[key] = (updated[key] ?: 0) + 1
        if (updated.size > MAX_HABITS) {
            val keep = updated.entries.sortedByDescending { it.value }.take(MAX_HABITS).map { it.key }.toSet()
            updated.keys.retainAll(keep)
        }
        return updated
    }

    fun parseHabits(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val item = JSONObject(raw)
            item.keys().asSequence().associateWith { item.optInt(it, 0) }.filterValues { it > 0 }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    /** The habits worth mentioning: repeated ones only, most common first. */
    fun habitsFrom(counts: Map<String, Int>, limit: Int = 8): List<Habit> =
        counts.mapNotNull { (key, count) ->
            val split = key.lastIndexOf('|')
            if (split <= 0 || count < MIN_HABIT_COUNT) return@mapNotNull null
            Habit(key.substring(0, split), key.substring(split + 1), count)
        }.sortedByDescending { it.count }.take(limit)

    /** Short sentences for the prompt, e.g. `"play music" often in the evening (7 times)`. */
    fun habitLines(habits: List<Habit>): List<String> = habits.take(5).map {
        "\"${it.title.lowercase(Locale.US)}\" often in the ${it.period} (${it.count} times)"
    }

    // ---------------------------------------------------------------------------------------------
    // Device side
    // ---------------------------------------------------------------------------------------------

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    /** Records one exchange. Silently does nothing while recording is paused or the disk refuses. */
    fun append(
        context: Context,
        heard: String,
        reply: String,
        title: String,
        command: Boolean,
        at: Long = System.currentTimeMillis(),
    ) {
        if (heard.isBlank() || isPaused(context)) return
        val entry = Entry(at, heard.trim(), reply.trim(), title.trim(), command)
        synchronized(lock) {
            val written = try {
                file(context).appendText(encodeLine(entry) + "\n", Charsets.UTF_8)
                true
            } catch (t: Throwable) {
                false
            }
            if (!written) return
            if (command && entry.title.isNotBlank()) {
                try {
                    val hour = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
                    val counts = parseHabits(prefs(context).getString(KEY_HABITS, null))
                    val updated = bumpHabits(counts, entry.title, periodOf(hour))
                    prefs(context).edit().putString(KEY_HABITS, JSONObject(updated).toString()).apply()
                } catch (t: Throwable) {
                    // The habit counter is a nicety; the conversation itself is already safe.
                }
            }
        }
    }

    /** The newest exchanges, oldest first. Reads only the tail of the file. */
    fun recent(context: Context, limit: Int): List<Entry> {
        synchronized(lock) {
            return try {
                val target = file(context)
                if (!target.isFile || limit <= 0) {
                    emptyList()
                } else {
                    RandomAccessFile(target, "r").use { raf ->
                        val length = raf.length()
                        val start = maxOf(0L, length - TAIL_BYTES)
                        raf.seek(start)
                        val bytes = ByteArray((length - start).toInt())
                        raf.readFully(bytes)
                        parseTail(String(bytes, Charsets.UTF_8), limit, startedMidFile = start > 0L)
                    }
                }
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    /** How many exchanges are stored. Counts lines, so call it once when a screen opens. */
    fun count(context: Context): Int {
        synchronized(lock) {
            return try {
                val target = file(context)
                if (!target.isFile) 0
                else target.useLines { lines -> lines.count { decodeLine(it) != null } }
            } catch (t: Throwable) {
                0
            }
        }
    }

    fun sizeBytes(context: Context): Long = try {
        file(context).takeIf { it.isFile }?.length() ?: 0L
    } catch (t: Throwable) {
        0L
    }

    fun habits(context: Context): List<Habit> =
        habitsFrom(parseHabits(prefs(context).getString(KEY_HABITS, null)))

    /** The habit lines for the prompt. Empty until something has been asked three times. */
    fun habitLines(context: Context): List<String> = habitLines(habits(context))

    /** The whole log as text, for the archive. */
    fun exportText(context: Context): String = synchronized(lock) {
        try {
            file(context).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    /** Adds the lines from an archive that are not already here. Returns how many were added. */
    fun merge(context: Context, incoming: String): Int = synchronized(lock) {
        try {
            val target = file(context)
            val existing = if (target.isFile) target.readText(Charsets.UTF_8) else ""
            val fresh = newLinesFor(existing, incoming)
            if (fresh.isNotEmpty()) {
                target.appendText(fresh.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
            }
            fresh.size
        } catch (t: Throwable) {
            0
        }
    }

    /** Wipes the conversations and the habits. Recording state is kept, so pausing survives. */
    fun clear(context: Context) {
        synchronized(lock) {
            try {
                file(context).delete()
            } catch (t: Throwable) {
                // Nothing to undo: a file that cannot be deleted is reported by the next count.
            }
            prefs(context).edit().remove(KEY_HABITS).apply()
        }
    }
}
