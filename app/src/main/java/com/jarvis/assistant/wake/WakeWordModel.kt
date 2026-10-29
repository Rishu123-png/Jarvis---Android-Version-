package com.jarvis.assistant.wake

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The offline speech model behind "Hey Jarvis", fetched once from the Vosk project.
 *
 * Installed under the app's private files, so it is removed with the app and nothing else can read
 * it. The download is user-initiated (the operator turns the wake word on), shows progress, and is
 * checked by its contents: the archive must unpack to a model folder with an acoustic model and a
 * configuration. A half-finished download is never left looking installed.
 */
object WakeWordModel {

    const val NAME = "vosk-model-small-en-us-0.15"
    const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"

    /** Roughly the archive size, used only to show a percentage when the server omits a length. */
    private const val APPROX_BYTES = 41_000_000L

    private fun root(context: Context): File = File(context.filesDir, "wake-models")

    fun installedDir(context: Context): File? {
        val dir = File(root(context), NAME)
        return if (looksValid(dir)) dir else null
    }

    fun isInstalled(context: Context): Boolean = installedDir(context) != null

    /** Enough to be sure this is a Vosk model: its acoustic model and its configuration. */
    internal fun looksValid(dir: File): Boolean =
        File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile

    /**
     * Downloads and unpacks the model. Reports progress from 0 to 100. Returns null on success or a
     * short, speakable reason on failure. Blocking: call it from a background thread.
     */
    fun install(context: Context, onProgress: (Int) -> Unit): String? {
        installedDir(context)?.let { return null }

        val base = root(context).apply { mkdirs() }
        val archive = File(base, "$NAME.zip.part")
        val staging = File(base, "staging")
        val target = File(base, NAME)

        try {
            download(archive, onProgress)
                ?: return "The download stopped. Check the connection and try again."

            staging.deleteRecursively()
            staging.mkdirs()
            unzip(archive, staging)

            val unpacked = File(staging, NAME)
            if (!looksValid(unpacked)) {
                return "The voice model did not unpack correctly. Try again."
            }
            target.deleteRecursively()
            if (!unpacked.renameTo(target)) {
                return "The voice model could not be put in place. Free some storage and try again."
            }
            onProgress(100)
            return null
        } catch (t: Throwable) {
            return "The voice model could not be installed (${t.javaClass.simpleName})."
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    /** Returns null on success, or a failure reason. */
    private fun download(target: File, onProgress: (Int) -> Unit): String? {
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                return "The voice model server answered ${connection.responseCode}."
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: APPROX_BYTES
            var done = 0L
            var lastReported = -1
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = ((done * 99) / total).toInt().coerceIn(0, 99)
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            return if (target.length() > 0) null else "The download came back empty."
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks with a guard against entries that try to escape the target folder. */
    private fun unzip(archive: File, into: File) {
        val rootPath = into.canonicalPath + File.separator
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val out = File(into, entry.name)
                require(out.canonicalPath.startsWith(rootPath)) { "Unsafe path in archive" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }
}
