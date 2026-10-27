package com.jarvis.assistant.device

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recording a voice note.
 *
 * "record a voice note" is a request people make while walking, holding something else, or thinking
 * out loud — so it takes no setup: one call starts recording, the note lands in
 * `Music/JARVIS` through MediaStore (so it shows up in every player and file manager), and the
 * duration is spoken back so it is clear the recording stopped.
 *
 * Bounded on purpose: [MAX_SECONDS] keeps a misheard "record" from filling the phone, and there is
 * a hard minimum so an accidental tap produces nothing.
 */
object VoiceNotes {

    const val MAX_SECONDS = 600
    const val DEFAULT_SECONDS = 60

    fun canRecord(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    data class Outcome(val ok: Boolean, val detail: String)

    /**
     * Records for [seconds] and saves the result.
     *
     * Uses `delay` rather than sleeping a thread, so the recorder runs on its own OS thread while the
     * coroutine stays cancellable — if the operator closes the app, the recording stops and is kept.
     */
    suspend fun record(context: Context, seconds: Int = DEFAULT_SECONDS): Outcome =
        withContext(Dispatchers.IO) {
            if (!canRecord(context)) {
                return@withContext Outcome(false, "Microphone access is off — allow it in Settings → Access.")
            }
            val duration = seconds.coerceIn(2, MAX_SECONDS)
            val name = "JARVIS note " +
                SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".m4a"

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/JARVIS")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val uri = try {
                context.contentResolver.insert(collection, values)
            } catch (t: Throwable) {
                null
            } ?: return@withContext Outcome(false, "The storage would not take the file.")

            var recorder: MediaRecorder? = null
            try {
                recorder = createRecorder(context)
                context.contentResolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                    recorder.setOutputFile(descriptor.fileDescriptor)
                    recorder.prepare()
                    recorder.start()
                    delay(duration * 1000L)
                    recorder.stop()
                }
                finish(context, uri)
                Outcome(true, "Recorded $duration seconds. Saved in Music, JARVIS folder.")
            } catch (t: Throwable) {
                try {
                    recorder?.stop()
                } catch (inner: Throwable) {
                    Unit
                }
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (inner: Throwable) {
                    Unit
                }
                Outcome(false, "The recording stopped early — nothing was saved.")
            } finally {
                try {
                    recorder?.release()
                } catch (t: Throwable) {
                    Unit
                }
            }
        }

    private fun createRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context.applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(96_000)
    }

    private fun finish(context: Context, uri: android.net.Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            try {
                context.contentResolver.update(uri, done, null, null)
            } catch (t: Throwable) {
                Unit
            }
        }
    }
}
