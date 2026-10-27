package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings

/**
 * Device controls.
 *
 * The rule applied here: only do what Android genuinely allows, and hand the rest to the system UI
 * instead of pretending. Concretely —
 *
 *  * **Real, no permission:** torch, media volume, media transport (play/pause/next/prev) once
 *    notification access is granted, and reading every device/status value.
 *  * **Opened as a system panel:** Wi-Fi, Bluetooth, battery saver. A regular app cannot silently
 *    flip these on modern Android, and trying would be dishonest.
 *  * **Real, with one special grant:** Do Not Disturb — see [DndControl]. Without that grant this
 *    class still offers the system screen rather than a dead toggle.
 *  * **Not offered:** anything needing WRITE_SETTINGS or a device-owner profile.
 */
object QuickControls {

    // ---------------------------------------------------------------- torch

    /**
     * Torch state, tracked by a camera torch callback so it stays correct if the user toggles the
     * flashlight from the system shade.
     */
    object Torch {
        private var cameraId: String? = null
        private var registered = false
        private var enabled = false

        val isOn: Boolean get() = enabled

        /** True once a camera with a flash has been located. */
        fun isAvailable(context: Context): Boolean = ensureRegistered(context)?.let { cameraId != null } == true

        private fun ensureRegistered(context: Context): CameraManager? {
            val manager = context.getSystemService(CameraManager::class.java) ?: return null
            if (!registered) {
                try {
                    cameraId = manager.cameraIdList.firstOrNull { id ->
                        try {
                            val chars = manager.getCameraCharacteristics(id)
                            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                                chars.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK
                        } catch (t: Throwable) {
                            false
                        }
                    } ?: manager.cameraIdList.firstOrNull()

                    manager.registerTorchCallback(
                        object : CameraManager.TorchCallback() {
                            override fun onTorchModeChanged(id: String, on: Boolean) {
                                if (id == cameraId) enabled = on
                            }

                            override fun onTorchModeUnavailable(id: String) {
                                if (id == cameraId) enabled = false
                            }
                        },
                        null,
                    )
                    registered = true
                } catch (t: Throwable) {
                    return null
                }
            }
            return manager
        }

        /** Returns the new state, or null when the device has no usable flashlight. */
        fun toggle(context: Context): Boolean? {
            val manager = ensureRegistered(context) ?: return null
            val id = cameraId ?: return null
            val target = !enabled
            return try {
                manager.setTorchMode(id, target)
                enabled = target
                target
            } catch (t: Throwable) {
                null
            }
        }

        fun setOff(context: Context) {
            if (!enabled) return
            val manager = context.getSystemService(CameraManager::class.java) ?: return
            val id = cameraId ?: return
            try {
                manager.setTorchMode(id, false)
                enabled = false
            } catch (t: Throwable) {
                // Camera in use elsewhere; nothing to do.
            }
        }
    }

    // ---------------------------------------------------------------- volume

    fun mediaVolumePercent(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0
        return try {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            (current * 100 / max).coerceIn(0, 100)
        } catch (t: Throwable) {
            0
        }
    }

