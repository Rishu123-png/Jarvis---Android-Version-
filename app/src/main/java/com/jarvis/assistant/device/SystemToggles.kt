package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.assistant.shell.ShizukuShell
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The switches the phone keeps to itself.
 *
 * Android stopped letting ordinary apps turn Wi-Fi, Bluetooth and mobile data on and off years ago,
 * and airplane mode and hotspot were never available at all. There are only three honest ways to
 * honour "switch it off":
 *
 *  1. **The privileged tier.** Shizuku gives the app the same identity as `adb shell`, which can run
 *     `svc wifi disable`. This is the only path that actually flips the switch silently.
 *  2. **A system setting the app may write.** Brightness and rotation are plain settings, and with
 *     the operator's permission they can be written directly, no shell needed.
 *  3. **The system's own panel.** When neither is available the panel is opened with the switch in
 *     front of the operator, and the reply says exactly that. It never says "done" for a switch
 *     somebody else still has to tap.
 *
 * Everything that can be decided without Android — reading "on"/"off"/"toggle", building the
 * command, turning a percentage into the 0–255 scale the system stores — is pure and unit-tested.
 */
object SystemToggles {

    /** A switch with an on and an off. */
    enum class Switch(val label: String) {
        WIFI("Wi-Fi"),
        BLUETOOTH("Bluetooth"),
        MOBILE_DATA("Mobile data"),
        AIRPLANE("Airplane mode"),
        HOTSPOT("Hotspot"),
        ;
    }

    enum class Desired { ON, OFF, TOGGLE }

    /** What a step actually achieved, in words the operator can trust. */
    sealed interface Outcome {
        data class Done(val detail: String) : Outcome
        /** The panel is open and one tap is still needed. Says so. */
        data class NeedsTap(val detail: String) : Outcome
        data class Failed(val reason: String) : Outcome
        data class Unavailable(val reason: String) : Outcome
    }

    // ---------------------------------------------------------------------------------------------
    // Pure decisions
    // ---------------------------------------------------------------------------------------------

    /** "on", "off", "disable", "band karo" — anything else is null and the caller says so. */
    fun parseDesired(raw: String): Desired? = when (raw.trim().lowercase(Locale.US)) {
        "", "toggle", "switch", "flip" -> Desired.TOGGLE
        "on", "enable", "start", "1", "true", "yes", "chalu", "on karo" -> Desired.ON
        "off", "disable", "stop", "0", "false", "no", "band", "band karo" -> Desired.OFF
        else -> null
    }

    /** The adb-level command that flips [switch] to [on], or null when the platform has no such verb. */
    fun commandsFor(switch: Switch, on: Boolean): String? = when (switch) {
        Switch.WIFI -> if (on) "svc wifi enable" else "svc wifi disable"
        Switch.BLUETOOTH -> if (on) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable"
        Switch.MOBILE_DATA -> if (on) "svc data enable" else "svc data disable"
        Switch.AIRPLANE -> if (on) {
            "settings put global airplane_mode_on 1 && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
        } else {
            "settings put global airplane_mode_on 0 && " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        }
        Switch.HOTSPOT -> if (on) "cmd wifi start-softap" else "cmd wifi stop-softap"
    }

    /** The setting a `settings get` answers, used to resolve "toggle" and to confirm the result. */
    fun stateKeyFor(switch: Switch): String? = when (switch) {
        Switch.WIFI -> "global wifi_on"
        Switch.BLUETOOTH -> "global bluetooth_on"
        Switch.MOBILE_DATA -> "global mobile_data"
        Switch.AIRPLANE -> "global airplane_mode_on"
        Switch.HOTSPOT -> null
    }

    /** `settings get` prints 1 or 0. Hotspot has no such key, so "toggle" means "start it". */
    fun readState(raw: String?): Boolean? = when (raw?.trim()) {
        "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> null
    }

    /**
     * Brightness is stored 0–255, but people speak in percent. Returns null when the words are not
     * a level; -1 means "automatic".
     */
    fun brightnessValue(raw: String): Int? {
        val word = raw.trim().lowercase(Locale.US)
        if (word.isEmpty()) return null
        if (word.startsWith("auto") || word == "automatic") return -1
        val percent = word.removeSuffix("%").trim().toIntOrNull() ?: return null
        if (percent !in 0..100) return null
        return Math.round(percent * 255f / 100f)
    }

    /** Rotation: true means locked upright (rotation off). */
    fun rotationLocked(raw: String): Boolean? = when (parseDesired(raw)) {
        Desired.ON -> true
        Desired.OFF -> false
        else -> null
    }

    // ---------------------------------------------------------------------------------------------
    // Doing it
    // ---------------------------------------------------------------------------------------------

