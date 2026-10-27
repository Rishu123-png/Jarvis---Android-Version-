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
import com.jarvis.assistant.device.Access
import com.jarvis.assistant.device.AppLaunch
import com.jarvis.assistant.device.CalendarBridge
import com.jarvis.assistant.device.ContactsWrite
import com.jarvis.assistant.device.Downloads
import com.jarvis.assistant.device.Files
import com.jarvis.assistant.device.Gallery
import com.jarvis.assistant.device.HealthLog
import com.jarvis.assistant.device.Messaging
import com.jarvis.assistant.device.NotifyBrain
import com.jarvis.assistant.device.VoiceNotes
import com.jarvis.assistant.device.Whereabouts
import com.jarvis.assistant.device.Reminders
import com.jarvis.assistant.device.QuickCapture
import com.jarvis.assistant.shell.ShizukuShell
import com.jarvis.assistant.data.Units
import com.jarvis.assistant.voice.Contacts
import com.jarvis.assistant.voice.Language
import com.jarvis.assistant.voice.SpokenLanguage
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
        if (all.any { it.action == ActionType.REMINDER } &&
            !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            // Posting permission, not listener access: the listener is for *reading* other apps.
            warnings.add("Reminders post a notification — allow notifications for JARVIS in system settings.")
        }
        if (all.any { it.action == ActionType.CAPTURE_PHOTO } && !QuickCapture.hasPermission(context)) {
            warnings.add("Needs camera permission to take the photo.")
        }
        if (all.any { it.action == ActionType.TYPE_TEXT } &&
            !AccessibilityStatus.isServiceEnabled(context)
        ) {
            warnings.add("Needs accessibility access to type into another app.")
        }
        if (all.any { it.action == ActionType.SEARCH_FILES || it.action == ActionType.OPEN_FILE } &&
            !Files.hasAllFiles(context)
        ) {
            warnings.add("File search needs \"All files access\" for the whole phone; without it only indexed media is searched.")
        }
        if (all.any {
                it.action == ActionType.LOCATION || it.action == ActionType.DISTANCE ||
                    it.action == ActionType.NAVIGATE
            } && !Whereabouts.hasPermission(context)
        ) {
            warnings.add("Needs location permission for distance and directions.")
        }
        if (all.any { it.action == ActionType.HEALTH || it.action == ActionType.WATER_LOG } &&
            !HealthLog.hasActivityPermission(context)
        ) {
            warnings.add("Step counting needs the Activity permission.")
        }
        if (all.any { it.action == ActionType.MESSAGE } && !Contacts.canRead(context)) {
            warnings.add("Needs contact access to find the person to message.")
        }
        if (all.any { it.action == ActionType.MESSAGE_REPLY || it.action == ActionType.READ_NOTIFICATIONS } &&
            !NotificationRepository.hasAccess(context)
        ) {
            warnings.add("Needs notification access to read or reply to alerts.")
        }
        if (all.any { it.action == ActionType.CALENDAR_READ || it.action == ActionType.CALENDAR_ADD } &&
            !CalendarBridge.canRead(context)
        ) {
            warnings.add("Needs calendar access to read or create events.")
        }
        if (all.any { it.action == ActionType.CONTACT_ADD } && !ContactsWrite.canWrite(context)) {
            warnings.add("Needs contact editing access to save a person.")
        }
        if (all.any { it.action == ActionType.RECORD_NOTE } && !VoiceNotes.canRecord(context)) {
            warnings.add("Needs microphone access to record a note.")
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
        ActionType.OPEN_GALLERY, ActionType.NAVIGATE, ActionType.SETTINGS_PAGE, ActionType.SHARE_TEXT,
        ActionType.OPEN_FILE, ActionType.MESSAGE, ActionType.CALENDAR_ADD, ActionType.CONTACT_ADD,
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

            // ------------------------------------------------------------------ the whole phone
            ActionType.OPEN_GALLERY -> openTarget(context, "gallery")

            ActionType.SETTINGS_PAGE -> openTarget(context, step.param.ifBlank { "settings" })

            ActionType.MESSAGE -> {
                val parts = step.param.split('|')
                val app = parts.getOrNull(0)?.trim().orEmpty().ifBlank { "whatsapp" }
                val who = parts.getOrNull(1)?.trim().orEmpty()
                val body = parts.getOrNull(2)?.trim().orEmpty()
                if (who.isBlank()) {
                    Result.Failed("No contact set")
                } else {
                    val outcome = Messaging.send(context, who, body, app)
                    if (outcome.ok) Result.Ok(outcome.detail) else Result.Failed(outcome.detail)
                }
            }

            ActionType.MESSAGE_REPLY -> {
                val parts = step.param.split('|')
                val who = parts.getOrNull(0)?.trim().orEmpty()
                val body = parts.getOrNull(1)?.trim().orEmpty()
                val target = NotifyBrain.findReplyTarget(who)
                when {
                    who.isBlank() -> Result.Failed("No contact set")
                    target == null -> Result.Failed("No live alert from $who to reply to")
                    NotifyBrain.sendReply(context, target, body) -> Result.Ok("Replied to $who")
                    else -> Result.Failed("The app refused the reply")
                }
            }

            ActionType.READ_NOTIFICATIONS -> readNotifications(context, step.param)

            ActionType.SEARCH_FILES -> searchFiles(context, step.param, openWhenSingle = false)

            ActionType.OPEN_FILE -> searchFiles(context, step.param, openWhenSingle = true)

            ActionType.LOCATION -> {
                val lang = SpokenLanguage.last(context)
                val fix = Whereabouts.currentFix(context)
                when {
                    !Whereabouts.hasPermission(context) ->
                        speakAndReport(context, Language.say(lang, "location_permission"))

                    fix == null -> Result.Failed("No location fix — is location turned on?")

                    else -> {
                        val place = Whereabouts.describe(context, fix)
                        speakAndReport(
                            context,
                            place ?: String.format(
                                Locale.US,
                                "%.4f, %.4f",
                                fix.latitude,
                                fix.longitude,
                            ),
                        )
                    }
                }
            }

            ActionType.DISTANCE -> distanceTo(context, step.param)

            ActionType.NAVIGATE -> {
                val place = step.param.trim()
                val lang = SpokenLanguage.last(context)
                if (place.isBlank()) {
                    Result.Failed("No destination set")
                } else {
                    val coordinates = Whereabouts.geocode(context, place)
                    when {
                        coordinates == null -> speakAndReport(
                            context,
                            Language.say(lang, "distance_unknown", place),
                        )

                        else -> {
                            val problem = Whereabouts.navigate(context, place, coordinates.first, coordinates.second)
                            if (problem == null) Result.Ok("Navigating to $place") else Result.Failed(problem)
                        }
                    }
                }
            }

            ActionType.CALCULATE -> {
                val lang = SpokenLanguage.last(context)
                val outcome = Units.tryCalculate(step.param)
                if (outcome == null) {
                    Result.Failed("That is not something I can work out")
                } else {
                    speakAndReport(context, Language.say(lang, "calc", outcome.text))
                }
            }

            ActionType.READ_SCREEN -> {
                if (!AccessibilityStatus.isServiceEnabled(context)) {
                    Result.Skipped("Accessibility access is off")
                } else {
                    val labels = JarvisAccessibilityService.visibleLabels(limit = 24)
                    if (labels.isEmpty()) {
                        Result.Failed("Nothing readable on this screen")
                    } else {
                        speakAndReport(context, labels.joinToString(", ").take(400))
                    }
                }
            }

            ActionType.SHARE_TEXT -> {
                val text = step.param.trim()
                if (text.isBlank()) {
                    Result.Failed("Nothing to share")
                } else {
                    try {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Share with",
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        Result.Ok("Share sheet open")
                    } catch (t: Throwable) {
                        Result.Failed("No app can share that")
                    }
                }
            }

            ActionType.HEALTH -> healthReport(context, step.param)

            ActionType.WATER_LOG -> {
                val lang = SpokenLanguage.last(context)
                val millilitres = step.param.trim().toIntOrNull() ?: 250
                val total = HealthLog.addWater(context, millilitres)
                speakAndReport(context, Language.say(lang, "water", total))
            }

            ActionType.CALENDAR_READ -> readCalendar(context, step.param)

            ActionType.CALENDAR_ADD -> addCalendar(context, step.param)

            ActionType.CONTACT_ADD -> {
                val parts = step.param.split('|')
                val name = parts.getOrNull(0)?.trim().orEmpty()
                val number = parts.getOrNull(1)?.trim().orEmpty()
                val outcome = ContactsWrite.save(context, name, number)
                if (outcome.ok) Result.Ok(outcome.detail) else Result.Failed(outcome.detail)
            }

            ActionType.RECORD_NOTE -> {
                val seconds = step.param.trim().toIntOrNull() ?: VoiceNotes.DEFAULT_SECONDS
                val outcome = VoiceNotes.record(context, seconds)
                if (outcome.ok) Result.Ok(outcome.detail) else Result.Failed(outcome.detail)
            }

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

    /**
     * Opens a package (`com.example/com.example.Main`) **or** a name ("gallery", "files", "wifi").
     *
     * The second form exists because the operator says "open gallery", not a package name, and
     * [AppLaunch] knows the system intents and the skin-specific gallery packages. Stored routines
     * keep their package strings and behave exactly as before.
     */
    private fun openApp(context: Context, param: String): Result {
        val raw = param.trim()
        if (raw.isBlank()) return Result.Failed("No app chosen")

        val looksLikePackage = raw.contains('.') && !raw.contains(' ')
        if (!raw.contains('/') && !looksLikePackage) {
            return when (val outcome = AppLaunch.open(context, raw)) {
                is AppLaunch.Outcome.Opened -> Result.Ok("Opened ${outcome.label}")
                is AppLaunch.Outcome.Failed -> Result.Failed(outcome.reason)
                is AppLaunch.Outcome.Unknown ->
                    if (outcome.suggestions.isEmpty()) {
                        Result.Failed("Nothing on this phone is called $raw")
                    } else {
                        Result.Ok("Closest: " + outcome.suggestions.joinToString(", "))
                    }
            }
        }

        val pkg = raw.substringBefore('/')
        val activity = raw.substringAfter('/', "")
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


    // ---------------------------------------------------------------------------------------------
    // Whole-phone helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens anything JARVIS can name, and reports what actually happened.
     *
     * When nothing matches, the closest real names are returned so the caller can offer a choice
     * instead of a dead "not found".
     */
    private fun openTarget(context: Context, target: String): Result =
        when (val outcome = AppLaunch.open(context, target)) {
            is AppLaunch.Outcome.Opened -> Result.Ok("Opened ${outcome.label}")
            is AppLaunch.Outcome.Failed -> Result.Failed(outcome.reason)
            is AppLaunch.Outcome.Unknown -> {
                if (outcome.suggestions.isEmpty()) {
                    Result.Failed("Nothing on this phone is called $target")
                } else {
                    Result.Ok("Closest matches: " + outcome.suggestions.joinToString(", "))
                }
            }
        }

    /** Marks the human reply as the outcome, and speaks it. Used by the "answer" actions. */
    private suspend fun speakAndReport(context: Context, text: String): Result {
        try {
            SpeechOut.speak(context, text, timeoutMillis = 15_000L)
        } catch (t: Throwable) {
            // Speaking is best-effort; the card still shows the answer.
        }
        return Result.Ok(text)
    }

    private suspend fun readNotifications(context: Context, raw: String): Result {
        val lang = SpokenLanguage.last(context)
        val mode = raw.trim().lowercase(Locale.US)
        val items = NotifyBrain.lastSummary(context, limit = if (mode == "last") 1 else 5)
        if (items.isEmpty()) {
            return speakAndReport(context, Language.say(lang, "messages_none"))
        }

        val lines = items.map { item ->
            val verdict = NotifyBrain.classify(
                item.packageName,
                item.appLabel,
                item.title,
                item.text,
                hasReplyAction = false,
            )
            NotifyBrain.announceLine(verdict, item.appLabel, item.title, item.text, lang)
        }
        return speakAndReport(context, lines.joinToString(" "))
    }

    /**
     * Searches for a file and, when there is exactly one good match, opens it.
     *
     * More than one match is deliberately *not* guessed: opening the wrong Aadhaar PDF or the wrong
     * photo is worse than showing a list.
     */
    private suspend fun searchFiles(context: Context, raw: String, openWhenSingle: Boolean): Result {
        val lang = SpokenLanguage.last(context)
        val query = raw.trim()
        if (query.isBlank()) return Result.Failed("No file name set")

        val found = Files.search(context, query)
        if (found.isEmpty()) {
            return if (Files.hasAllFiles(context) || Access.hasAll(context, Access.MEDIA_PERMISSIONS)) {
                speakAndReport(context, Language.say(lang, "file_none", query))
            } else {
                speakAndReport(context, Language.say(lang, "file_permission"))
            }
        }

        val first = found.first()
        if (found.size == 1 || openWhenSingle) {
            val problem = Files.open(context, first.uri, first.name)
            return if (problem == null) {
                speakAndReport(context, Language.say(lang, "file_found", 1, first.name))
            } else {
                Result.Failed(problem)
            }
        }

        val listing = found.joinToString(" · ") { it.name }
        return Result.Ok("$listing")
    }

    private suspend fun distanceTo(context: Context, raw: String): Result {
        val lang = SpokenLanguage.last(context)
        val place = raw.trim()
        if (place.isBlank()) return Result.Failed("No destination set")
        if (!Whereabouts.hasPermission(context)) {
            return speakAndReport(context, Language.say(lang, "location_permission"))
        }

        val fix = Whereabouts.currentFix(context)
            ?: return Result.Failed("No location fix — is location turned on?")

        val destination = parseCoordinates(place) ?: Whereabouts.geocode(context, place)
        if (destination == null) {
            return speakAndReport(context, Language.say(lang, "distance_unknown", place))
        }

        val road = if (Whereabouts.roadRoutingEnabled(context)) {
            Whereabouts.roadKm(fix, destination)
        } else {
            null
        }
        val km = road ?: Whereabouts.haversineKm(
            fix.latitude,
            fix.longitude,
            destination.first,
            destination.second,
        )
        val spoken = if (road != null) {
            Language.say(lang, "distance_road", place, Whereabouts.formatKm(km))
        } else {
            Language.say(lang, "distance_line", place, Whereabouts.formatKm(km))
        }
        return speakAndReport(context, spoken)
    }

    private fun parseCoordinates(raw: String): Pair<Double, Double>? {
        val match = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d+(?:\\.\\d+)?)$").find(raw.trim())
            ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private suspend fun healthReport(context: Context, raw: String): Result {
        val lang = SpokenLanguage.last(context)
        val what = raw.trim().lowercase(Locale.US).ifBlank { "steps" }

        return when (what) {
            "water" -> speakAndReport(context, Language.say(lang, "water", HealthLog.waterToday(context)))

            "heart" -> {
                if (!HealthLog.hasHeartSensor(context)) {
                    speakAndReport(context, Language.say(lang, "heart_none"))
                } else {
                    val bpm = HealthLog.heartRate(context)
                    if (bpm == null) {
                        Result.Failed("The heart rate sensor did not report")
                    } else {
                        speakAndReport(context, Language.say(lang, "heart", bpm))
                    }
                }
            }

            "sleep" -> {
                val minutes = HealthLog.sleepLastNight(context)
                if (minutes == null) {
                    Result.Failed("No night recorded yet")
                } else {
                    val hours = String.format(Locale.US, "%.1f hours", minutes / 60.0)
                    speakAndReport(context, Language.say(lang, "sleep", hours))
                }
            }

            "distance" -> {
                val steps = HealthLog.stepsToday(context)
                if (steps == null) {
                    speakAndReport(context, Language.say(lang, "steps_none"))
                } else {
                    val km = HealthLog.distanceKm(steps)
                    speakAndReport(
                        context,
                        String.format(Locale.US, "%.2f kilometres walked, about %d steps.", km, steps),
                    )
                }
            }

            else -> {
                val steps = HealthLog.stepsToday(context)
                if (steps == null) {
                    speakAndReport(context, Language.say(lang, "steps_none"))
                } else {
                    speakAndReport(context, Language.say(lang, "steps", steps))
                }
            }
        }
    }


    private suspend fun readCalendar(context: Context, raw: String): Result {
        val mode = raw.trim().lowercase(Locale.US).ifBlank { "today" }

        if (!CalendarBridge.canRead(context)) {
            return Result.Failed("Calendar access is off — allow it in Settings → Access.")
        }

        val events = when (mode) {
            "next" -> CalendarBridge.next(context)?.let { listOf(it) }
            "week", "upcoming", "next week" -> CalendarBridge.upcoming(context, days = 7, limit = 5)
            else -> CalendarBridge.today(context)
        }.orEmpty()

        if (events.isEmpty()) {
            return speakAndReport(context, "Nothing in the calendar.")
        }

        val spoken = if (mode == "next" && events.size == 1) {
            val event = events.first()
            "Next is ${event.title} at ${CalendarBridge.clockOf(event.startMillis)}."
        } else {
            events.joinToString("; ") { event ->
                "${event.title} at ${CalendarBridge.clockOf(event.startMillis)}"
            }
        }
        // Titles are the operator's own words and are not translated; the surrounding sentence is
        // kept simple enough that it reads correctly in every language JARVIS speaks.
        return speakAndReport(context, spoken)
    }

    private suspend fun addCalendar(context: Context, raw: String): Result {
        val parts = raw.split('|')
        val whenRaw = parts.getOrNull(0)?.trim().orEmpty()
        val title = parts.getOrNull(1)?.trim().orEmpty().ifBlank { "Event" }

        if (!CalendarBridge.canWrite(context)) {
            return Result.Failed("Calendar access is off — allow it in Settings → Access.")
        }

        val at = try {
            java.time.LocalDateTime.parse(whenRaw)
        } catch (t: Throwable) {
            null
        } ?: return Result.Failed("I did not get the time for that event.")

        val start = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = CalendarBridge.add(context, title, start)
            ?: return Result.Failed("The calendar app would not take the event.")

        return Result.Ok(
            "Added $title " + com.jarvis.assistant.data.When.spoken(at).lowercase(Locale.US) + ".",
        )
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