    fun adjustVolume(context: Context, delta: Int): Int {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0
        return try {
            val direction = if (delta >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            repeat(kotlin.math.abs(delta).coerceIn(1, 5)) {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            mediaVolumePercent(context)
        } catch (t: Throwable) {
            0
        }
    }

    /** Sets media volume absolutely (0–100). Returns the value the system reports afterwards. */
    fun setMediaVolume(context: Context, percent: Int): Int {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0
        return try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (percent.coerceIn(0, 100) * max / 100f).toInt().coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            mediaVolumePercent(context)
        } catch (t: Throwable) {
            mediaVolumePercent(context)
        }
    }

    fun isSilent(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    // ---------------------------------------------------------------- media

    private fun activeController(context: Context): MediaController? {
        if (!NotificationAccess.isGranted(context)) return null
        return try {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val component = NotificationAccess.listenerComponent(context)
            manager.getActiveSessions(component).firstOrNull()
        } catch (t: Throwable) {
            // SecurityException when listener access was revoked, or nothing is playing.
            null
        }
    }

    fun hasMediaSession(context: Context): Boolean = activeController(context) != null

    fun mediaNowPlaying(context: Context): String? {
        val controller = activeController(context) ?: return null
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        return when {
            title.isNullOrBlank() -> null
            artist.isNullOrBlank() -> title
            else -> "$title — $artist"
        }
    }

    fun isPlaying(context: Context): Boolean {
        val controller = activeController(context) ?: return false
        val state = controller.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING
    }

    /** Play/pause the active media session. Returns the new playing state, or null. */
    fun togglePlayback(context: Context): Boolean? {
        val controller = activeController(context) ?: return null
        return try {
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
                false
            } else {
                controller.transportControls.play()
                true
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Explicit play or pause, without toggling. Returns the applied state, or null. */
    fun setPlayback(context: Context, play: Boolean): Boolean? {
        val controller = activeController(context) ?: return null
        return try {
            if (play) controller.transportControls.play() else controller.transportControls.pause()
            play
        } catch (t: Throwable) {
            null
        }
    }

    /** True when there is a media session JARVIS can drive. */
    fun hasActiveMedia(context: Context): Boolean = activeController(context) != null

    fun skipNext(context: Context): Boolean = withController(context) {
        it.transportControls.skipToNext()
    }

    fun skipPrevious(context: Context): Boolean = withController(context) {
        it.transportControls.skipToPrevious()
    }

    private inline fun withController(context: Context, block: (MediaController) -> Unit): Boolean {
        val controller = activeController(context) ?: return false
        return try {
            block(controller)
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- system panels

    /** Wi-Fi / mobile data panel (API 29+). Reading and toggling are the system's job. */
    fun openInternetPanel(context: Context) = openPanel(context, Settings.Panel.ACTION_INTERNET_CONNECTIVITY)

    fun openVolumePanel(context: Context) = openPanel(context, Settings.Panel.ACTION_VOLUME)

    fun openBluetoothSettings(context: Context) = openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)

    /**
     * Do Not Disturb lives in different screens across OEMs and API levels, and the specific
     * `Settings.ACTION_ZEN_MODE_*` constants are not all public. Rather than guess at a constant
     * that may not exist, try the known action strings in order and resolve each first.
     */
    fun openDndSettings(context: Context) = openFirstAvailableSettings(
        context,
        "android.settings.ZEN_MODE_SETTINGS",
        "android.settings.ZEN_MODE_PRIORITY_SETTINGS",
        Settings.ACTION_SOUND_SETTINGS,
    )

    /**
     * The one screen that actually grants the DND toggle to an app
     * (Settings -> Notifications -> Do Not Disturb access). Falls back through the OEM variants.
     */
    fun openDndAccessSettings(context: Context) = openFirstAvailableSettings(
        context,
        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
        "android.settings.ZEN_MODE_SETTINGS",
        Settings.ACTION_SOUND_SETTINGS,
    )

    fun openBatterySaverSettings(context: Context) =
        openSettings(context, Settings.ACTION_BATTERY_SAVER_SETTINGS)

    fun openNotificationAccessSettings(context: Context) =
        openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun openDisplaySettings(context: Context) = openSettings(context, Settings.ACTION_DISPLAY_SETTINGS)

    private fun openPanel(context: Context, panel: String) {
        val intent = Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            // Fall back to the connectivity settings screen on OEMs without panels.
            openSettings(context, Settings.ACTION_WIRELESS_SETTINGS)
        }
    }

    /** Starts the first settings action that actually resolves on this device. */
    private fun openFirstAvailableSettings(context: Context, vararg actions: String) {
        val pm = context.packageManager
        for (action in actions) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = try {
                pm.resolveActivity(intent, 0) != null
            } catch (t: Throwable) {
                false
            }
            if (!resolvable) continue
            try {
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                // Try the next candidate.
            }
        }
        openSettings(context, Settings.ACTION_SOUND_SETTINGS)
    }

    private fun openSettings(context: Context, action: String) {
        LauncherStatus.openSystemScreen(
            context,
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

}
