package com.jarvis.assistant.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The few things kept behind a fingerprint.
 *
 * The Vault is not a second app or a second database: it is one small file of notes, opened only
 * after the system has confirmed the operator's fingerprint or face. What lives there is the sort of
 * thing you would not want on a screen someone else can pick up — a medical note, an account detail,
 * something about a person.
 *
 * Honest about its boundary: the file sits in the app's private storage, which on a normal phone is
 * already unreadable to other apps. The fingerprint gate protects the *screen*, not the disk. If the
 * phone is rooted or the backup is taken, the notes travel with it. That is why this is described as
 * a locked drawer in a locked room, not a safe.
 *
 * Writes go through a temporary file and a rename, so a crash mid-write cannot leave a half-written
 * note that later fails to parse. [encode] and [decode] are pure and covered by `VaultTest`.
 */
object Vault {

    data class Note(val id: String, val at: Long, val text: String)

    /** Somebody's drawer, not a database. The cap keeps the screen fast and the file small. */
    const val MAX_NOTES = 200
    const val MAX_CHARS = 2_000

    private const val FILE = "vault_notes.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun notes(context: Context): List<Note> = try {
        val target = file(context)
        if (target.isFile) decode(target.readText()) else emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** Adds one note. Blank text is refused rather than stored as an empty row. */
    fun add(context: Context, text: String): Note? {
        val trimmed = text.trim().take(MAX_CHARS)
        if (trimmed.isEmpty()) return null

        val existing = notes(context)
        if (existing.size >= MAX_NOTES) return null

        val note = Note(
            id = UUID.randomUUID().toString(),
            at = System.currentTimeMillis(),
            text = trimmed,
        )
        write(context, listOf(note) + existing)
        return note
    }

    fun remove(context: Context, id: String): Boolean {
        val existing = notes(context)
        val kept = existing.filterNot { it.id == id }
        if (kept.size == existing.size) return false
        write(context, kept)
        return true
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (t: Throwable) {
            // Nothing to delete.
        }
    }

    fun count(context: Context): Int = notes(context).size

    private fun write(context: Context, notes: List<Note>) {
        val target = file(context)
        val temp = File(target.parentFile, "$FILE.tmp")
        try {
            temp.writeText(encode(notes.take(MAX_NOTES)))
            if (!temp.renameTo(target)) {
                target.writeText(encode(notes.take(MAX_NOTES)))
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pure codec
    // ---------------------------------------------------------------------------------------------

    fun encode(notes: List<Note>): String = JSONArray().apply {
        notes.forEach { note ->
            put(JSONObject().apply {
                put("id", note.id)
                put("at", note.at)
                put("text", note.text)
            })
        }
    }.toString()

    /** Anything unreadable is skipped rather than failing the whole drawer. */
    fun decode(raw: String?): List<Note> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").trim()
                    if (text.isEmpty()) continue
                    add(
                        Note(
                            id = item.optString("id").ifBlank { index.toString() },
                            at = item.optLong("at", 0L),
                            text = text.take(MAX_CHARS),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
