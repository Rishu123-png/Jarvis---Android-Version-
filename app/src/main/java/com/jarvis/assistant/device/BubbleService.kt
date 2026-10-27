package com.jarvis.assistant.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import kotlin.math.abs

/**
 * The floating bubble.
 *
 * A small disc that sits over other apps so JARVIS is one tap away without going home. It is:
 *
 *  - **opt-in**, gated on "display over other apps" *and* an in-app switch;
 *  - **foreground**, with one quiet notification, because an overlay that can disappear when the
 *    system feels like it is worse than no overlay;
 *  - **dumb on purpose** — it does not listen, record or read anything. A tap opens JARVIS with the
 *    voice surface armed; a drag moves it; a long press removes it.
 *
 * It never shows a microphone indicator while idle, and it cannot start a voice turn by itself.
 */
class BubbleService : Service() {

    private var window: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Bubble.canDraw(this) || !Bubble.supported) {
            stopSelf()
            return
        }
        startForegroundSafely()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeBubble()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Foreground notification
    // ---------------------------------------------------------------------------------------------

    private fun startForegroundSafely() {
        val channel = "jarvis_bubble"
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager?.createNotificationChannel(
                    NotificationChannel(channel, "Floating bubble", NotificationManager.IMPORTANCE_MIN)
                        .apply { description = "Keeps the JARVIS bubble available" },
                )
            }
        } catch (t: Throwable) {
            // Channel exists.
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS bubble")
            .setContentText("Tap the disc to talk · long press to remove")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "REMOVE", stop)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Starting without the foreground promotion is still better than crashing the service.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The disc
    // ---------------------------------------------------------------------------------------------

    private fun showBubble() {
        val manager = getSystemService(WindowManager::class.java) ?: run {
            stopSelf()
            return
        }
        window = manager

        val disc = BubbleView(this)
        val layout = WindowManager.LayoutParams(
            SIZE_PX,
            SIZE_PX,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 420
        }

        try {
            manager.addView(disc, layout)
            view = disc
            params = layout
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private fun removeBubble() {
        val manager = window
        val current = view
        view = null
        params = null
        if (manager != null && current != null) {
            try {
                manager.removeView(current)
            } catch (t: Throwable) {
                // Already detached.
            }
        }
    }

    /** Drag to move, tap to open, long press to remove. No gesture ever starts a recording. */
    private inner class BubbleView(context: Context) : View(context) {

        private val background: GradientDrawable = Bubble.draw(context)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#16C8FF")
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
        }

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private val longPress = Runnable {
            Bubble.stop(context)
            stopSelf()
        }

        init {
            setOnTouchListener { _, event ->
                val layout = params ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = layout.x
                        startY = layout.y
                        dragging = false
                        postDelayed(longPress, 700L)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > 12f || abs(dy) > 12f) {
                            dragging = true
                            removeCallbacks(longPress)
                        }
                        if (dragging) {
                            layout.x = startX + dx.toInt()
                            layout.y = startY + dy.toInt()
                            try {
                                window?.updateViewLayout(this, layout)
                            } catch (t: Throwable) {
                                // The window went away mid-drag.
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(longPress)
                        if (!dragging) openVoice()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(longPress)
                        true
                    }

                    else -> false
                }
            }
        }

        private fun openVoice() {
            try {
                startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(Bubble.EXTRA_VOICE, true),
                )
            } catch (t: Throwable) {
                // Nothing to open; the bubble stays where it is.
            }
        }

        override fun onDraw(canvas: Canvas) {
            background.setBounds(0, 0, width, height)
            background.draw(canvas)

            // A microphone: rounded stem plus a cradle.
            val cx = width / 2f
            val top = height * 0.30f
            canvas.drawRoundRect(cx - 8f, top, cx + 8f, top + 26f, 8f, 8f, glyph)
            canvas.drawArc(cx - 14f, top + 8f, cx + 14f, top + 34f, 0f, 180f, false, glyph)
            canvas.drawLine(cx, top + 34f, cx, top + 42f, glyph)
        }
    }

    companion object {
        private const val SIZE_PX = 132
        private const val NOTIFICATION_ID = 4402
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP_BUBBLE"
    }
}
