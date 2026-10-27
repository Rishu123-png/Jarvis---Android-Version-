package com.jarvis.assistant.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Real Do Not Disturb control.
 *
 * This is the one interruption control Android actually grants to ordinary apps: with
 * *notification policy access* the app may set the interruption filter directly, so a routine can
 * genuinely silence the phone instead of only opening the settings screen.
 *
 * Everything degrades instead of throwing: without the grant, [set] returns null and the caller
 * reports that the permission is missing. Nothing here pretends to have worked.
 */
object DndControl {

    enum class Mode(
        val id: String,
        val label: String,
        val filter: Int,
        val note: String,
    ) {
        OFF(
            id = "off",
            label = "OFF",
            filter = NotificationManager.INTERRUPTION_FILTER_ALL,
            note = "Everything is allowed through",
        ),
        PRIORITY(
            id = "priority",
            label = "PRIORITY ONLY",
            filter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            note = "Only people and apps you starred can interrupt",
        ),
        ALARMS(
            id = "alarms",
            label = "ALARMS ONLY",
            filter = NotificationManager.INTERRUPTION_FILTER_ALARMS,
            note = "Alarms still ring, nothing else does",
        ),
        SILENT(
            id = "silent",
            label = "TOTAL SILENCE",
            filter = NotificationManager.INTERRUPTION_FILTER_NONE,
            note = "Nothing interrupts",
        );

        companion object {
            fun fromId(id: String?): Mode? = entries.firstOrNull {
                it.id == (id ?: "").trim().lowercase()
            }

            /** Accepts a few obvious synonyms so typed commands feel natural. */
            fun fromText(text: String?): Mode? {
                val key = (text ?: "").trim().lowercase()
                return fromId(key) ?: when {
                    key.isEmpty() -> null
                    key.startsWith("all") || key.startsWith("none") && key.length < 5 -> OFF
                    key.startsWith("total") || key.startsWith("silence") || key == "on" -> SILENT
                    key.startsWith("prio") || key.startsWith("important") -> PRIORITY
                    key.startsWith("alarm") -> ALARMS
                    else -> null
                }
            }
        }
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /** True when the operator granted notification policy access. */
    fun hasAccess(context: Context): Boolean = try {
        manager(context)?.isNotificationPolicyAccessGranted == true
    } catch (t: Throwable) {
        false
    }

    /** The system screen where that grant lives. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Current filter as one of our modes, or null when it cannot be read. */
    fun current(context: Context): Mode? = try {
        val filter = manager(context)?.currentInterruptionFilter ?: return null
        Mode.entries.firstOrNull { it.filter == filter }
    } catch (t: Throwable) {
        null
    }

    fun isActive(context: Context): Boolean = current(context)?.let { it != Mode.OFF } == true

    fun label(context: Context): String = when {
        !hasAccess(context) -> "NO ACCESS"
        else -> current(context)?.label ?: "UNKNOWN"
    }

    /**
     * Applies [mode]. Returns true on success, false when the system refused, or null when access
     * has not been granted — so the caller can tell "not allowed yet" apart from "did not work".
     */
    fun set(context: Context, mode: Mode): Boolean? {
        val notificationManager = manager(context) ?: return false
        if (!hasAccess(context)) return null
        return try {
            notificationManager.setInterruptionFilter(mode.filter)
            // Confirm against the system rather than assuming the call took effect.
            notificationManager.currentInterruptionFilter == mode.filter
        } catch (t: Throwable) {
            false
        }
    }
}
