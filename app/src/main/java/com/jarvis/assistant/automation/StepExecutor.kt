package com.jarvis.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.device.DndControl
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.QuickControls
import com.jarvis.assistant.ai.Translator
import com.jarvis.assistant.device.Downloads
import com.jarvis.assistant.device.Gallery
import com.jarvis.assistant.device.Reminders
import com.jarvis.assistant.device.QuickCapture
import com.jarvis.assistant.shell.ShizukuShell
import com.jarvis.assistant.voice.Contacts
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Executes one leaf [WorkflowStep].
 *
 * Every action maps to a documented Android intent, a permission-free API, or an explicit network
 * call the operator asked for. Where Android does not permit an action outright, the step *opens the
 * system surface* and reports that honestly rather than claiming success on something that did not
 * happen.
 *
 * Control-flow actions are interpreted by [AutomationEngine], never here.
 */
object StepExecutor {

    sealed interface Result {
        data class Ok(val detail: String) : Result
        data class Failed(val reason: String) : Result
        data class Skipped(val reason: String) : Result
    }

    private const val WEBHOOK_CONNECT_TIMEOUT_MS = 8_000
    private const val WEBHOOK_READ_TIMEOUT_MS = 10_000
    private const val NOTIFY_CHANNEL = "jarvis_runs"

    /** Human-readable warnings shown before a run when a step cannot possibly work. */
    fun preflight(context: Context, workflow: Workflow): List<String> {
        val warnings = mutableListOf<String>()
        val all = workflow.flatSteps

        if (workflow.requiresAccessibility && !AccessibilityStatus.isServiceEnabled(context)) {
            warnings.add("Needs accessibility access for system gestures or screen presses.")
        }
        if (workflow.requiresNotificationAccess && !NotificationRepository.hasAccess(context)) {
            warnings.add("Needs notification access for media or alert steps.")
        }
        if (all.any { it.action == ActionType.PRESS_TEXT } &&
            !AccessibilityStatus.isScreenPressAllowed(context)
        ) {
            warnings.add("\"Press on screen\" steps are disabled until you allow them.")
        }
        if (all.any { it.action == ActionType.SET_DND } && !DndControl.hasAccess(context)) {
            warnings.add("Needs Do Not Disturb access, which is granted in system settings.")
        }
        if (all.any { it.action == ActionType.SHELL } && !ShizukuShell.isReady) {
            warnings.add("Needs an active Shizuku connection for shell steps.")
        }
        if (all.any { it.action == ActionType.CALL_CONTACT }) {
            if (!Contacts.canRead(context)) {
                warnings.add("Needs contact access to turn a name into a number.")
            }
            if (!canPlaceCalls(context)) {
                warnings.add("Phone permission is off — the dialer will open instead of calling.")
            }
        }
        if (all.any { it.action == ActionType.REMINDER } && !NotificationRepository.hasAccess(context)) {
            // Reminders do not need notification *listener* access; only the ability to post.
            warnings.add("Reminders post a notification — allow notifications for JARVIS.")
        }
        if (all.any { it.action == ActionType.CAPTURE_PHOTO } && !QuickCapture.hasPermission(context)) {
            warnings.add("Needs camera permission to take the photo.")
        }
        if (all.any { it.action == ActionType.TYPE_TEXT } &&
            !AccessibilityStatus.isServiceEnabled(context)
        ) {
            warnings.add("Needs accessibility access to type into another app.")
        }
        val unconfigured = all.count { !it.isConfigured }
        if (unconfigured > 0) warnings.add("$unconfigured step(s) have no value set.")
        if (workflow.steps.isEmpty()) warnings.add("This routine has no steps yet.")
        if (workflow.totalSteps > AutomationEngine.MAX_TOTAL_STEPS) {
            warnings.add("This routine exceeds the ${AutomationEngine.MAX_TOTAL_STEPS}-step budget.")
        }
        return warnings
    }

