package com.jarvis.assistant.voice

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the screen when the accessibility tree is empty.
 *
 * Some apps draw their own UI, so nothing appears in the tree the screen agent normally reads. A
 * screenshot plus on-device text recognition gives it something to work with. The model is bundled
 * in the APK (no download, no Play Services round-trip) and nothing leaves the phone.
 *
 * Bounded at 8 seconds and always closing its bitmap, because this runs in the middle of a command.
 */
object Ocr {

    private const val TIMEOUT_MS = 8_000L

    /** Lines of text found in the image, de-duplicated and trimmed to something a prompt can hold. */
    suspend fun readLines(context: Context, bitmap: Bitmap, limit: Int = 40): List<String> =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = try {
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                val image = try {
                    InputImage.fromBitmap(bitmap, 0)
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val lines = result.textBlocks
                            .flatMap { it.lines }
                            .map { it.text.trim() }
                            .filter { it.length in 1..60 }
                            .distinct()
                            .take(limit)
                        if (continuation.isActive) continuation.resume(lines)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
            }
        } ?: emptyList()
}
