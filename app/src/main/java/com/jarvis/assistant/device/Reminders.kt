package com.jarvis.assistant.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Reminders: "remind me in 10 minutes to call dad".
 *
 * A reminder is a real notification at a real time, not a clock alarm — it does not wake the screen
 * to a full alarm UI, and it carries the operator's own words.
 *
 * Storage is a small JSON list in app-private preferences, so a reminder survives the process being
 * killed. Exact alarms are used when the operator has allowed them and an inexact one otherwise,
 * which is the same contract the automation triggers already follow. Nothing is lost on reboot:
 * [rearm] re-registers everything still in the future.
 */
object Reminders {

    private const val PREFS = "jarvis_reminders"
    private const val KEY_LIST = "list_v1"
    private const val CHANNEL = "jarvis_reminders"
    private const val MAX_PENDING = 50

    const val ACTION_FIRE = "com.jarvis.assistant.action.FIRE_REMINDER"
    const val EXTRA_ID = "reminder_id"

    data class Reminder(val id: String, val at: Long, val text: String)

    data class Outcome(val ok: Boolean, val message: String)

    fun schedule(context: Context, inSeconds: Long?, atClock: String?, text: String): Outcome {
        val clean = text.trim()
        if (clean.isEmpty()) return Outcome(false, "A reminder needs something to say.")

        val triggerAt = when {
            inSeconds != null && inSeconds > 0 -> System.currentTimeMillis() + inSeconds * 1_000L
            atClock != null -> nextOccurrence(atClock)
            else -> return Outcome(false, "I need a time — try \"in 10 minutes\" or \"at 18:30\".")
        }
        if (triggerAt <= System.currentTimeMillis()) return Outcome(false, "That time has already passed.")

        val reminder = Reminder(UUID.randomUUID().toString(), triggerAt, clean.take(160))
        val existing = load(context).filter { it.at > System.currentTimeMillis() }
        store(context, (existing + reminder).sortedBy { it.at }.take(MAX_PENDING))

        val scheduled = arm(context, reminder)
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(triggerAt))
        val whenPart = if (inSeconds != null) "in ${describe(inSeconds)}" else "at $clock"

        return when (scheduled) {
            ArmResult.NO_NOTIFICATIONS -> Outcome(
                true,
                "Reminder saved for $whenPart, but notifications are off — it may not appear.",
            )
            ArmResult.INEXACT -> Outcome(true, "Reminder saved for $whenPart (Android may be a minute late).")
            ArmResult.FAILED -> Outcome(false, "Reminder saved for $whenPart, but the alarm could not be armed.")
            ArmResult.EXACT -> Outcome(true, "Reminder set for $whenPart.")
        }
    }

    fun pending(context: Context): List<Reminder> =
        load(context).filter { it.at > System.currentTimeMillis() }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        load(context).forEach { reminder ->
            manager?.cancel(pendingIntent(context, reminder.id))
        }
        store(context, emptyList())
    }

    /** Re-arms every future reminder. Called after a reboot or an app update. */
    fun rearm(context: Context) {
        val now = System.currentTimeMillis()
        val alive = load(context).filter { it.at > now }
        store(context, alive)
        alive.forEach { arm(context, it) }
    }

    fun fire(context: Context, id: String) {
        val all = load(context)
        val reminder = all.firstOrNull { it.id == id } ?: return
        store(context, all.filterNot { it.id == id })
        post(context, reminder)
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    enum class ArmResult { EXACT, INEXACT, NO_NOTIFICATIONS, FAILED }

    private fun arm(context: Context, reminder: Reminder): ArmResult {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            // Still arm it: the operator may switch notifications on before it fires.
            registerAlarm(context, reminder)
            return ArmResult.NO_NOTIFICATIONS
        }
        return registerAlarm(context, reminder)
    }

    private fun registerAlarm(context: Context, reminder: Reminder): ArmResult {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return ArmResult.FAILED
        val intent = pendingIntent(context, reminder.id)
        return try {
            val exact = if (Build.VERSION.SDK_INT >= 31) manager.canScheduleExactAlarms() else true
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
                ArmResult.EXACT
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
                ArmResult.INEXACT
            }
        } catch (t: Throwable) {
            ArmResult.FAILED
        }
    }

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(context: Context, reminder: Reminder) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Reminders",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Reminders you asked JARVIS for" },
                )
            }
        } catch (t: Throwable) {
            // Channel already exists.
        }

        val open = Intent(context, com.jarvis.assistant.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val content = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.jarvis.assistant.R.mipmap.ic_launcher)
            .setContentTitle("JARVIS · REMINDER")
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
        } catch (t: Throwable) {
            // Permission revoked between scheduling and firing.
        }
    }

    private fun describe(seconds: Long): String = when {
        seconds < 60 -> "$seconds seconds"
        seconds < 3_600 -> "${seconds / 60} minutes"
        else -> "${seconds / 3_600} hours"
    }

    private fun nextOccurrence(clock: String): Long {
        val parts = clock.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return Long.MAX_VALUE
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun load(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val text = item.optString("text")
                    val at = item.optLong("at")
                    if (id.isNotBlank() && text.isNotBlank() && at > 0) add(Reminder(id, at, text))
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun store(context: Context, list: List<Reminder>) {
        val array = JSONArray()
        list.forEach { reminder ->
            array.put(
                JSONObject()
                    .put("id", reminder.id)
                    .put("at", reminder.at)
                    .put("text", reminder.text),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, array.toString())
            .apply()
    }
}

/** Fired by AlarmManager. Not exported: nothing else can reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        Reminders.fire(context, id)
    }
}
