package com.jarvis.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.QuickControls
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Executes one [WorkflowStep].
 *
 * Every action maps to a documented Android intent or a permission-free API. Where Android does not
 * permit an action outright, the step *opens the system surface* and reports that honestly rather
 * than claiming success on something that did not happen.
 */
object StepExecutor {

    sealed interface Result {
        data class Ok(val detail: String) : Result
        data class Failed(val reason: String) : Result
        data class Skipped(val reason: String) : Result
    }

    /** Human-readable warning shown before a run when a step cannot possibly work. */
    fun preflight(context: Context, workflow: Workflow): List<String> {
        val warnings = mutableListOf<String>()
        if (workflow.requiresAccessibility && !AccessibilityStatus.isServiceEnabled(context)) {
            warnings.add("Needs accessibility access for system gestures or screen presses.")
        }
        if (workflow.requiresNotificationAccess && !NotificationRepository.hasAccess(context)) {
            warnings.add("Needs notification access for media or alert steps.")
        }
        if (workflow.steps.any { it.action == ActionType.PRESS_TEXT } &&
            !AccessibilityStatus.isScreenPressAllowed(context)
        ) {
            warnings.add("\"Press on screen\" steps are disabled until you allow them.")
        }
        val unconfigured = workflow.steps.count { !it.isConfigured }
        if (unconfigured > 0) warnings.add("$unconfigured step(s) have no value set.")
        if (workflow.steps.isEmpty()) warnings.add("This routine has no steps yet.")
        return warnings
    }

