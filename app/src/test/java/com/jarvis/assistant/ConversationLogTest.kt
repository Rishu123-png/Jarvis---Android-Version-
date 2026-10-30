package com.jarvis.assistant

import com.jarvis.assistant.data.ConversationLog
import com.jarvis.assistant.data.ConversationLog.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation record: the line format, reading only the tail, merging an archive without
 * doubling up, and the habit counters. These run on the JVM; the file and preferences are thin
 * wrappers around exactly these functions.
 */
class ConversationLogTest {

    private fun entry(at: Long, heard: String, reply: String = "ok", title: String = "PLAY MUSIC", command: Boolean = true) =
        Entry(at, heard, reply, title, command)

    @Test
    fun aLineRoundTripsAndLongFieldsAreCut() {
        val long = "x".repeat(900)
        val line = ConversationLog.encodeLine(entry(1_000L, long, reply = "hi"))
        val back = ConversationLog.decodeLine(line)!!
        assertEquals(1_000L, back.at)
        assertEquals(400, back.heard.length)
        assertEquals("hi", back.reply)
        assertEquals("PLAY MUSIC", back.title)
        assertTrue(back.command)
    }

    @Test
    fun unusableLinesAreSkippedNotFatal() {
        assertNull(ConversationLog.decodeLine(""))
        assertNull(ConversationLog.decodeLine("   "))
        assertNull(ConversationLog.decodeLine("{not json"))
        assertNull(ConversationLog.decodeLine("{\"t\":5,\"h\":\"\",\"r\":\"x\"}"))
    }

    @Test
    fun tailReadKeepsTheNewestAndDropsAFragment() {
        val lines = (1..10).joinToString("\n") { ConversationLog.encodeLine(entry(it.toLong(), "turn $it")) }
        // Started mid-file: the first line is a fragment and must not be parsed.
        val fragment = "ary\":1}\n" + lines + "\n"
        val tail = ConversationLog.parseTail(fragment, limit = 3, startedMidFile = true)
        assertEquals(listOf("turn 8", "turn 9", "turn 10"), tail.map { it.heard })
    }

    @Test
    fun tailReadSkipsCorruptLinesAndHandlesZeroLimit() {
        val text = ConversationLog.encodeLine(entry(1L, "first")) + "\n{broken\n" +
            ConversationLog.encodeLine(entry(2L, "second")) + "\n"
        assertEquals(listOf("first", "second"), ConversationLog.parseTail(text, 20, startedMidFile = false).map { it.heard })
        assertTrue(ConversationLog.parseTail(text, 0, startedMidFile = false).isEmpty())
    }

    @Test
    fun restoringAnArchiveDoesNotDoubleUpExchanges() {
        val a = ConversationLog.encodeLine(entry(1L, "one"))
        val b = ConversationLog.encodeLine(entry(2L, "two"))
        val existing = a + "\n"
        val incoming = a + "\n" + b + "\n"
        val fresh = ConversationLog.newLinesFor(existing, incoming)
        assertEquals(1, fresh.size)
        assertEquals("two", ConversationLog.decodeLine(fresh[0])!!.heard)
    }

    @Test
    fun timeOfDayGroupsTheHours() {
        assertEquals("morning", ConversationLog.periodOf(7))
        assertEquals("afternoon", ConversationLog.periodOf(13))
        assertEquals("evening", ConversationLog.periodOf(19))
        assertEquals("night", ConversationLog.periodOf(2))
        assertEquals("night", ConversationLog.periodOf(23))
    }

    @Test
    fun habitsCountUpAndOnlyRepeatedOnesAreMentioned() {
        var counts = emptyMap<String, Int>()
        repeat(3) { counts = ConversationLog.bumpHabits(counts, "play music", "evening") }
        counts = ConversationLog.bumpHabits(counts, "weather", "morning")
        val habits = ConversationLog.habitsFrom(counts)
        assertEquals(1, habits.size)
        assertEquals("play music", habits[0].title)
        assertEquals("evening", habits[0].period)
        assertEquals(3, habits[0].count)
        val lines = ConversationLog.habitLines(habits)
        assertTrue(lines[0].contains("play music") && lines[0].contains("evening"))
    }

    @Test
    fun habitCountersStayBounded() {
        var counts = emptyMap<String, Int>()
        repeat(260) { counts = ConversationLog.bumpHabits(counts, "request $it", "night") }
        assertTrue(counts.size <= 200)
    }

    @Test
    fun corruptHabitStorageReadsAsEmpty() {
        assertTrue(ConversationLog.parseHabits("not json").isEmpty())
        assertTrue(ConversationLog.parseHabits(null).isEmpty())
        assertEquals(1, ConversationLog.parseHabits("{\"a|evening\":2,\"b|night\":0}").size)
    }
}
