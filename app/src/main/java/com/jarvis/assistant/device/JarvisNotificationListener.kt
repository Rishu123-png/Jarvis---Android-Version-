package com.jarvis.assistant.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification listener.
 *
 * Declared in the manifest with `BIND_NOTIFICATION_LISTENER_SERVICE`, which means Android only binds
 * it after the user explicitly enables JARVIS in *Settings → Notification access*. Until then the
 * service is dormant and the app behaves exactly as before.
 *
 * Nothing is stored: notifications are held in memory only, and dropped when the listener
 * disconnects or the list changes.
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationRepository.attach(this)
        publishSnapshot()
    }

    override fun onListenerDisconnected() {
        NotificationRepository.detach()
        NotificationRepository.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val active = try {
            activeNotifications?.toList() ?: emptyList()
        } catch (t: Throwable) {
            // Thrown when access was revoked while the process was alive.
            emptyList()
        }
        NotificationRepository.publish(applicationContext, active)
    }
}
