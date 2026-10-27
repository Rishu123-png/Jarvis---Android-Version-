package com.jarvis.assistant.device

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Which lock guards JARVIS.
 *
 * The operator asked for the phone's **own** lock rather than one JARVIS invents, and separately
 * asked for JARVIS to be visible *on* the lock screen. Both are here, and the limits are stated
 * rather than papered over:
 *
 *  - [Mode.PHONE_LOCK] shows the real system credential screen through
 *    [KeyguardManager.createConfirmDeviceCredentialIntent]. That is the same PIN/pattern/password/
 *    fingerprint the operator already uses — JARVIS only ever learns pass or fail, and cannot read
 *    the credential itself.
 *  - The lock-screen notice is a **high-priority notification** with public visibility, which is how
 *    any app may appear over the keyguard. Android 14 restricted full-screen intents to calling and
 *    alarm apps, so claiming one would be a lie; a heads-up notification is what is actually
 *    available.
 *
 * What no app can do — and this app will not pretend — is replace the keyguard or draw on it
 * permanently. Lock-screen widgets were removed in Android 5.0.
 */
object LockGate {

    enum class Mode(val id: String, val label: String, val blurb: String) {
        APP_PIN(
            "pin",
            "JARVIS KEYPAD",
            "A PIN inside JARVIS only. Works on any phone, independent of the system lock.",
        ),
        PHONE_LOCK(
            "device",
            "PHONE LOCK",
            "Your phone's own PIN, pattern or fingerprint. JARVIS sees only pass or fail.",
        ),
        ;

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: APP_PIN
        }
    }

    private const val PREFS = "jarvis_lockgate"
    private const val CHANNEL = "jarvis_locknotice"
    private const val NOTICE_ID = 2107

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode = Mode.fromId(prefs(context).getString("mode", null))

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString("mode", mode.id).apply()
    }

    /** True when the phone has a PIN, pattern, password or enrolled biometric. */
    fun deviceLockAvailable(context: Context): Boolean = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        keyguard?.isDeviceSecure == true
    } catch (t: Throwable) {
        false
    }

    /**
     * The system credential prompt, or null when the phone has no secure lock.
     *
     * Deprecated by the platform in favour of `BiometricPrompt`, but it is still the only API that
     * accepts *any* credential the operator set up — including a pattern or a password, which
     * `BiometricPrompt` refuses to handle on its own.
     */
    @Suppress("DEPRECATION")
    fun credentialIntent(context: Context): Intent? = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        keyguard?.createConfirmDeviceCredentialIntent(
            "JARVIS",
            "Unlock with the phone's own lock",
        )
    } catch (t: Throwable) {
        null
    }

    /** What the lock screen should be allowed to show. */
    fun noticeEnabled(context: Context): Boolean =
        prefs(context).getBoolean("notice", false)

    fun setNoticeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("notice", enabled).apply()
        if (!enabled) clearNotice(context)
    }

    /**
     * Posts (or replaces) the JARVIS line on the lock screen.
     *
     * Public visibility so the text shows over the keyguard; no reply action, because the keyguard is
     * the point.
     */
    fun postNotice(context: Context, lines: List<String>): Boolean {
        val body = lines.filter { it.isNotBlank() }.take(3).joinToString(" · ")
        if (body.isBlank()) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        ensureChannel(context)

        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = open?.let { PendingIntent.getActivity(context, 0, it, flags) }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.jarvis.assistant.R.drawable.ic_stat_jarvis)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .apply { if (content != null) setContentIntent(content) }
            .build()

        return try {
            manager.notify(NOTICE_ID, notification)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun clearNotice(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTICE_ID)
        } catch (t: Throwable) {
            Unit
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL,
            "Lock screen line",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "The short JARVIS summary shown over the lock screen."
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
