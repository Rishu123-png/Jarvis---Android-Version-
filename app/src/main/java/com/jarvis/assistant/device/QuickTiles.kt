package com.jarvis.assistant.device

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R

/**
 * Two Quick Settings tiles: open JARVIS, and the torch.
 *
 * Deliberately the two things worth a single tap from the shade. Anything more (DND, music, a voice
 * toggle) belongs in the app, where the state can be shown honestly — a tile that claims to be
 * "listening" would be a lie, because there is no always-on microphone.
 */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "JARVIS"
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                openFromTile34(intent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (t: Throwable) {
            // The shade refused to collapse; the app still launches on the next tap.
        }
    }

    @RequiresApi(34)
    private fun openFromTile34(intent: Intent) {
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }
}

/**
 * Torch tile.
 *
 * Works without opening the app and without Shizuku: `CameraManager.setTorchMode` is a public API.
 * The tile keeps its own on/off flag, so a torch switched on from the CONTROLS screen can read as
 * off here — hence the label says JARVIS TORCH rather than pretending to know every other switch.
 */
class TorchTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val next = !isOn()
        setTorch(this, next)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = isOn()
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (on) "JARVIS TORCH ON" else "JARVIS TORCH"
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }

    private fun isOn(): Boolean = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_TORCH, false)

    companion object {
        private const val PREFS = "jarvis_tiles"
        private const val KEY_TORCH = "torch_on"

        /** Shared with the tile only; the app toggles the torch through QuickControls. */
        fun setTorch(context: Context, on: Boolean): Boolean {
            val id = torchId(context)
            val manager = context.getSystemService(CameraManager::class.java)
            if (id == null || manager == null) return false
            return try {
                manager.setTorchMode(id, on)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_TORCH, on)
                    .apply()
                true
            } catch (t: Throwable) {
                false
            }
        }

        private fun torchId(context: Context): String? {
            val manager = context.getSystemService(CameraManager::class.java) ?: return null
            return try {
                manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}
