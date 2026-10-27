package com.jarvis.assistant.automation

import android.content.Context
import android.os.PowerManager
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.QuickControls
import com.jarvis.assistant.device.readBattery
import com.jarvis.assistant.device.readNetwork
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Reads live device state for [Condition]s and state-based triggers.
 *
 * Everything here is cheap and permission-free except the two already-gated reads: media state
 * needs notification access and the alert count comes from the listener. When a read is not
 * available, the evaluator returns **false** rather than guessing — a routine must never branch on
 * a value JARVIS could not actually observe.
 */
object ConditionEvaluator {

    data class Evaluation(val passed: Boolean, val describe: String)

    fun evaluate(context: Context, condition: Condition): Evaluation = try {
        when (condition.type) {
            ConditionType.BATTERY_BELOW -> {
                val threshold = condition.param.toIntOrNull() ?: 20
                val level = readBattery(context).percent
                Evaluation(level < threshold, "battery $level% < $threshold%")
            }

            ConditionType.BATTERY_ABOVE -> {
                val threshold = condition.param.toIntOrNull() ?: 80
                val level = readBattery(context).percent
                Evaluation(level > threshold, "battery $level% > $threshold%")
            }

            ConditionType.CHARGING -> {
                val battery = readBattery(context)
                Evaluation(battery.isCharging, "charging ${battery.plugLabel.lowercase()}")
            }

            ConditionType.NOT_CHARGING -> {
                val battery = readBattery(context)
                Evaluation(!battery.isCharging, "not charging")
            }

            ConditionType.WIFI -> {
                val transport = readNetwork(context).transport
                Evaluation(transport.equals("WIFI", ignoreCase = true), "transport $transport")
            }

            ConditionType.MOBILE_DATA -> {
                val transport = readNetwork(context).transport
                Evaluation(transport.contains("MOBILE", ignoreCase = true), "transport $transport")
            }

            ConditionType.MEDIA_PLAYING -> {
                if (!NotificationRepository.hasAccess(context)) {
                    Evaluation(false, "media state unreadable (no notification access)")
                } else {
                    val playing = QuickControls.isPlaying(context)
                    Evaluation(playing, if (playing) "media playing" else "media idle")
                }
            }

            ConditionType.TIME_WINDOW -> {
                val window = parseWindow(condition.param)
                if (window == null) {
                    Evaluation(false, "invalid window '${condition.param}'")
                } else {
                    val now = LocalTime.now()
                    val (from, to) = window
                    val inside = if (from <= to) {
                        !now.isBefore(from) && !now.isAfter(to)
                    } else {
                        // Window crosses midnight, e.g. 22:00-06:00.
                        !now.isBefore(from) || !now.isAfter(to)
                    }
                    Evaluation(inside, "now ${now.format(HHMM)}")
                }
            }

            ConditionType.ALERTS_ABOVE -> {
                if (!NotificationRepository.hasAccess(context)) {
                    Evaluation(false, "alert count unreadable")
                } else {
                    val threshold = condition.param.toIntOrNull() ?: 0
                    val count = NotificationRepository.notifications.value.size
                    Evaluation(count > threshold, "$count alerts > $threshold")
                }
            }

            ConditionType.APP_INSTALLED -> {
                val pkg = condition.param.trim()
                if (pkg.isEmpty()) {
                    Evaluation(false, "no package set")
                } else {
                    val installed = try {
                        context.packageManager.getLaunchIntentForPackage(pkg) != null
                    } catch (t: Throwable) {
                        false
                    }
                    Evaluation(installed, if (installed) "$pkg installed" else "$pkg missing")
                }
            }

            ConditionType.SCREEN_ON -> {
                val interactive = power(context)?.isInteractive ?: false
                Evaluation(interactive, if (interactive) "screen on" else "screen off")
            }
        }
    } catch (t: Throwable) {
        Evaluation(false, "check failed: ${t.javaClass.simpleName}")
    }

    // ---------------------------------------------------------------------------------------
    // Trigger helpers
    // ---------------------------------------------------------------------------------------

    /** Fires when a state trigger would: charging, unplugged, wifi, battery, unlock, alert. */
    fun stateMatches(context: Context, trigger: RoutineTrigger): Evaluation = try {
        when (trigger.kind) {
            TriggerKind.CHARGING -> {
                val battery = readBattery(context)
                Evaluation(battery.isCharging, "charger ${battery.plugLabel.lowercase()}")
            }

            TriggerKind.UNPLUGGED -> {
                val battery = readBattery(context)
                Evaluation(!battery.isCharging, "charger ${battery.plugLabel.lowercase()}")
            }

            TriggerKind.WIFI_CONNECTED -> {
                val transport = readNetwork(context).transport
                Evaluation(transport.equals("WIFI", ignoreCase = true), "transport $transport")
            }

            TriggerKind.BATTERY_LOW -> {
                val threshold = trigger.param.toIntOrNull() ?: 20
                val level = readBattery(context).percent
                Evaluation(level <= threshold, "battery $level% ≤ $threshold%")
            }

            TriggerKind.NOTIFICATION -> {
                val pkg = trigger.param.trim()
                if (pkg.isEmpty()) {
                    Evaluation(false, "no package set")
                } else if (!NotificationRepository.hasAccess(context)) {
                    Evaluation(false, "needs notification access")
                } else {
                    val recent = NotificationRepository.notifications.value
                        .any { it.packageName == pkg && it.postedAt >= minEdgeTime() }
                    Evaluation(recent, "alert from $pkg")
                }
            }

            TriggerKind.UNLOCK -> {
                val interactive = power(context)?.isInteractive ?: false
                Evaluation(interactive, if (interactive) "interactive" else "asleep")
            }

            TriggerKind.BOOT -> Evaluation(true, "boot")

            else -> Evaluation(false, "not a state trigger")
        }
    } catch (t: Throwable) {
        Evaluation(false, "check failed: ${t.javaClass.simpleName}")
    }

    /**
     * Notification triggers only care about alerts posted recently. Without this window, one
     * unread message would re-fire the routine on every poll.
     */
    fun minEdgeTime(): Long = System.currentTimeMillis() - 2 * 60 * 1000L

    /** The next wall-clock occurrence of a HH:mm trigger, as an epoch millisecond. */
    fun nextOccurrence(time: String, weekdaysOnly: Boolean, from: Long = System.currentTimeMillis()): Long? {
        val local = parseTime(time) ?: return null
        var day = LocalDate.now()
        repeat(8) {
            val candidate = day.atTime(local)
            val weekdayOk = !weekdaysOnly ||
                (day.dayOfWeek != java.time.DayOfWeek.SATURDAY &&
                    day.dayOfWeek != java.time.DayOfWeek.SUNDAY)
            val millis = candidate.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (weekdayOk && millis > from + 1_000) return millis
            day = day.plusDays(1)
        }
        return null
    }

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

    fun parseTime(raw: String): LocalTime? = try {
        LocalTime.parse(raw.trim(), HHMM)
    } catch (t: Throwable) {
        null
    }

    /** Parses "HH:mm-HH:mm", accepting an en dash or a single hyphen. */
    fun parseWindow(raw: String): Pair<LocalTime, LocalTime>? {
        val cleaned = raw.replace("–", "-").replace("—", "-").trim()
        val parts = cleaned.split("-").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val from = parseTime(parts[0]) ?: return null
        val to = parseTime(parts[1]) ?: return null
        return from to to
    }

    private fun power(context: Context): PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
}
