package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns "dad", "Divik" or "mom's phone" into a number.
 *
 * Read-only, bounded and cached: the address book is fetched once per minute at most, never on the
 * main thread, and only the name + number columns are touched. Matching is deliberately simple and
 * explainable — exact name, then alias stored by [VoiceMemory], then a token match — because a
 * wrong number is worse than "I could not find that contact".
 */
object Contacts {

    data class Match(val name: String, val number: String, val how: String)

    private data class Row(val name: String, val number: String, val normalised: String)

    @Volatile
    private var cache: List<Row>? = null

    @Volatile
    private var cachedAt: Long = 0L

    private const val CACHE_MS = 60_000L
    private const val MAX_ROWS = 5_000

    fun canRead(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    /** True when a remembered alias can answer without touching the address book. */
    fun aliasName(context: Context, name: String): String? = VoiceMemory.alias(context, name)

    suspend fun resolve(context: Context, query: String): Match? = withContext(Dispatchers.IO) {
        val raw = query.trim()
        if (raw.isEmpty()) return@withContext null

        // A number is already an answer.
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 5 && raw.count { it.isDigit() } == raw.length - raw.count { it == '+' || it == ' ' || it == '-' }) {
            return@withContext Match(raw, raw, "number")
        }

        VoiceMemory.alias(context, raw)?.let { stored ->
            return@withContext Match(raw, stored, "memory")
        }

        if (!canRead(context)) return@withContext null

        val rows = rows(context)
        if (rows.isEmpty()) return@withContext null

        val needle = normalise(raw)
        if (needle.isEmpty()) return@withContext null

        // 1. exact display name
        rows.firstOrNull { it.normalised == needle }?.let {
            return@withContext Match(it.name, it.number, "name")
        }

        // 2. one of the contact's name tokens equals the query ("divik" in "Divik Sharma")
        rows.firstOrNull { row -> row.normalised.split(' ').any { it == needle } }?.let {
            return@withContext Match(it.name, it.number, "name")
        }

        // 3. starts-with, then contains — shortest name wins so "raj" prefers "Raj" over "Rajanikant"
        val loose = rows
            .filter { it.normalised.startsWith(needle) || it.normalised.contains(needle) }
            .sortedBy { it.normalised.length }
            .firstOrNull()
        loose?.let {
            return@withContext Match(it.name, it.number, "partial")
        }

        null
    }

    /** Wipes the cache — called when contact permission is granted mid-session. */
    fun invalidate() {
        cache = null
        cachedAt = 0L
    }

    private fun rows(context: Context): List<Row> {
        val now = System.currentTimeMillis()
        val hit = cache
        if (hit != null && now - cachedAt < CACHE_MS) return hit

        val fresh = mutableListOf<Row>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(projection[0])
                val numberIndex = cursor.getColumnIndex(projection[1])
                while (cursor.moveToNext() && fresh.size < MAX_ROWS) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    if (name.isBlank() || number.isBlank()) continue
                    fresh.add(Row(name, number.trim(), normalise(name)))
                }
            }
        } catch (t: Throwable) {
            // Permission revoked mid-read, or the provider is unavailable: behave as "no contacts".
            return emptyList()
        }

        cache = fresh
        cachedAt = now
        return fresh
    }

    private fun normalise(value: String): String = VoiceParsing.normaliseName(value)
}
