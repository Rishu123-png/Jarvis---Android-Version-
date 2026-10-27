package com.jarvis.assistant.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device memory for the voice surface.
 *
 * Three things are kept, and nothing else:
 *
 *  - the last [MAX_RECENT] commands and what they did, so "again" and "what did I just ask" work
 *  - contact aliases the operator has confirmed ("papa" -> Dad's number), so a nickname that is not
 *    in the address book still resolves next time
 *  - app aliases ("insta" -> com.instagram.android) learned when a fuzzy match had to be guessed
 *
 * No network, no account, no backups: the file lives in the app's private storage.
 */
object VoiceMemory {

    private const val PREFS = "jarvis_voice"
    private const val KEY_RECENT = "recent_v1"
    private const val KEY_ALIASES = "contact_aliases_v1"
    private const val KEY_APPS = "app_aliases_v1"
    private const val MAX_RECENT = 50
    private const val MAX_ALIASES = 120

    data class Entry(val heard: String, val summary: String, val at: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // Recent commands
    // ---------------------------------------------------------------------------------------------

    @Synchronized
    fun remember(context: Context, heard: String, summary: String) {
        val clean = heard.trim()
        if (clean.isEmpty()) return
        val list = readRecent(context).toMutableList()
        list.add(0, Entry(clean, summary.trim(), System.currentTimeMillis()))
        val trimmed = list.take(MAX_RECENT)
        val array = JSONArray()
        trimmed.forEach { entry ->
            array.put(
                JSONObject()
                    .put("h", entry.heard)
                    .put("s", entry.summary)
                    .put("t", entry.at),
            )
        }
        prefs(context).edit().putString(KEY_RECENT, array.toString()).apply()
    }

    fun recent(context: Context, limit: Int = 6): List<Entry> = readRecent(context).take(limit)

    fun lastCommand(context: Context): String? = readRecent(context).firstOrNull()?.heard

    private fun readRecent(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val heard = item.optString("h")
                    if (heard.isBlank()) continue
                    add(Entry(heard, item.optString("s"), item.optLong("t")))
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Contact aliases
    // ---------------------------------------------------------------------------------------------

    fun alias(context: Context, name: String): String? {
        val key = normalise(name)
        if (key.isEmpty()) return null
        return readMap(context, KEY_ALIASES)[key]
    }

    @Synchronized
    fun rememberAlias(context: Context, name: String, number: String) {
        val key = normalise(name)
        val digits = number.filter { it.isDigit() || it == '+' }
        if (key.isEmpty() || digits.length < 3) return
        writeMap(context, KEY_ALIASES, readMap(context, KEY_ALIASES) + (key to digits), MAX_ALIASES)
    }

    // ---------------------------------------------------------------------------------------------
    // App aliases
    // ---------------------------------------------------------------------------------------------

    fun appAlias(context: Context, phrase: String): String? {
        val key = normalise(phrase)
        if (key.isEmpty()) return null
        return readMap(context, KEY_APPS)[key]
    }

    @Synchronized
    fun rememberAppAlias(context: Context, phrase: String, packageName: String) {
        val key = normalise(phrase)
        if (key.isEmpty() || packageName.isBlank()) return
        writeMap(context, KEY_APPS, readMap(context, KEY_APPS) + (key to packageName), MAX_ALIASES)
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    @Synchronized
    fun forget(context: Context) {
        prefs(context).edit()
            .remove(KEY_RECENT)
            .remove(KEY_ALIASES)
            .remove(KEY_APPS)
            .apply()
    }

    private fun readMap(context: Context, key: String): Map<String, String> {
        val raw = prefs(context).getString(key, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { name ->
                    val value = json.optString(name)
                    if (value.isNotBlank()) put(name, value)
                }
            }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun writeMap(context: Context, key: String, map: Map<String, String>, limit: Int) {
        val json = JSONObject()
        map.entries.take(limit).forEach { (name, value) -> json.put(name, value) }
        prefs(context).edit().putString(key, json.toString()).apply()
    }

    private fun normalise(value: String): String =
        value.lowercase().trim().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
