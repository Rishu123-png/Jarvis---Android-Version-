package com.jarvis.assistant.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a file to `Download/JARVIS`.
 *
 * Bounded everywhere, because a download is the classic way an assistant hangs: connect timeout,
 * read timeout, a hard size cap, and a cancellation flag the caller can flip. Progress is a real
 * notification with a percentage, and the result is either a saved file or one sentence saying what
 * went wrong — never a silent spinner.
 *
 * Scoped storage only (API 29+ is the app's minimum), so no storage permission is required.
 */
object Downloads {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_BYTES = 250L * 1024L * 1024L
    private const val CHANNEL = "jarvis_downloads"
    private const val NOTIFICATION_ID = 7710

    data class Outcome(val uri: Uri? = null, val name: String = "", val error: String? = null)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    suspend fun fetch(context: Context, rawUrl: String, suggestedName: String = ""): Outcome =
        withContext(Dispatchers.IO) {
            cancelled = false

            val url = normalise(rawUrl)
                ?: return@withContext Outcome(error = "That does not look like a web address.")

            val name = cleanName(suggestedName.ifBlank { url.substringAfterLast('/') })
                ?: ("jarvis-" + System.currentTimeMillis())

            val connection = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JARVIS-Android")
                }
            } catch (t: Throwable) {
                return@withContext Outcome(error = "Could not reach that address.")
            }

            try {
                val code = try {
                    connection.responseCode
                } catch (t: Throwable) {
                    return@withContext Outcome(error = "The server did not answer.")
                }
                if (code !in 200..299) {
                    return@withContext Outcome(error = "The server returned HTTP $code.")
                }

                val total = connection.contentLengthLong
                if (total > MAX_BYTES) {
                    return@withContext Outcome(error = "That file is larger than 250 MB.")
                }

                val uri = createEntry(context, name, connection.contentType)
                    ?: return@withContext Outcome(error = "Could not create the download file.")

                var written = 0L
                val stream = try {
                    connection.inputStream
                } catch (t: Throwable) {
                    deleteEntry(context, uri)
                    return@withContext Outcome(error = "The download could not start.")
                }

                val ok = try {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        copyBounded(stream, output, total) { progress ->
                            written = progress
                            notifyProgress(context, name, progress, total)
                        }
                    } ?: false
                } catch (t: Throwable) {
                    false
                } finally {
                    try {
                        stream.close()
                    } catch (t: Throwable) {
                        // Already closed.
                    }
                }

                if (!ok || cancelled) {
                    deleteEntry(context, uri)
                    clearNotification(context)
                    return@withContext Outcome(
                        error = if (cancelled) "Download cancelled." else "The download stopped partway.",
                    )
                }

                publish(context, uri)
                clearNotification(context)
                Outcome(uri = uri, name = name)
            } finally {
                try {
                    connection.disconnect()
                } catch (t: Throwable) {
                    // Already closed.
                }
            }
        }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private fun copyBounded(
        input: InputStream,
        output: java.io.OutputStream,
        total: Long,
        onProgress: (Long) -> Unit,
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        var lastReport = 0L
        while (true) {
            if (cancelled) return false
            val read = try {
                input.read(buffer)
            } catch (t: Throwable) {
                return false
            }
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            if (written > MAX_BYTES) return false
            if (written - lastReport > 512 * 1024 || (total > 0 && written >= total)) {
                lastReport = written
                onProgress(written)
            }
        }
        return true
    }

    private fun createEntry(context: Context, name: String, mime: String?): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/JARVIS")
            put(MediaStore.Downloads.IS_PENDING, 1)
            if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (t: Throwable) {
        null
    }

    private fun publish(context: Context, uri: Uri) {
        try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            // The file is written either way.
        }
    }

    private fun deleteEntry(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (t: Throwable) {
            // Nothing to delete.
        }
    }

    private fun notifyProgress(context: Context, name: String, written: Long, total: Long) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val percent = if (total > 0) ((written * 100) / total).toInt() else -1
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.jarvis.assistant.R.mipmap.ic_launcher)
                .setContentTitle("JARVIS · DOWNLOAD")
                .setContentText(name)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .apply {
                    if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true)
                }
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // Notifications are a courtesy here.
        }
    }

    private fun clearNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            // Already gone.
        }
    }

    private fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return try {
            val url = URL(withScheme)
            if (url.host.isNullOrBlank()) null else withScheme
        } catch (t: Throwable) {
            null
        }
    }

    private fun cleanName(raw: String): String? {
        val base = raw.substringBefore('?').substringAfterLast('/').trim()
        if (base.isEmpty()) return null
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return safe.ifBlank { null }
    }
}
