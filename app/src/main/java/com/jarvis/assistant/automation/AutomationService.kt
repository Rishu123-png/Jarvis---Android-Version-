package com.jarvis.assistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches state triggers while automation is armed.
 *
 * A normal Android app gets no callback for "the charger went in" while it is asleep, and exact
 * alarm delivery alone cannot express "only when on Wi-Fi". So armed automation runs inside a
 * foreground service — the one mechanism Android offers with a visible, honest cost: a quiet
 * persistent notification. Disarming removes both the service and the notification.
 *
 * Everything here is defensive: the service must survive a revoked permission, a dead engine and a
 * routine that is already running without taking the process down.
 */
class AutomationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var receiversRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Any of these can flip a state trigger; evaluate now instead of waiting a full tick.
            evaluateNow("state change")
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        registerStateReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RUN -> {
                val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID)
                val triggerLabel = intent.getStringExtra(EXTRA_TRIGGER) ?: "Trigger"
                if (workflowId != null) runRoutine(workflowId, triggerLabel)
            }

            else -> Unit
        }

        startPolling()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        if (receiversRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (t: Throwable) {
                // Already unregistered.
            }
            receiversRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------------------------
    // Work
    // -----------------------------------------------------------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                evaluateNow("poll")
                updateNotification()
                delay(AutomationTriggers.POLL_INTERVAL_MS)
            }
        }
    }

    private fun evaluateNow(reason: String) {
        // The engine allows one run at a time; a busy engine simply means this tick is skipped.
        if (AutomationEngine.isBusy) return
        val routines = AutomationTriggers.armedRoutines(this)
        if (routines.isEmpty()) {
            stopSelf()
            return
        }

        val candidate = routines.firstOrNull { routine ->
            if (routine.trigger.kind.isTimeBased || routine.trigger.kind.isSilent) {
                false
            } else if (AutomationTriggers.withinCooldown(this, routine.id)) {
                false
            } else {
                ConditionEvaluator.stateMatches(this, routine.trigger).passed
            }
        } ?: return

        AutomationTriggers.markFired(this, candidate.id)
        AutomationEngine.run(
            context = this,
            workflow = candidate,
            triggerLabel = candidate.trigger.display,
        )
        logDispatch(candidate, reason)
        updateNotification()
    }

    private fun runRoutine(workflowId: String, triggerLabel: String) {
        val routine = AutomationStore.load(this).firstOrNull { it.id == workflowId } ?: return
        if (AutomationEngine.isBusy) return
        AutomationTriggers.markFired(this, routine.id)
        AutomationEngine.run(
            context = this,
            workflow = routine.copy(enabled = true),
            triggerLabel = triggerLabel,
        )
        updateNotification()
    }

    private fun logDispatch(routine: Workflow, reason: String) {
        // Deliberately quiet: the run history is the durable record. This exists for debugging
        // builds without needing to attach a debugger.
        if (!BuildConfigHolder.isDebug) return
        android.util.Log.d(
            "JarvisTriggers",
            "fired '${routine.name}' (${routine.trigger.display}) via $reason",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Receivers
    // -----------------------------------------------------------------------------------------

    private fun registerStateReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stateReceiver, filter)
            }
            receiversRegistered = true
        } catch (t: Throwable) {
            receiversRegistered = false
        }
    }

    // -----------------------------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------------------------

    private fun startForegroundSafely() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "Automation armed",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while JARVIS triggers are active"
                    setShowBadge(false)
                }
            )
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            // A device that refuses the notification still gets the service's work; the system
            // will simply not show the chip.
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            // Ignore: the notification is cosmetic, the scheduler is not.
        }
    }

    private fun buildNotification(): android.app.Notification {
        val armed = try {
            AutomationTriggers.armedCount(this)
        } catch (t: Throwable) {
            0
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Automation armed")
            .setContentText(
                if (armed == 1) "1 routine will run on its trigger" else "$armed routines will run on their triggers"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    // -----------------------------------------------------------------------------------------
    // Companion
    // -----------------------------------------------------------------------------------------

    companion object {
        private const val CHANNEL_ONGOING = "jarvis_automation"
        private const val NOTIFICATION_ID = 1401

        private const val ACTION_START = "com.jarvis.assistant.action.START_WATCH"
        private const val ACTION_STOP = "com.jarvis.assistant.action.STOP_WATCH"
        private const val ACTION_RUN = "com.jarvis.assistant.action.RUN_ROUTINE"
        private const val EXTRA_WORKFLOW_ID = "workflow_id"
        private const val EXTRA_TRIGGER = "trigger_label"

        fun start(context: Context) {
            val intent = Intent(context, AutomationService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // Android may refuse a background start; the armed flag keeps the intent, and the
                // service comes up next time the app is opened.
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutomationService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                try {
                    context.stopService(Intent(context, AutomationService::class.java))
                } catch (ignored: Throwable) {
                    // Nothing left to stop.
                }
            }
        }

        fun runRoutine(context: Context, workflowId: String, triggerLabel: String) {
            val intent = Intent(context, AutomationService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_WORKFLOW_ID, workflowId)
                .putExtra(EXTRA_TRIGGER, triggerLabel)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // Fall back to running it in-process if the platform refuses a background start.
                val routine = AutomationStore.load(context).firstOrNull { it.id == workflowId }
                if (routine != null) {
                    AutomationEngine.run(context, routine, triggerLabel)
                }
            }
        }
    }
}

/** Keeps the debug-log gate in one place instead of importing BuildConfig everywhere. */
private object BuildConfigHolder {
    val isDebug: Boolean
        get() = try {
            com.jarvis.assistant.BuildConfig.DEBUG
        } catch (t: Throwable) {
            false
        }
}
