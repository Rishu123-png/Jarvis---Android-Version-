package com.jarvis.assistant.automation

import android.content.Context
import android.os.BatteryManager
import com.jarvis.assistant.device.NotificationRepository

/**
 * Checks that something actually happened.
 *
 * Automation until now trusted each step's own report. A tap can report success while the app it
 * meant to reach never opened, so a routine can now say "verify this" and stop or continue on the
 * answer. Failure text is the point: a routine that fails should tell the operator which check saw
 * what, not just "failed".
 *
 * Grammar (one line, case-insensitive):
 *
 *   app:com.whatsapp              an app is in front (needs accessibility)
 *   screen:Send                   a label is on screen (needs accessibility)
 *   notif:hi                      a notification containing this text exists
 *   battery<20 / battery>80       charge level
 *   charging / not_charging
 *   wifi / mobile                 network transport
 */
object Verify {

    sealed interface Result {
        data class Passed(val detail: String) : Result
        data class Failed(val detail: String) : Result
        data class Unavailable(val detail: String) : Result
    }

    fun evaluate(context: Context, spec: String): Result {
        val raw = spec.trim()
        if (raw.isEmpty()) return Result.Failed("a VERIFY step needs a check")

        val lower = raw.lowercase()

        when {
            lower == "charging" -> return if (isCharging(context)) {
                Result.Passed("charging")
            } else {
                Result.Failed("not charging")
            }

            lower == "not_charging" -> return if (isCharging(context)) {
                Result.Failed("still charging")
            } else {
                Result.Passed("not charging")
            }

            lower == "wifi" || lower == "mobile" -> {
                val transport = try {
                    com.jarvis.assistant.device.readNetwork(context).transport.lowercase()
                } catch (t: Throwable) {
                    ""
                }
                val wanted = if (lower == "wifi") "wi-fi" else "cellular"
                return if (transport.contains(wanted)) {
                    Result.Passed(transport)
                } else {
                    Result.Failed("on $transport, not $lower")
                }
            }
        }

        Regex("^battery\\s*([<>])\\s*(\\d{1,3})$").find(lower)?.let { match ->
            val level = batteryPercent(context)
            val threshold = match.groupValues[2].toIntOrNull() ?: return@let
            val ok = if (match.groupValues[1] == "<") level < threshold else level > threshold
            return if (ok) {
                Result.Passed("battery $level%")
            } else {
                Result.Failed("battery is $level%, needed ${match.groupValues[1]}$threshold%")
            }
        }

        when {
            lower.startsWith("app:") -> {
                val wanted = raw.substringAfter("app:").trim()
                if (wanted.isEmpty()) return Result.Failed("app: needs a package name")
                if (!AccessibilityStatus.isServiceEnabled(context)) {
                    return Result.Unavailable("screen control is off, so the foreground app is unknown")
                }
                val front = JarvisAccessibilityService.currentPackage()
                    ?: return Result.Unavailable("the foreground app could not be read")
                return if (front.equals(wanted, ignoreCase = true)) {
                    Result.Passed("$wanted is in front")
                } else {
                    Result.Failed("$front is in front, not $wanted")
                }
            }

            lower.startsWith("screen:") -> {
                val wanted = raw.substringAfter("screen:").trim()
                if (wanted.isEmpty()) return Result.Failed("screen: needs a label")
                if (!AccessibilityStatus.isServiceEnabled(context)) {
                    return Result.Unavailable("screen control is off")
                }
                val labels = JarvisAccessibilityService.visibleLabels(60)
                val found = labels.any { it.equals(wanted, ignoreCase = true) } ||
                    labels.any { it.contains(wanted, ignoreCase = true) }
                return if (found) {
                    Result.Passed("\"$wanted\" is on screen")
                } else {
                    Result.Failed("\"$wanted\" is not on screen")
                }
            }

            lower.startsWith("notif:") -> {
                val wanted = raw.substringAfter("notif:").trim()
                if (wanted.isEmpty()) return Result.Failed("notif: needs some text")
                if (!NotificationRepository.hasAccess(context)) {
                    return Result.Unavailable("notification access is off")
                }
                val hit = NotificationRepository.notifications.value.any { alert ->
                    alert.title.contains(wanted, ignoreCase = true) ||
                        alert.text.contains(wanted, ignoreCase = true) ||
                        alert.appLabel.contains(wanted, ignoreCase = true)
                }
                return if (hit) {
                    Result.Passed("a notification mentions \"$wanted\"")
                } else {
                    Result.Failed("no notification mentions \"$wanted\"")
                }
            }
        }

        return Result.Failed("unknown check \"$raw\" (try app:, screen:, notif:, battery<20, charging, wifi)")
    }

    private fun batteryPercent(context: Context): Int = try {
        val manager = context.getSystemService(BatteryManager::class.java)
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
    } catch (t: Throwable) {
        0
    }

    private fun isCharging(context: Context): Boolean = try {
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } catch (t: Throwable) {
        false
    }
}
