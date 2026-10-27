package com.jarvis.assistant.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.AccessibilityStatus

/**
 * Everything JARVIS is allowed to touch, in one list.
 *
 * One screen, one row per capability, each row stating what it unlocks and what breaks without it.
 * Android spreads these across three different kinds of grant — runtime dialogs, special
 * permissions that only exist in system settings, and services the operator enables by hand — and
 * that spread is why assistants usually feel broken: a feature quietly fails because a switch
 * nobody mentioned is off.
 */
object Access {

    enum class Kind {
        /** A runtime dialog JARVIS can show itself. */
        RUNTIME,

        /** A system settings page the operator must use. */
        SETTINGS,

        /** A service access the operator toggles by hand. */
        SERVICE,
    }

    data class Item(
        val id: String,
        val label: String,
        val unlocks: String,
        val note: String,
        val kind: Kind,
        val granted: Boolean,
        val permissions: List<String> = emptyList(),
    )

    val MEDIA_PERMISSIONS: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun snapshot(context: Context): List<Item> = listOf(
        Item(
            id = "files",
            label = "ALL FILES",
            unlocks = "finding any file by name — PDFs, documents, downloads",
            note = if (Files.hasAllFiles(context)) "switch is on" else "special access, set in system settings",
            kind = Kind.SETTINGS,
            granted = Files.hasAllFiles(context),
        ),
        Item(
            id = "photos",
            label = "PHOTOS AND MEDIA",
            unlocks = "the gallery, your last photo, video",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = hasAll(context, MEDIA_PERMISSIONS),
            permissions = MEDIA_PERMISSIONS,
        ),
        Item(
            id = "location",
            label = "LOCATION",
            unlocks = "where am I, distance, navigation",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = Whereabouts.hasPermission(context),
            permissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        ),
        Item(
            id = "steps",
            label = "ACTIVITY",
            unlocks = "steps and distance walked",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = HealthLog.hasActivityPermission(context),
            permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
        ),
        Item(
            id = "heart",
            label = "BODY SENSORS",
            unlocks = "heart rate, on phones that have the sensor",
            note = if (HealthLog.hasHeartSensor(context)) "sensor present" else "no sensor on this phone",
            kind = Kind.RUNTIME,
            granted = HealthLog.hasBodySensors(context),
            permissions = listOf(Manifest.permission.BODY_SENSORS),
        ),
        Item(
            id = "contacts",
            label = "CONTACTS",
            unlocks = "calling and messaging by name",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = hasAll(context, listOf(Manifest.permission.READ_CONTACTS)),
            permissions = listOf(Manifest.permission.READ_CONTACTS),
        ),
        Item(
            id = "calendar",
            label = "CALENDAR",
            unlocks = "reading your schedule and creating events by voice",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = CalendarBridge.canRead(context),
            permissions = CalendarBridge.PERMISSIONS.toList(),
        ),
        Item(
            id = "contacts_write",
            label = "SAVE CONTACTS",
            unlocks = "\"save this number as Amit\"",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = ContactsWrite.canWrite(context),
            permissions = listOf(Manifest.permission.WRITE_CONTACTS),
        ),
        Item(
            id = "microphone",
            label = "MICROPHONE",
            unlocks = "voice notes (not always-listening — only while recording)",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = VoiceNotes.canRecord(context),
            permissions = listOf(Manifest.permission.RECORD_AUDIO),
        ),
        Item(
            id = "camera",
            label = "CAMERA",
            unlocks = "taking a photo",
            note = "runtime permission",
            kind = Kind.RUNTIME,
            granted = hasAll(context, listOf(Manifest.permission.CAMERA)),
            permissions = listOf(Manifest.permission.CAMERA),
        ),
        Item(
            id = "notifications",
            label = "NOTIFICATION ACCESS",
            unlocks = "reading messages, replying, what did I miss",
            note = "system settings list",
            kind = Kind.SERVICE,
            granted = NotificationRepository.hasAccess(context),
        ),
        Item(
            id = "accessibility",
            label = "SCREEN ACCESS",
            unlocks = "tapping controls, screenshots, reading the screen",
            note = "system accessibility settings",
            kind = Kind.SERVICE,
            granted = AccessibilityStatus.isServiceEnabled(context),
        ),
        Item(
            id = "overlay",
            label = "DISPLAY OVER OTHER APPS",
            unlocks = "the floating bubble",
            note = "system settings",
            kind = Kind.SETTINGS,
            granted = Settings.canDrawOverlays(context),
        ),
    )

    /** Opens the right system page for a row. Runtime rows are handled by the UI dialog instead. */
    fun settingsIntent(context: Context, item: Item): Intent? = when (item.id) {
        "files" -> Files.allFilesIntent(context)
        "notifications" -> NotificationAccess.settingsIntent()
        "accessibility" -> AccessibilityStatus.settingsIntent()
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else -> null
    }

    fun hasAll(context: Context, permissions: List<String>): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * True when the operator has everything the wide features need.
     *
     * Used for one quiet line in the health/settings panels — never as a blocker, and never as a
     * nagging dialog on startup.
     */
    fun missingCount(context: Context): Int = snapshot(context).count { !it.granted }
}