    /**
     * Flips one switch, trying the privileged tier first and telling the truth about which tier
     * answered.
     */
    suspend fun setSwitch(context: Context, switch: Switch, desired: Desired): Outcome {
        ShizukuShell.refresh(context)
        val ready = ShizukuShell.isReady

        if (!ready) {
            return openPanel(context, switch)
        }

        val resolved = when (desired) {
            Desired.ON -> true
            Desired.OFF -> false
            Desired.TOGGLE -> currentlyOn(context, switch)?.not() ?: true
        }

        val command = commandsFor(switch, resolved)
            ?: return Outcome.Unavailable("${switch.label} cannot be switched here.")

        return when (val outcome = ShizukuShell.exec(context, command)) {
            is ShizukuShell.Outcome.Failed -> Outcome.Unavailable(outcome.reason)
            is ShizukuShell.Outcome.Ok -> {
                if (outcome.exitCode != 0) {
                    val why = outcome.output.lineSequence().firstOrNull().orEmpty().take(80)
                    Outcome.Failed("${switch.label} did not change" + if (why.isBlank()) "" else " · $why")
                } else {
                    // The system applies these asynchronously; a short settle makes the next step
                    // (say, opening the browser) land on a connected network instead of a dead one.
                    delay(350)
                    Outcome.Done("${switch.label} ${if (resolved) "on" else "off"}")
                }
            }
        }
    }

    /** Reads a switch's current state through the privileged tier, or null when it cannot. */
    suspend fun currentlyOn(context: Context, switch: Switch): Boolean? {
        val key = stateKeyFor(switch) ?: return null
        if (!ShizukuShell.isReady) return null
        val outcome = ShizukuShell.exec(context, "settings get $key")
        if (outcome !is ShizukuShell.Outcome.Ok) return null
        return readState(outcome.output.lineSequence().firstOrNull { it.isNotBlank() })
    }

    /** Sets screen brightness, either a level or automatic. */
    suspend fun setBrightness(context: Context, raw: String): Outcome {
        val value = brightnessValue(raw)
            ?: return Outcome.Failed("Say a brightness from 0 to 100, or \"auto\".")

        if (value == -1) {
            return applyOrAsk(context, "system screen_brightness_mode", "1", "Brightness automatic")
        }

        // Automatic would immediately override a manual level, so it is switched off first. If this
        // fails the level below still applies on most phones; nothing is claimed about it either way.
        applySetting(context, "system screen_brightness_mode", "0")
        val percent = Math.round(value * 100f / 255f)
        return applyOrAsk(context, "system screen_brightness", value.toString(), "Brightness $percent%")
    }

    /** Locks or unlocks rotation. */
    suspend fun setRotation(context: Context, raw: String): Outcome {
        val locked = rotationLocked(raw)
            ?: return Outcome.Failed("Say \"rotation lock on\" or \"off\".")
        return applyOrAsk(
            context = context,
            key = "system accelerometer_rotation",
            value = if (locked) "0" else "1",
            detail = if (locked) "Rotation locked" else "Rotation unlocked",
        )
    }

    /** Tries both real tiers, and opens the permission page only when neither worked. */
    private suspend fun applyOrAsk(context: Context, key: String, value: String, detail: String): Outcome {
        if (applySetting(context, key, value)) return Outcome.Done(detail)
        return try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Outcome.NeedsTap("Allow JARVIS to change system settings, then ask again.")
        } catch (t: Throwable) {
            Outcome.Failed("I cannot change that setting on this phone.")
        }
    }

    /**
     * Writes one system setting. The app's own permission is tried first because it needs no service;
     * the privileged tier is the fallback.
     */
    private suspend fun applySetting(context: Context, key: String, value: String): Boolean {
        val parts = key.split(' ', limit = 2)
        if (parts.size != 2) return false
        val (namespace, name) = parts

        val numeric = value.toIntOrNull()
        if (namespace == "system" && numeric != null) {
            try {
                if (Settings.System.canWrite(context) &&
                    Settings.System.putInt(context.contentResolver, name, numeric)
                ) {
                    return true
                }
            } catch (t: Throwable) {
                // Permission revoked between the check and the write, or a vendor ROM refusing it.
            }
        }

        ShizukuShell.refresh(context)
        if (ShizukuShell.isReady) {
            val outcome = ShizukuShell.exec(context, "settings put $namespace $name $value")
            if (outcome is ShizukuShell.Outcome.Ok && outcome.exitCode == 0) return true
        }
        return false
    }

    /** The system panel for this switch, with the toggle in view. */
    private fun openPanel(context: Context, switch: Switch): Outcome {
        val intent = when (switch) {
            Switch.WIFI, Switch.MOBILE_DATA -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            Switch.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            Switch.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            Switch.HOTSPOT -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            Outcome.NeedsTap(
                "${switch.label}: the switch is open on screen. Connect Shizuku to let me do it myself.",
            )
        } catch (t: Throwable) {
            Outcome.Failed("I could not open the ${switch.label} control.")
        }
    }
}