    /** True when this step needs a touch of the system UI, so the runner can wait for it. */
    fun opensSystemSurface(step: WorkflowStep): Boolean = when (step.action) {
        ActionType.OPEN_APP, ActionType.OPEN_URL, ActionType.DEEP_LINK, ActionType.DIAL_NUMBER,
        ActionType.COMPOSE_SMS, ActionType.SET_TIMER, ActionType.SET_ALARM, ActionType.OPEN_PANEL,
        -> true

        else -> false
    }

    suspend fun execute(context: Context, step: WorkflowStep): Result = try {
        when (step.action) {
            // ------------------------------------------------------------------ apps + links
            ActionType.OPEN_APP -> openApp(context, step.param)

            ActionType.OPEN_URL -> {
                val url = normaliseUrl(step.param)
                if (url == null) {
                    Result.Failed("Not a valid address")
                } else {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Opened $url")
                }
            }

            ActionType.DEEP_LINK -> {
                val raw = step.param.trim()
                if (raw.isEmpty() || !raw.contains(":")) {
                    Result.Failed("A deep link needs a scheme, e.g. app://screen")
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Result.Ok("Opened deep link")
                    } catch (e: Exception) {
                        Result.Failed("No app handles that link")
                    }
                }
            }

            ActionType.DIAL_NUMBER -> {
                val number = step.param.trim()
                if (number.isEmpty()) {
                    Result.Failed("No number set")
                } else {
                    // ACTION_DIAL only opens the dialer with the number filled in. JARVIS never
                    // places a call: that would need CALL_PHONE, which this app does not hold.
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Dialer opened, not called")
                }
            }

            ActionType.CALL_CONTACT -> callContact(context, step.param)

            ActionType.CAPTURE_PHOTO -> {
                val (uri, error) = QuickCapture.capture(context)
                if (uri == null) Result.Failed(error ?: "No photo was taken") else Result.Ok("Photo saved")
            }

            ActionType.VERIFY -> when (val check = Verify.evaluate(context, step.param)) {
                is Verify.Result.Passed -> Result.Ok(check.detail)
                is Verify.Result.Failed -> Result.Failed(check.detail)
                is Verify.Result.Unavailable -> Result.Skipped(check.detail)
            }

            ActionType.DOWNLOAD -> {
                val parts = step.param.split('|')
                val url = parts.getOrNull(0)?.trim().orEmpty()
                val name = parts.getOrNull(1)?.trim().orEmpty()
                val outcome = Downloads.fetch(context, url, name)
                if (outcome.uri == null) {
                    Result.Failed(outcome.error ?: "The download failed")
                } else {
                    Result.Ok("Saved ${outcome.name} to Download/JARVIS")
                }
            }

            ActionType.TRANSLATE -> {
                val parts = step.param.split('|')
                val language = parts.getOrNull(0)?.trim().orEmpty().ifBlank { "English" }
                val text = parts.drop(1).joinToString("|").trim()
                val outcome = Translator.translate(context, text, language)
                if (outcome.text == null) {
                    Result.Failed(outcome.error ?: "Translation failed")
                } else {
                    Result.Ok(outcome.text)
                }
            }

            ActionType.SCREENSHOT -> {
                val (uri, error) = JarvisAccessibilityService.screenshot(context)
                if (uri == null) Result.Failed(error ?: "No screenshot was taken") else Result.Ok("Screenshot saved")
            }

            ActionType.REMINDER -> {
                val parts = step.param.split('|')
                val seconds = parts.getOrNull(0)?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                val clock = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                val text = parts.drop(2).joinToString("|").trim()
                val outcome = Reminders.schedule(context, seconds, clock, text)
                if (outcome.ok) Result.Ok(outcome.message) else Result.Failed(outcome.message)
            }

            ActionType.OPEN_LAST_PHOTO -> {
                val error = Gallery.openLatest(context)
                if (error == null) Result.Ok("Opened the newest photo") else Result.Failed(error)
            }

            ActionType.COMPOSE_SMS -> {
                val number = step.param.trim()
                if (number.isEmpty()) {
                    Result.Failed("No number set")
                } else {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Composer opened, not sent")
                }
            }

            // ------------------------------------------------------------------ device
            ActionType.SET_TIMER -> {
                val seconds = step.param.toIntOrNull() ?: 0
                if (seconds <= 0) {
                    Result.Failed("Timer length must be positive")
                } else {
                    context.startActivity(
                        Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Result.Ok("Timer set for ${seconds / 60}m ${seconds % 60}s")
                }
            }

            ActionType.SET_ALARM -> setAlarm(context, step.param)

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
                val percent = step.param.toIntOrNull()
                if (percent == null) {
                    Result.Failed("Not a number")
                } else {
                    val applied = QuickControls.setMediaVolume(context, percent)
                    Result.Ok("Media volume $applied%")
                }
            }

            ActionType.PLAY_MEDIA -> setPlayback(context, play = true)
            ActionType.PAUSE_MEDIA -> setPlayback(context, play = false)

            ActionType.NEXT_MEDIA -> {
                if (!NotificationRepository.hasAccess(context)) {
                    Result.Skipped("Notification access is off")
                } else if (QuickControls.skipNext(context)) {
                    Result.Ok("Next track")
                } else {
                    Result.Failed("No active media session")
                }
            }

            ActionType.PREVIOUS_MEDIA -> {
                if (!NotificationRepository.hasAccess(context)) {
                    Result.Skipped("Notification access is off")
                } else if (QuickControls.skipPrevious(context)) {
                    Result.Ok("Previous track")
                } else {
                    Result.Failed("No active media session")
                }
            }

            ActionType.DISMISS_NOTIFICATIONS -> {
                if (!NotificationRepository.hasAccess(context)) {
                    Result.Skipped("Notification access is off")
                } else {
                    val cleared = NotificationRepository.dismissAllClearable()
                    Result.Ok("Cleared $cleared alert(s)")
                }
            }

            ActionType.NOTIFY -> notify(context, step.param)

            ActionType.OPEN_PANEL -> openPanel(context, step.param)

            ActionType.SET_DND -> setDnd(context, step.param)

            ActionType.SHELL -> runShell(context, step.param)

            // ------------------------------------------------------------------ speech + network
            ActionType.SAY -> {
                val reason = SpeechOut.speak(context, step.param)
                if (reason == null) Result.Ok("Spoke ${step.param.take(30)}") else Result.Failed(reason)
            }

            ActionType.WEBHOOK -> webhook(step.param)

            // ------------------------------------------------------------------ accessibility
            ActionType.GLOBAL_ACTION -> performGlobal(context, step.param)

            ActionType.PRESS_TEXT -> {
                if (!AccessibilityStatus.isServiceEnabled(context)) {
                    Result.Skipped("Accessibility access is off")
                } else if (!AccessibilityStatus.isScreenPressAllowed(context)) {
                    Result.Skipped("Screen pressing is not allowed")
                } else {
                    val label = step.param.trim()
                    if (label.isEmpty()) {
                        Result.Failed("No label set")
                    } else if (JarvisAccessibilityService.pressByText(label)) {
                        Result.Ok("Pressed \"$label\"")
                    } else {
                        Result.Failed("No control labelled \"$label\" on screen")
                    }
                }
            }

            ActionType.TYPE_TEXT -> typeText(context, step.param)

            // ------------------------------------------------------------------ timing
            ActionType.WAIT -> {
                val seconds = (step.param.toIntOrNull() ?: 0).coerceIn(0, 120)
                delay(seconds * 1000L)
                Result.Ok("Waited ${seconds}s")
            }

            // Control flow never reaches the executor.
            ActionType.IF_BLOCK, ActionType.REPEAT_BLOCK, ActionType.WAIT_UNTIL,
            ActionType.STOP_ROUTINE,
            -> Result.Skipped("Handled by the runner")
        }
    } catch (t: Throwable) {
        Result.Failed(t.message ?: t.javaClass.simpleName)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun openApp(context: Context, param: String): Result {
        val pkg = param.substringBefore('/')
        val activity = param.substringAfter('/', "")
        if (pkg.isBlank()) return Result.Failed("No app chosen")
        val intent = try {
            context.packageManager.getLaunchIntentForPackage(pkg)
        } catch (t: Throwable) {
            null
        } ?: return Result.Failed("$pkg is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (activity.isNotBlank()) {
            intent.component = android.content.ComponentName(pkg, activity)
        }
        return try {
            context.startActivity(intent)
            Result.Ok("Opened ${appLabel(context, pkg)}")
        } catch (t: Throwable) {
            Result.Failed("Could not open ${appLabel(context, pkg)}")
        }
    }

    private fun setPlayback(context: Context, play: Boolean): Result {
        if (!NotificationRepository.hasAccess(context)) {
            return Result.Skipped("Notification access is off")
        }
        val applied = QuickControls.setPlayback(context, play)
            ?: return Result.Failed("No active media session")
        return Result.Ok(if (applied) "Playing" else "Paused")
    }

    private fun setAlarm(context: Context, raw: String): Result {
        val time = ConditionEvaluator.parseTime(raw)
            ?: return Result.Failed("Time must look like 07:30")
        return try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result.Ok(
                "Alarm set for " + com.jarvis.assistant.voice.VoiceParsing.clock12(
                    String.format(Locale.US, "%02d:%02d", time.hour, time.minute),
                ),
            )
        } catch (t: Throwable) {
            Result.Failed("No alarm app available")
        }
    }

    private fun notify(context: Context, text: String): Result {
        val message = text.trim()
        if (message.isEmpty()) return Result.Failed("No message set")
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return Result.Failed("Notification service unavailable")
            val channel = NotificationChannel(
                NOTIFY_CHANNEL,
                "JARVIS routines",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Messages posted by your own routines" }
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(context, NOTIFY_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("JARVIS")
                .setContentText(message.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(600)))
                .setAutoCancel(true)
                .build()
            manager.notify(System.currentTimeMillis().toInt(), notification)
            Result.Ok("Notified")
        } catch (t: Throwable) {
            Result.Failed("Could not post a notification (permission?)")
        }
    }

    /**
     * Calls a URL the operator typed. GET unless the value starts with `POST `.
     * The full method/URL/status is reported so a silent failure cannot hide.
     */
    private fun webhook(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Failed("No URL set")

        val isPost = trimmed.startsWith("POST ", ignoreCase = true)
        val target = normaliseUrl(if (isPost) trimmed.substring(5).trim() else trimmed)
            ?: return Result.Failed("Not a valid address")

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = if (isPost) "POST" else "GET"
                connectTimeout = WEBHOOK_CONNECT_TIMEOUT_MS
                readTimeout = WEBHOOK_READ_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                if (isPost) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
            }
            if (isPost) {
                connection.outputStream.use { it.write("jarvis".toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code in 200..299) {
                Result.Ok("HTTP $code from ${Uri.parse(target).host}")
            } else {
                Result.Failed("HTTP $code from ${Uri.parse(target).host}")
            }
        } catch (e: IOException) {
            Result.Failed("Webhook failed: ${e.javaClass.simpleName}")
        } catch (t: Throwable) {
            Result.Failed("Webhook failed: ${t.javaClass.simpleName}")
        } finally {
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                // Nothing to clean up.
            }
        }
    }

    private fun openPanel(context: Context, raw: String): Result {
        val key = raw.trim().lowercase(Locale.US)
        if (key.isEmpty()) return Result.Failed("No panel chosen")
        return when {
            key.startsWith("wifi") || key.startsWith("internet") -> {
                QuickControls.openInternetPanel(context); Result.Ok("Opened internet panel")
            }

            key.startsWith("bt") || key.startsWith("bluetooth") -> {
                QuickControls.openBluetoothSettings(context); Result.Ok("Opened Bluetooth settings")
            }

            key.startsWith("dnd") || key.startsWith("silent") || key.startsWith("do not") -> {
                QuickControls.openDndSettings(context); Result.Ok("Opened Do Not Disturb")
            }

            key.startsWith("display") || key.startsWith("screen") -> {
                QuickControls.openDisplaySettings(context); Result.Ok("Opened display settings")
            }

            key.startsWith("volume") || key.startsWith("audio") -> {
                QuickControls.openVolumePanel(context); Result.Ok("Opened volume panel")
            }

            key.startsWith("battery") -> {
                QuickControls.openBatterySaverSettings(context); Result.Ok("Opened battery saver")
            }

            key.startsWith("notif") -> {
                QuickControls.openNotificationAccessSettings(context)
                Result.Ok("Opened notification access")
            }

            else -> Result.Failed("Unknown panel \"$key\" (try wifi, bluetooth, dnd, display, volume, battery)")
        }
    }

    private fun setDnd(context: Context, raw: String): Result {
        val key = raw.trim().lowercase(Locale.US)
        val current = DndControl.current(context)
        val mode = when {
            key.isEmpty() || key == "toggle" ->
                if (current == null || current == DndControl.Mode.OFF) DndControl.Mode.SILENT
                else DndControl.Mode.OFF

            key == "on" -> DndControl.Mode.SILENT
            key == "off" -> DndControl.Mode.OFF
            else -> DndControl.Mode.fromText(key)
        } ?: return Result.Failed("Unknown mode \"$raw\" (try off, priority, alarms, silent)")

        // null means the special access has not been granted — say that, do not pretend.
        val applied = DndControl.set(context, mode)
            ?: return Result.Skipped("Do Not Disturb access is not granted")
        return if (applied) {
            Result.Ok("Do Not Disturb: ${mode.label}")
        } else {
            Result.Failed("Android refused the change")
        }
    }

    private suspend fun runShell(context: Context, raw: String): Result {
        val command = raw.trim()
        if (command.isEmpty()) return Result.Failed("No command given")
        return when (val outcome = ShizukuShell.exec(context, command)) {
            is ShizukuShell.Outcome.Failed -> Result.Skipped(outcome.reason)
            is ShizukuShell.Outcome.Ok -> {
                val tail = outcome.output.lineSequence().firstOrNull().orEmpty().take(120)
                if (outcome.exitCode == 0) {
                    Result.Ok(if (tail.isBlank()) "Ran · no output" else "Ran · $tail")
                } else {
                    Result.Failed("exit ${outcome.exitCode}" + if (tail.isBlank()) "" else " · $tail")
                }
            }
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
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
        return try {
            val uri = Uri.parse(withScheme)
            // Only web schemes: a webhook must not be able to reach file:// or intent://.
            if (uri.host.isNullOrBlank()) null else withScheme
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Calls a saved contact.
     *
     * The name is resolved against the address book or a remembered alias. With CALL_PHONE the call
     * is placed; without it the dialer opens with the number filled in and the card says so — the
     * one thing this must never do is report a call that did not happen.
     */
    private suspend fun callContact(context: Context, raw: String): Result {
        val query = raw.trim()
        if (query.isEmpty()) return Result.Failed("No contact named")
        if (!Contacts.canRead(context) && Contacts.aliasName(context, query) == null) {
            return Result.Failed("Contact access is off — allow it in Settings")
        }
        val match = Contacts.resolve(context, query)
            ?: return Result.Failed("No contact matched \"$query\"")
        val uri = Uri.parse("tel:" + Uri.encode(match.number))
        return try {
            if (canPlaceCalls(context)) {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Result.Ok("Calling ${match.name}")
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Result.Ok("Dialer open for ${match.name} — allow phone permission to call directly")
            }
        } catch (t: Throwable) {
            Result.Failed("Could not start the call")
        }
    }

    /** Types into whatever field currently has focus, through the accessibility service. */
    private fun typeText(context: Context, text: String): Result {
        if (text.isBlank()) return Result.Failed("Nothing to type")
        if (!AccessibilityStatus.isServiceEnabled(context)) {
            return Result.Failed("Accessibility is off — enable JARVIS in system settings")
        }
        return if (JarvisAccessibilityService.typeInto(text)) {
            Result.Ok("Typed ${text.take(28)}")
        } else {
            Result.Failed("No text field had focus")
        }
    }

    private fun canPlaceCalls(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.CALL_PHONE,
    ) == PackageManager.PERMISSION_GRANTED

    private fun appLabel(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (t: Throwable) {
        pkg
    }
}
