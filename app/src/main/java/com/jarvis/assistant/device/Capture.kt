package com.jarvis.assistant.device

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One photo, no shutter to press.
 *
 * The system camera intent always ends with the operator tapping a button, which makes "take a photo"
 * useless inside a spoken chain. So JARVIS opens its own capture surface for about a second, takes
 * one frame with CameraX, writes it to Pictures/JARVIS through MediaStore, and gets out of the way.
 *
 * Everything is bounded: the activity has its own watchdog, [QuickCapture.capture] waits at most
 * [CAPTURE_TIMEOUT_MS], and a failure is returned as text instead of a hang.
 */
object CaptureBridge {

    data class Outcome(val uri: Uri? = null, val error: String? = null)

    @Volatile
    private var pending: CompletableDeferred<Outcome>? = null

    fun start(): CompletableDeferred<Outcome> {
        val fresh = CompletableDeferred<Outcome>()
        pending = fresh
        return fresh
    }

    fun complete(uri: Uri?, error: String?) {
        val target = pending
        pending = null
        if (target != null && !target.isCompleted) target.complete(Outcome(uri, error))
    }

    fun isWaiting(): Boolean = pending != null
}

object QuickCapture {

    private const val CAPTURE_TIMEOUT_MS = 25_000L

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    /** Returns (uri, error). Exactly one of the two is non-null. */
    suspend fun capture(context: Context): Pair<Uri?, String?> = withContext(Dispatchers.Main) {
        if (!hasPermission(context)) {
            return@withContext null to "Camera permission is off — allow it on the home screen."
        }

        val result = CaptureBridge.start()
        val intent = Intent(context, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            CaptureBridge.complete(null, "The camera screen could not open.")
        }

        val outcome = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { result.await() }
            ?: CaptureBridge.Outcome(error = "The camera did not answer in time.")

        if (outcome.uri == null) null to (outcome.error ?: "No photo was taken.")
        else outcome.uri to null
    }
}

/**
 * The capture window. Deliberately built from framework views only — no Compose, no AppCompat — so
 * there is nothing in the window that can fail to inflate while a command is running.
 */
class CaptureActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var done = false

    private val watchdog = Runnable {
        finishWith(null, "The camera did not answer in time.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S. \u00B7 CAPTURING"
            setTextColor(Color.rgb(0xE7, 0xF6, 0xFF))
            textSize = 13f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
        }
        val note = TextView(this).apply {
            text = "one frame \u00B7 hold still"
            setTextColor(Color.rgb(0x8F, 0xB3, 0xC9))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(note)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(0x03, 0x07, 0x0D))
                addView(
                    column,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )

        main.postDelayed(watchdog, 15_000L)
        grab()
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        // If the window dies without a result, the awaiting step must still end.
        if (!done) CaptureBridge.complete(null, "The camera screen closed before capturing.")
        super.onDestroy()
    }

    private fun grab() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                val provider = try {
                    future.get()
                } catch (t: Throwable) {
                    finishWith(null, "The camera is unavailable.")
                    return@addListener
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                } catch (t: Throwable) {
                    finishWith(null, "Could not open the camera.")
                    return@addListener
                }

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "JARVIS_" + System.currentTimeMillis() + ".jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val options = ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ).build()

                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val uri = output.savedUri
                            if (uri == null) {
                                finishWith(null, "The camera saved nothing.")
                                return
                            }
                            try {
                                contentResolver.update(
                                    uri,
                                    ContentValues().apply {
                                        put(MediaStore.Images.Media.IS_PENDING, 0)
                                    },
                                    null,
                                    null,
                                )
                            } catch (t: Throwable) {
                                // The file is written either way; publish is best-effort.
                            }
                            finishWith(uri, null)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            finishWith(null, "Capture failed (code ${exception.imageCaptureError}).")
                        }
                    },
                )
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun finishWith(uri: Uri?, error: String?) {
        if (done) return
        done = true
        CaptureBridge.complete(uri, error)
        try {
            finish()
        } catch (t: Throwable) {
            // Already finishing.
        }
    }
}

/** The newest image in the gallery, opened in whatever viewer the phone uses. */
object Gallery {

    fun openLatest(context: Context): String? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val uri = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return "There are no photos yet."
                val id = cursor.getLong(cursor.getColumnIndex(projection[0]))
                Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            }
        } catch (t: Throwable) {
            null
        } ?: return "The gallery could not be read."

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            null
        } catch (t: Throwable) {
            "No app on this phone can open the photo."
        }
    }
}
