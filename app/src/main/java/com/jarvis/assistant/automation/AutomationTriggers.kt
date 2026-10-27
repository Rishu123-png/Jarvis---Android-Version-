package com.jarvis.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Owns the trigger feature: the global arming switch, alarm scheduling, and the decision of whether
 * the foreground service should be running.
 *
 * Honest constraints encoded here:
 *  * nothing fires unless the operator has armed triggers (one switch);
 *  * time triggers use exact alarms when the operator granted that, and otherwise fall back to an
 *    inexact alarm rather than pretending to be punctual;
 *  * state triggers are polled once a minute by the service, with a per-routine cooldown, because
 *    Android gives a normal app no push notification for "the charger went in".
 */
object AutomationTriggers {

    private const val PREFS = "jarvis_triggers"
    private const val KEY_ARMED = "armed"
    private const val KEY_LAST_FIRED = "last_fired_"

    /** Minimum gap between two fires of the same routine, so a flapping state cannot spam it. */
    const val COOLDOWN_MS = 5 * 60 * 1000L

    /** How often the service re-checks state triggers. */
    const val POLL_INTERVAL_MS = 60 * 1000L

    fun isArmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ARMED, false)

    fun armedRoutines(context: Context): List<Workflow> =
        AutomationStore.load(context).filter { it.isArmed }

    fun armedCount(context: Context): Int = armedRoutines(context).size

    /** True when the operator allowed exact alarms (Android 12+ gate). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    fun requestExactPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            // Some OEM builds hide this screen; the inexact fallback still works.
        }
    }

    /**
     * Turns the feature on or off. Arming starts the foreground service and schedules alarms;
     * disarming cancels both, so nothing runs behind the operator's back.
     */
    fun setArmed(context: Context, armed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ARMED, armed).apply()
        if (armed) {
            sync(context)
        } else {
            cancelAll(context)
            AutomationService.stop(context)
        }
    }

    /** Re-reads routines and brings the service, alarms and receivers in line with them. */
    fun sync(context: Context) {
        val app = context.applicationContext
        if (!isArmed(app)) {
            cancelAll(app)
            AutomationService.stop(app)
            return
        }

        val routines = armedRoutines(app)
        if (routines.isEmpty()) {
            cancelAll(app)
            AutomationService.stop(app)
            return
        }

        scheduleTimeTriggers(app, routines)

        // Anything that needs watching over time lives in the service.
        val needsService = routines.any { !it.trigger.kind.isTimeBased && !it.trigger.kind.isSilent }
        if (needsService) {
            AutomationService.start(app)
        } else {
            AutomationService.stop(app)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Time triggers
    // -----------------------------------------------------------------------------------------

    fun scheduleTimeTriggers(context: Context, routines: List<Workflow>) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val exact = canScheduleExact(context)

        routines.filter { it.trigger.kind.isTimeBased }.forEach { routine ->
            val time = routine.trigger.param
            val weekdays = routine.trigger.kind == TriggerKind.WEEKDAYS
            val next = ConditionEvaluator.nextOccurrence(time, weekdays) ?: return@forEach
            val pending = alarmPendingIntent(context, routine.id)

            try {
                if (exact) {
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next,
                        pending,
                    )
                } else {
                    // Inexact on purpose: better an honest "sometime soon" than a crash or a lie.
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                }
            } catch (t: Throwable) {
                // SecurityException when exact alarms were revoked between the check and the call.
                try {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
                } catch (ignored: Throwable) {
                    // Nothing more can be done.
                }
            }
        }
    }

    private fun alarmPendingIntent(context: Context, workflowId: String): PendingIntent {
        val intent = Intent(context, AutomationAlarmReceiver::class.java)
            .setAction(AutomationAlarmReceiver.ACTION_FIRE)
            .putExtra(AutomationAlarmReceiver.EXTRA_WORKFLOW_ID, workflowId)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            workflowId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        AutomationStore.load(context).forEach { routine ->
            val pending = alarmPendingIntent(context, routine.id)
            try {
                manager?.cancel(pending)
            } catch (t: Throwable) {
                // Ignore: cancelling an alarm that was never set is harmless.
            }
            pending.cancel()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Cooldowns
    // -----------------------------------------------------------------------------------------

    fun lastFired(context: Context, workflowId: String): Long =
        prefs(context).getLong(KEY_LAST_FIRED + workflowId, 0L)

    fun markFired(context: Context, workflowId: String, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_FIRED + workflowId, at).apply()
    }

    fun withinCooldown(context: Context, workflowId: String): Boolean =
        System.currentTimeMillis() - lastFired(context, workflowId) < COOLDOWN_MS

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Fires a time-based routine. The alarm itself cannot run a routine (the process may be dead), so
 * it starts the foreground service with a one-shot command.
 */
class AutomationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return
        if (!AutomationTriggers.isArmed(context)) return

        AutomationService.runRoutine(context, workflowId, "Scheduled")

        // Re-arm for the next occurrence: this alarm is one-shot by design.
        val routines = AutomationTriggers.armedRoutines(context)
        AutomationTriggers.scheduleTimeTriggers(context, routines)
    }

    companion object {
        const val ACTION_FIRE = "com.jarvis.assistant.action.FIRE_ROUTINE"
        const val EXTRA_WORKFLOW_ID = "workflow_id"
    }
}

/**
 * Re-arms everything after a reboot. Alarms do not survive a restart, and a foreground service
 * certainly does not, so this receiver is the only way "armed" stays true across a reboot.
 */
class AutomationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        try {
            // ContextCompat handles the foreground-service start rules on every API level.
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationService::class.java),
            )
            AutomationService.stop(context)
        } catch (t: Throwable) {
            // If the service cannot be touched, the alarms are still re-armed below.
        }
        AutomationTriggers.sync(context)
        // Reminders live in the same AlarmManager bucket and need the same treatment.
        try {
            com.jarvis.assistant.device.Reminders.rearm(context)
        } catch (t: Throwable) {
            // A reminder that cannot be re-armed is dropped on its next read.
        }
    }
}
