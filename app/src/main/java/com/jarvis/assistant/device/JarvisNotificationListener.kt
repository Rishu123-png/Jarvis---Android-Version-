package com.jarvis.assistant.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.assistant.automation.SpeechOut
import com.jarvis.assistant.voice.SpokenLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification listener.
 *
 * Declared in the manifest with `BIND_NOTIFICATION_LISTENER_SERVICE`, which means Android only binds
 * it after the user explicitly enables JARVIS in *Settings → Notification access*. Until then the
 * service is dormant and the app behaves exactly as before.
 *
 * Two jobs:
 *
 *  1. **Publish** the live list into [NotificationRepository] for the alerts screen.
 *  2. **Judge and speak**, when the operator has switched that on. A chat is read properly and comes
 *     with three suggested replies; an advertisement gets one dry line and is then left alone for
 *     the rest of the day; an OTP is read with its code. Everything else stays silent.
 *
 * Nothing is stored: notifications are held in memory only, and dropped when the listener
 * disconnects. The only thing written to disk is "this app's advert was already mentioned today".
 */
class JarvisNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun publishSnapshot() {
        val active = try {
            activeNotifications?.toList() ?: emptyList()
        } catch (t: Throwable) {
            // Thrown when access was revoked while the process was alive.
            emptyList()
        }
        NotificationRepository.publish(applicationContext, active)
        NotifyBrain.capture(active)
        announce(active)
    }

    /**
     * Speaks the notifications that deserve it.
     *
     * The whole feature is opt-in: with the switch off this returns immediately and the service is
     * purely a reader for the alerts screen.
     */
    private fun announce(active: List<StatusBarNotification>) {
        val context = applicationContext
        if (!NotifyBrain.isEnabled(context)) return
        if (NotifyBrain.isQuietHour(
                NotifyBrain.currentHour(),
                NotifyBrain.quietFrom(context),
                NotifyBrain.quietTo(context),
            )
        ) {
            return
        }

        val muted = NotifyBrain.mutedApps(context)
        val lang = SpokenLanguage.last(context)

        for (sbn in active.sortedBy { it.postTime }) {
            if (sbn.packageName == packageName) continue
            if (sbn.packageName in muted) continue
            if (!NotifyBrain.markSeen(context, sbn.key, sbn.postTime)) continue

            val notification = sbn.notification ?: continue
            // Foreground-service chatter and group summaries are never worth a sentence.
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue

            val verdict = NotifyBrain.verdictFor(
                context = context,
                packageName = sbn.packageName,
                appLabel = NotifyBrain.appLabelOf(context, sbn.packageName),
                title = NotifyBrain.senderOf(sbn),
                text = NotifyBrain.textOf(sbn),
            )

            if (verdict.isPromo) {
                if (NotifyBrain.promoAlreadyTold(context, sbn.packageName)) continue
                NotifyBrain.markPromoTold(context, sbn.packageName)
            } else if (!verdict.worthTelling) {
                continue
            }

            val line = buildString {
                append(
                    NotifyBrain.announceLine(
                        verdict = verdict,
                        appLabel = NotifyBrain.appLabelOf(context, sbn.packageName),
                        sender = NotifyBrain.senderOf(sbn).ifBlank { "someone" },
                        text = NotifyBrain.textOf(sbn),
                        lang = lang,
                    ),
                )
                if (verdict.kind == NotificationKind.MESSAGE) {
                    val options = NotifyBrain.suggestions(
                        NotifyBrain.textOf(sbn),
                        NotifyBrain.senderOf(sbn),
                        lang,
                    )
                    if (options.isNotEmpty()) {
                        append(" ")
                        append(
                            when (lang.replyLang) {
                                com.jarvis.assistant.voice.Lang.HI -> "आप कह सकते हैं: "
                                com.jarvis.assistant.voice.Lang.HINGLISH -> "Aap keh sakte hain: "
                                else -> "You could say: "
                            },
                        )
                        append(options.joinToString(", "))
                    }
                }
            }

            if (verdict.kind == NotificationKind.MESSAGE) {
                val sender = NotifyBrain.senderOf(sbn)
                if (sender.isNotBlank()) {
                    try {
                        com.jarvis.assistant.voice.Brain.touchPerson(context, sender)
                    } catch (t: Throwable) {
                        // A memory write must never break an announcement.
                    }
                }
            }

            scope.launch {
                try {
                    SpeechOut.speak(context, line, timeoutMillis = 20_000L)
                } catch (t: Throwable) {
                    // Speaking is best-effort: a missing voice must never crash the listener.
                }
            }
        }
    }
}
