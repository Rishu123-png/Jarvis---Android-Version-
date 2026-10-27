package com.jarvis.assistant.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.AutomationTriggers
import com.jarvis.assistant.shell.ShizukuShell
import com.jarvis.assistant.voice.Contacts
import com.jarvis.assistant.voice.VoiceController
import com.jarvis.assistant.voice.Whisper

/**
 * The honest state of every subsystem that can be silently missing.
 *
 * Each row answers "is this actually going to work", not "is the app installed". Permission-gated
 * features are the ones that fail in the field, and a row that says DEGRADED is worth more than a
 * screen full of green.
 */
object SystemHealth {

    data class Row(val label: String, val value: String, val ok: Boolean)

    fun rows(context: Context, aiLabel: String?): List<Row> {
        val app = context.applicationContext

        val ears = when {
            Whisper.ready(app) -> "WHISPER · ${aiLabel?.uppercase() ?: "KEY"}"
            else -> "DEVICE RECOGNIZER"
        }
        val reminders = try {
            Reminders.pending(app).size
        } catch (t: Throwable) {
            0
        }
        val shizuku = when (ShizukuShell.state.value.status) {
            ShizukuShell.Status.READY -> "READY"
            ShizukuShell.Status.PERMISSION_NEEDED -> "PERMISSION NEEDED"
            ShizukuShell.Status.NOT_RUNNING -> "SERVICE STOPPED"
            ShizukuShell.Status.NOT_INSTALLED -> "NOT INSTALLED"
        }

        return listOf(
            Row("MICROPHONE", if (VoiceController.hasMicrophone(app)) "GRANTED" else "OFF", VoiceController.hasMicrophone(app)),
            Row("CONTACTS", if (Contacts.canRead(app)) "GRANTED" else "OFF", Contacts.canRead(app)),
            Row("CALLS", if (granted(app, Manifest.permission.CALL_PHONE)) "GRANTED" else "DIALER ONLY", granted(app, Manifest.permission.CALL_PHONE)),
            Row("CAMERA", if (granted(app, Manifest.permission.CAMERA)) "GRANTED" else "OFF", granted(app, Manifest.permission.CAMERA)),
            Row("SCREEN CONTROL", if (AccessibilityStatus.isServiceEnabled(app)) "ON" else "OFF", AccessibilityStatus.isServiceEnabled(app)),
            Row("SCREEN TAPS", if (AccessibilityStatus.isScreenPressAllowed(app)) "ALLOWED" else "BLOCKED", AccessibilityStatus.isScreenPressAllowed(app)),
            Row("NOTIFICATIONS", if (NotificationRepository.hasAccess(app)) "LISTENER ON" else "OFF", NotificationRepository.hasAccess(app)),
            Row("DO NOT DISTURB", if (DndControl.hasAccess(app)) "GRANTED" else "SYSTEM SCREEN", DndControl.hasAccess(app)),
            Row("EXACT ALARMS", if (AutomationTriggers.canScheduleExact(app)) "ALLOWED" else "INEXACT", AutomationTriggers.canScheduleExact(app)),
            Row("SHIZUKU", shizuku, shizuku == "READY"),
            Row("VOICE ENGINE", ears, true),
            Row("AI PROVIDER", aiLabel?.uppercase() ?: "NO KEY", aiLabel != null),
            Row("REMINDERS", if (reminders == 1) "1 PENDING" else "$reminders PENDING", true),
            Row("PHOTO STORE", "PICTURES/JARVIS", true),
        )
    }

    /** Cheap helper for callers that only need one answer. */
    fun aiConfigured(context: Context): Boolean = AiKeyStore.config(context) != null

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
