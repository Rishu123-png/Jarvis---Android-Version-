package com.jarvis.assistant.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/**
 * Everything he knows about you, in one file you own.
 *
 * A phone gets replaced, wiped, or dropped. Without this, all of it — who your sister is, what you
 * asked him last month, the routines you spent an evening arranging — dies with the handset, and
 * nobody would blame you for never telling him anything again.
 *
 * So the whole memory is written to a single JSON file: every preferences bucket, every key, its
 * type, nothing else. It goes wherever the operator sends it — a share sheet, a folder, their own
 * Drive. There is no JARVIS server, no account, no sync service, and nothing is uploaded anywhere.
 *
 * **The AI credential is deliberately excluded.** [EXCLUDED] names the bucket that holds it, because
 * an export file travels by chat and email, and a file with an API key in it is a file that will
 * eventually be forwarded. The key is re-entered on the new phone in ten seconds; a leaked key is
 * somebody else's bill.
 */
object Archive {

    /** Buckets that never leave the device. The AI key lives in this one, encrypted. */
    private val EXCLUDED = setOf("jarvis_ai")

    /** Newest format first. A file that does not say which version it is cannot be trusted. */
    const val VERSION = 1

    data class Summary(val buckets: Int = 0, val keys: Int = 0, val skipped: Int = 0)

    // ---------------------------------------------------------------------------------------------
    // Pure codec — a map of buckets, each a map of typed values, to and from JSON
    // ---------------------------------------------------------------------------------------------

    /** What `SharedPreferences.getAll` gives back, flattened into JSON. Types are written down. */
    fun encode(snapshot: Map<String, Map<String, Any?>>, at: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("format", "jarvis-archive")
        root.put("version", VERSION)
        root.put("at", at)

        val buckets = JSONObject()
        for ((bucket, values) in snapshot) {
            if (bucket in EXCLUDED) continue
            val entries = JSONObject()
            for ((key, value) in values) {
                val entry = JSONObject()
                when (value) {
                    is String -> entry.put("t", "s").put("v", value)
                    is Int -> entry.put("t", "i").put("v", value)
                    is Long -> entry.put("t", "l").put("v", value)
                    is Boolean -> entry.put("t", "b").put("v", value)
                    is Float -> entry.put("t", "f").put("v", value.toDouble())
                    is Set<*> -> entry.put("t", "s").put("v", value.joinToString("\n") { it.toString() })
                    else -> continue
                }
                entries.put(key, entry)
            }
            if (entries.length() > 0) buckets.put(bucket, entries)
        }
        root.put("buckets", buckets)
        return root.toString()
    }

    /** The inverse. Null when the text is not a JARVIS archive at all. */
    fun decode(raw: String): Map<String, Map<String, Any>>? {
        val root = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return null
        }
        if (root.optString("format") != "jarvis-archive") return null
        if (root.optInt("version", 0) > VERSION) return null
        val buckets = root.optJSONObject("buckets") ?: return null

        val out = mutableMapOf<String, Map<String, Any>>()
        for (bucket in buckets.keys()) {
            if (bucket in EXCLUDED) continue
            val entries = buckets.optJSONObject(bucket) ?: continue
            val values = mutableMapOf<String, Any>()
            for (key in entries.keys()) {
                val entry = entries.optJSONObject(key) ?: continue
                val value: Any? = when (entry.optString("t")) {
                    "s" -> entry.optString("v")
                    "i" -> entry.optInt("v")
                    "l" -> entry.optLong("v")
                    "b" -> entry.optBoolean("v")
                    "f" -> entry.optDouble("v").toFloat()
                    else -> null
                }
                if (value != null) values[key] = value
            }
            if (values.isNotEmpty()) out[bucket] = values
        }
        return out
    }

    /** How big the archive is, for the one line shown before it is written. */
    fun summarise(snapshot: Map<String, Map<String, Any?>>): Summary {
        var buckets = 0
        var keys = 0
        var skipped = 0
        for ((bucket, values) in snapshot) {
            if (bucket in EXCLUDED) {
                skipped++
                continue
            }
            if (values.isEmpty()) continue
            buckets++
            keys += values.size
        }
        return Summary(buckets, keys, skipped)
    }

    // ---------------------------------------------------------------------------------------------
    // The device side
    // ---------------------------------------------------------------------------------------------

    /** Every preference bucket on the phone, read fresh. */
    fun snapshot(context: Context): Map<String, Map<String, Any?>> {
        val dir = File(context.applicationContext.dataDir, "shared_prefs")
        val files = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        }.orEmpty()

        val out = mutableMapOf<String, Map<String, Any?>>()
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".xml")) continue
            val name = file.nameWithoutExtension
            if (name in EXCLUDED) continue
            val all = try {
                context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
            } catch (t: Throwable) {
                null
            } ?: continue
            if (all.isNotEmpty()) out[name] = all
        }
        return out
    }

    /** The conversation log travels as one text value, merged back in on restore. */
    private const val CONVERSATION_BUCKET = "conversation_log"

    /** The file contents, ready to be shared. The full conversation log is included. */
    fun export(context: Context): String {
        val buckets = snapshot(context).toMutableMap()
        val log = try {
            ConversationLog.exportText(context)
        } catch (t: Throwable) {
            ""
        }
        if (log.isNotBlank()) buckets[CONVERSATION_BUCKET] = mapOf("jsonl" to log)
        return encode(buckets)
    }

    /**
     * Writes the archive into `Download/JARVIS` and hands back its uri.
     *
     * The share sheet in the settings screen starts from this file, so the operator can put it
     * wherever they keep things. Null means the write failed and the screen must say so rather than
     * offering a share of nothing.
     */
    fun saveToDownloads(context: Context): Pair<Uri?, String> {
        val text = try {
            export(context)
        } catch (t: Throwable) {
            return null to "The memory could not be read."
        }
        val name = "jarvis-memory-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date()) + ".json"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/JARVIS")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null to "Could not create the file."
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: return null to "Could not write the file."
            uri to name
        } catch (t: Throwable) {
            null to "Could not write the file."
        }
    }

    /**
     * Writes an archive back into preferences.
     *
     * Additive on purpose: importing a backup must not delete anything the operator has saved since.
     * The one exception is that a key present in both wins for the archive, because that is what
     * "restore" means.
     */
    fun restore(context: Context, raw: String): Summary? {
        val decoded = decode(raw) ?: return null
        var buckets = 0
        var keys = 0
        for ((bucket, values) in decoded) {
            if (bucket in EXCLUDED) continue
            if (bucket == CONVERSATION_BUCKET) {
                val text = values["jsonl"] as? String
                if (text != null) keys += ConversationLog.merge(context, text)
                buckets++
                continue
            }
            val prefs = context.applicationContext
                .getSharedPreferences(bucket, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for ((key, value) in values) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> continue
                }
                keys++
            }
            editor.apply()
            buckets++
        }
        return Summary(buckets, keys, 0)
    }
}
