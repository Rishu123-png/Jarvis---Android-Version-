package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.jarvis.assistant.data.PhraseBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * "Find my aadhaar pdf" — real file search.
 *
 * Two modes, because Android has two very different worlds:
 *
 *  - **All files access** (`MANAGE_EXTERNAL_STORAGE`, a manual grant in system settings). JARVIS can
 *    walk the shared storage, so any file on the phone is findable by name, including downloads,
 *    WhatsApp documents and folders apps created. This is what "it should find any file" means.
 *  - **Scoped** (no special grant). Only what `MediaStore` indexes: photos, videos, audio and the
 *    documents other apps registered. Still useful, and it needs only a media permission.
 *
 * The walk is bounded — depth, count, and a time budget — so asking for a file can never hang the
 * assistant. `com.android.systemui`-style system folders and `Android/data` are skipped: they are
 * unreadable on 11+ and are never what somebody means by "my file".
 */
object Files {

    data class Found(
        val name: String,
        val uri: String,
        val folder: String,
        val bytes: Long,
        val modified: Long,
    ) {
        val sizeLabel: String
            get() = when {
                bytes <= 0L -> ""
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024L * 1024 * 1024)} GB"
            }
    }

    private const val MAX_RESULTS = 12
    private const val MAX_FILES_SCANNED = 40_000
    private const val MAX_DEPTH = 7
    private const val TIME_BUDGET_MS = 4_000L

    private val SKIP_DIRS = setOf(
        "Android/data", "Android/obb", "Android/media",
        ".thumbnails", ".trashed", "cache", "Cache",
    )

    /** True when the operator granted "All files access" in system settings. */
    fun hasAllFiles(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            false
        }
    } catch (t: Throwable) {
        false
    }

    fun allFilesIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (t: Throwable) {
            try {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (inner: Throwable) {
                null
            }
        }
    }

    /**
     * Files whose name matches [query], newest first.
     *
     * The match is "contains" plus a typo-tolerant pass on the file name, so "aadhar" still finds
     * "Aadhaar 2024.pdf" — people mis-hear and mistype, and a file finder that demands exact
     * spelling is the same as no file finder.
     */
    suspend fun search(context: Context, query: String, limit: Int = MAX_RESULTS): List<Found> =
        withContext(Dispatchers.IO) {
            val needle = PhraseBook.clean(query)
            if (needle.isBlank()) return@withContext emptyList()

            val fromDisk = if (hasAllFiles(context)) walk(context, needle, limit) else emptyList()
            if (fromDisk.isNotEmpty()) return@withContext fromDisk
            mediaStore(context, needle, limit)
        }

    private fun walk(context: Context, needle: String, limit: Int): List<Found> {
        val root = try {
            Environment.getExternalStorageDirectory()
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        val startedAt = System.currentTimeMillis()
        var scanned = 0
        val hits = mutableListOf<Found>()

        fun visit(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || scanned > MAX_FILES_SCANNED) return
            if (System.currentTimeMillis() - startedAt > TIME_BUDGET_MS) return

            val children = try {
                dir.listFiles()
            } catch (t: Throwable) {
                null
            } ?: return

            for (child in children) {
                if (scanned > MAX_FILES_SCANNED) return
                if (System.currentTimeMillis() - startedAt > TIME_BUDGET_MS) return
                val name = child.name
                if (name.startsWith(".")) continue
                scanned++

                if (child.isDirectory) {
                    val path = child.absolutePath.lowercase(Locale.US)
                    if (SKIP_DIRS.any { path.contains(it.lowercase(Locale.US)) }) continue
                    visit(child, depth + 1)
                    continue
                }

                if (!matches(needle, PhraseBook.clean(name))) continue
                hits.add(
                    Found(
                        name = name,
                        uri = Uri.fromFile(child).toString(),
                        folder = child.parent?.let { shortFolder(it.absolutePath) }.orEmpty(),
                        bytes = child.length(),
                        modified = child.lastModified(),
                    ),
                )
                if (hits.size >= limit * 2) return
            }
        }

        visit(root, 0)
        return hits.sortedByDescending { it.modified }.take(limit)
    }

    private fun mediaStore(context: Context, needle: String, limit: Int): List<Found> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATA,
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$needle%")

        val rows = mutableListOf<Found>()
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                args,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext() && rows.size < limit * 2) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: continue
                    val uri = Uri.withAppendedPath(
                        MediaStore.Files.getContentUri("external"),
                        id.toString(),
                    )
                    val data = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""
                    rows.add(
                        Found(
                            name = name,
                            uri = uri.toString(),
                            folder = if (data.isBlank()) "" else shortFolder(data.substringBeforeLast('/')),
                            bytes = cursor.getLong(sizeIndex),
                            modified = cursor.getLong(dateIndex) * 1000L,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return rows.sortedByDescending { it.modified }.take(limit)
    }

    /** "Download", "Documents/Aadhaar" — short enough to show on a card. */
    private fun shortFolder(path: String): String {
        val parts = path.split('/').filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0]
            else -> parts.takeLast(2).joinToString("/")
        }
    }

    private fun matches(needle: String, haystack: String): Boolean {
        if (haystack.contains(needle)) return true
        if (needle.length < 4) return false
        return haystack.split(' ', '-', '_', '.').any { word ->
            word.isNotBlank() && PhraseBook.jaroWinkler(needle, word) >= 0.88
        }
    }

    /** Opens a found file with whatever app owns its type, granting read access. */
    fun open(context: Context, uriString: String, name: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val mime = context.contentResolver.getType(uri) ?: mimeFor(name)
            val intent = if (uri.scheme == "file") {
                // A raw file path cannot be granted through a URI permission, so it goes through
                // the FileProvider-free ACTION_VIEW route that most viewers accept.
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            } else {
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (t: Throwable) {
            "No app on this phone can open $name."
        }
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "*/*"
    }
}