    suspend fun execute(context: Context, step: WorkflowStep): Result {
        return try {
            when (step.action) {
                ActionType.OPEN_APP -> {
                    val pkg = step.param.substringBefore('/')
                    val activity = step.param.substringAfter('/', "")
                    if (pkg.isBlank()) return Result.Failed("No app chosen")
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: return Result.Failed("$pkg is not installed")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (activity.isNotBlank()) {
                        intent.component = android.content.ComponentName(pkg, activity)
                    }
                    context.startActivity(intent)
                    Result.Ok("Opened ${appLabel(context, pkg)}")
                }

                ActionType.OPEN_URL -> {
                    val url = normaliseUrl(step.param)
                    if (url == null) return Result.Failed("Not a valid address")
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Opened $url")
                }

                ActionType.DIAL_NUMBER -> {
                    val number = step.param.trim()
                    if (number.isEmpty()) return Result.Failed("No number set")
                    // ACTION_DIAL only opens the dialer with the number filled in. JARVIS never
                    // places a call: that would need CALL_PHONE, which this app does not hold.
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Dialer opened, not called")
                }

                ActionType.COMPOSE_SMS -> {
                    val number = step.param.trim()
                    if (number.isEmpty()) return Result.Failed("No number set")
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Composer opened, not sent")
                }

                ActionType.SET_TIMER -> {
                    val seconds = step.param.toIntOrNull() ?: 0
                    if (seconds <= 0) return Result.Failed("Timer length must be positive")
                    context.startActivity(
                        Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Timer set for ${seconds / 60}m ${seconds % 60}s")
                }

                ActionType.TORCH_ON -> {
                    val state = QuickControls.Torch.toggle(context)
                    when {
                        state == null -> Result.Failed("No flashlight available")
                        state -> Result.Ok("Torch on")
                        // It was already on, so the toggle turned it off — put it back.
                        else -> {
                            QuickControls.Torch.toggle(context)
                            Result.Ok("Torch on")
                        }
                    }
                }

                ActionType.TORCH_OFF -> {
                    QuickControls.Torch.setOff(context)
                    Result.Ok("Torch off")
                }

                ActionType.SET_VOLUME -> {
                    val percent = step.param.toIntOrNull() ?: return Result.Failed("Not a number")
                    val applied = QuickControls.setMediaVolume(context, percent)
                    Result.Ok("Media volume $applied%")
                }

                ActionType.MEDIA_TOGGLE -> {
                    if (!NotificationRepository.hasAccess(context)) {
                        return Result.Skipped("Notification access is off")
                    }
                    val playing = QuickControls.togglePlayback(context)
                        ?: return Result.Failed("No active media session")
                    Result.Ok(if (playing) "Playing" else "Paused")
                }

                ActionType.DISMISS_NOTIFICATIONS -> {
                    if (!NotificationRepository.hasAccess(context)) {
                        return Result.Skipped("Notification access is off")
                    }
                    val cleared = NotificationRepository.dismissAllClearable()
                    Result.Ok("Cleared $cleared alert(s)")
                }

                ActionType.OPEN_PANEL -> openPanel(context, step.param)

                ActionType.WAIT -> {
                    val seconds = (step.param.toIntOrNull() ?: 0).coerceIn(0, 120)
                    delay(seconds * 1000L)
                    Result.Ok("Waited ${seconds}s")
                }

                ActionType.GLOBAL_ACTION -> performGlobal(context, step.param)

                ActionType.PRESS_TEXT -> {
                    if (!AccessibilityStatus.isServiceEnabled(context)) {
                        return Result.Skipped("Accessibility access is off")
                    }
                    if (!AccessibilityStatus.isScreenPressAllowed(context)) {
                        return Result.Skipped("Screen pressing is not allowed")
                    }
                    if (JarvisAccessibilityService.pressByText(step.param)) {
                        Result.Ok("Pressed \"${step.param}\"")
                    } else {
                        Result.Failed("No visible control labelled \"${step.param}\"")
                    }
                }
            }
        } catch (t: Throwable) {
            Result.Failed(t.message?.take(80) ?: "Step failed")
        }
    }

    private fun openPanel(context: Context, key: String): Result {
        val normalised = key.trim().lowercase(Locale.US)
        return when {
            normalised.startsWith("wifi") || normalised.contains("internet") -> {
                QuickControls.openInternetPanel(context); Result.Ok("Opened connectivity panel")
            }
            normalised.startsWith("blue") -> {
                QuickControls.openBluetoothSettings(context); Result.Ok("Opened Bluetooth settings")
            }
            normalised.startsWith("dnd") || normalised.contains("disturb") -> {
                QuickControls.openDndSettings(context); Result.Ok("Opened Do Not Disturb")
            }
            normalised.startsWith("display") || normalised.contains("bright") -> {
                QuickControls.openDisplaySettings(context); Result.Ok("Opened display settings")
            }
            normalised.startsWith("volume") -> {
                QuickControls.openVolumePanel(context); Result.Ok("Opened volume panel")
            }
            normalised.startsWith("battery") -> {
                QuickControls.openBatterySaverSettings(context); Result.Ok("Opened battery saver")
            }
            else -> Result.Failed("Unknown panel \"$key\" (try wifi, bluetooth, dnd, display, volume, battery)")
        }
    }

    private fun performGlobal(context: Context, key: String): Result {
        if (!AccessibilityStatus.isServiceEnabled(context)) {
            return Result.Skipped("Accessibility access is off")
        }
        val action = when (key.trim().lowercase(Locale.US)) {
            "back", "navigate back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home", "go home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "shade", "notification shade" ->
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick", "quick settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock", "lock screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return Result.Failed(
                "Unknown gesture \"$key\" (try back, home, recents, notifications, quick, lock)"
            )
        }
        return if (JarvisAccessibilityService.perform(action)) {
            Result.Ok("Gesture: ${key.trim().lowercase(Locale.US)}")
        } else {
            Result.Failed("Gesture rejected by the system")
        }
    }

    private fun normaliseUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return try {
            val uri = Uri.parse(withScheme)
            if (uri.host.isNullOrBlank()) null else withScheme
        } catch (t: Throwable) {
            null
        }
    }

    private fun appLabel(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }
}
