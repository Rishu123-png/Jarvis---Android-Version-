package com.jarvis.assistant.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * One notification currently posted on the device, reduced to what the HUD needs.
 */
data class JarvisNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
)

/**
 * Single source of truth for the notification list.
 *
 * [JarvisNotificationListener] publishes into this repo; the UI only ever reads from it. That keeps
 * Compose out of the service and means the panel still shows the last known list after a config
 * change.
 */
object NotificationRepository {

    private val _notifications = MutableStateFlow<List<JarvisNotification>>(emptyList())
    val notifications: StateFlow<List<JarvisNotification>> = _notifications.asStateFlow()

    private var listenerRef: WeakReference<JarvisNotificationListener>? = null

    private val labelCache = LruCache<String, String>(48)

    fun attach(listener: JarvisNotificationListener) {
        listenerRef = WeakReference(listener)
    }

    fun detach() {
        listenerRef = null
    }

    /** Reads the listener's live snapshot. Safe to call when access has been revoked. */
    fun publish(context: Context, active: List<StatusBarNotification>) {
        val mapped = try {
            active.mapNotNull { sbn -> map(context, sbn) }
                .sortedByDescending { it.postedAt }
        } catch (t: Throwable) {
            emptyList()
        }
        _notifications.value = mapped
    }

    fun clear() {
        _notifications.value = emptyList()
    }

    /** Dismisses one notification through the listener. Returns false when access is gone. */
    fun dismiss(key: String): Boolean {
        val listener = listenerRef?.get() ?: return false
        return try {
            listener.cancelNotification(key)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun dismissAllClearable(): Int {
        val listener = listenerRef?.get() ?: return 0
        var count = 0
        for (item in _notifications.value) {
            if (!item.isClearable || item.isOngoing) continue
            try {
                listener.cancelNotification(item.key)
                count++
            } catch (t: Throwable) {
                break
            }
        }
        return count
    }

    private fun map(context: Context, sbn: StatusBarNotification): JarvisNotification? {
        val notification: Notification = sbn.notification ?: return null
        val extras = notification.extras

        val title = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
        ).firstOrNull { !it.isNullOrBlank() }?.toString()?.trim().orEmpty()

        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString()?.trim().orEmpty()

        val flags = notification.flags
        val ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val clearable = flags and Notification.FLAG_NO_CLEAR == 0 && sbn.isClearable

        return JarvisNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime,
            isOngoing = ongoing,
            isClearable = clearable,
        )
    }

    private fun appLabel(context: Context, packageName: String): String {
        labelCache.get(packageName)?.let { return it }
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (t: Throwable) {
            packageName
        }
        labelCache.put(packageName, label)
        return label
    }

    /** True when the user has granted notification access to JARVIS. */
    fun hasAccess(context: Context): Boolean = NotificationAccess.isGranted(context)
}

/**
 * Notification-access plumbing. Android has no runtime permission for this — the user must enable
 * it in system settings, so JARVIS only ever links there and never nags on startup.
 */
object NotificationAccess {

    fun isGranted(context: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    } catch (t: Throwable) {
        false
    }

    /** The system list of apps with notification access. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, JarvisNotificationListener::class.java)
}
