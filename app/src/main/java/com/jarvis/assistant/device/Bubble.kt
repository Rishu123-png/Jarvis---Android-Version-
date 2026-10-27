package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Everything the floating bubble needs, kept out of the service so the service stays small and the
 * permission dance is in one readable place.
 *
 * The bubble is opt-in twice: first the operator grants "display over other apps" in system settings,
 * then switches the bubble on inside JARVIS. Revoking either stops it — the service checks on start
 * and shuts itself down rather than running invisibly.
 */
object Bubble {

    const val EXTRA_VOICE = "com.jarvis.assistant.extra.VOICE"

    fun canDraw(context: Context): Boolean = try {
        Settings.canDrawOverlays(context.applicationContext)
    } catch (t: Throwable) {
        false
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences("jarvis_bubble", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences("jarvis_bubble", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .apply()
    }

    /** The system screen where "display over other apps" is granted. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun start(context: Context): Boolean {
        if (!canDraw(context)) return false
        setEnabled(context, true)
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BubbleService::class.java),
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun stop(context: Context) {
        setEnabled(context, false)
        try {
            context.applicationContext.stopService(Intent(context, BubbleService::class.java))
        } catch (t: Throwable) {
            // Already gone.
        }
    }

    /** The bubble itself: a dim cyan disc with a mic glyph, drawn with plain Canvas. */
    internal fun draw(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#0B1929"))
        setStroke(2, Color.parseColor("#16C8FF"))
    }

    /** Breathing dot shown while JARVIS is listening, so the bubble is never a mystery. */
    internal fun active(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#3A1F0A"))
        setStroke(3, Color.parseColor("#FFC24B"))
    }

    internal val supported: Boolean get() = Build.VERSION.SDK_INT >= 26
}
