package com.jarvis.assistant.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * His eyes: one photo, one question, one short answer.
 *
 * The camera work is already here — [CaptureActivity] takes a frame without a shutter, because a
 * spoken chain cannot stop and wait for a button. This wraps it: take the frame, shrink it, encode
 * it, and hand it to whatever model the operator already configured.
 *
 * Two rules, both about not making the app worse:
 *
 *  - **On demand only.** Nothing here runs unless a sentence asked him to look. No background
 *    capture, no camera preview left holding the sensor, no extra service.
 *  - **Small.** A modern phone camera hands back 4000×3000 and 6 MB per frame, which is slow to
 *    encode and, on most providers, rejected outright. [sampleSizeFor] subsamples by powers of two
 *    at decode time so the full-size bitmap is never in memory, and [MAX_DIMENSION] caps the rest.
 */
object Vision {

    /** Big enough to read a label, small enough to upload in a second on mobile data. */
    const val MAX_DIMENSION = 1024

    /** Quality, not size — JPEG at 85 is indistinguishable on a phone screen. */
    private const val JPEG_QUALITY = 85

    /** A hard ceiling, because an unusual image must never become an out-of-memory crash. */
    const val MAX_BYTES = 1_600_000

    sealed class Outcome {
        data class Seen(val answer: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * The subsample factor for a decoded bitmap.
     *
     * Powers of two only, because that is what `BitmapFactory` accepts, and it must be the
     * *smallest* factor that brings the long edge down to [target] — one step further and the photo
     * is too small to read, one step less and it is still enormous.
     */
    fun sampleSizeFor(width: Int, height: Int, target: Int = MAX_DIMENSION): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > target && sample < 64) {
            sample *= 2
        }
        return sample
    }

    /** Decoded, shrunk, and encoded for upload. Null when the image cannot be read at all. */
    suspend fun encode(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@withContext null

            val long = maxOf(decoded.width, decoded.height)
            bitmap = if (long > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / long.toFloat()
                val width = (decoded.width * scale).toInt().coerceAtLeast(1)
                val height = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, width, height, true)
            } else {
                decoded
            }

            val bytes = ByteArrayOutputStream().use { stream ->
                (bitmap ?: decoded).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
                null
            } else {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                bitmap?.recycle()
            } catch (t: Throwable) {
                // Already recycled by the caller's bitmap cache.
            }
        }
    }

    /**
     * Takes one photo and answers a question about it.
     *
     * The failure modes are named rather than thrown: no camera permission, no photo, no key, no
     * network. Each one has its own sentence, because "something went wrong" is exactly the kind of
     * dead end this app is not allowed to produce.
     */
    suspend fun look(
        context: Context,
        question: String,
        system: String,
    ): Outcome {
        val (uri, error) = try {
            QuickCapture.capture(context)
        } catch (t: Throwable) {
            null to "The camera could not start."
        }
        if (uri == null) return Outcome.Failed(error ?: "No photo was taken.")

        val encoded = encode(context, uri)
            ?: return Outcome.Failed("I have the photo but could not read it. Try once more.")

        val result = try {
            AiClient.chat(
                context = context,
                messages = listOf(AiMessage.looking(question, listOf(encoded))),
                system = system,
            )
        } catch (t: Throwable) {
            return Outcome.Failed("I could not send the photo.")
        }

        return when (result) {
            is AiResult.Ok -> {
                val answer = com.jarvis.assistant.voice.Persona.sanitize(result.text.trim()).take(600)
                if (answer.isBlank()) {
                    Outcome.Failed("Nothing useful came back about the photo.")
                } else {
                    Outcome.Seen(answer)
                }
            }

            is AiResult.Error -> Outcome.Failed(result.message)
        }
    }
}
