package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.automation.AutomationEngine
import com.jarvis.assistant.automation.AutomationStore
import com.jarvis.assistant.device.DndControl
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.QuickControls
import com.jarvis.assistant.device.readBattery
import com.jarvis.assistant.device.readNetwork
import com.jarvis.assistant.device.readStorage
import com.jarvis.assistant.shell.ShizukuShell
import com.jarvis.assistant.social.InstagramAnalytics
import com.jarvis.assistant.social.InstagramStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Local command line for the HUD terminal.
 *
 * Every command here does real work against a real Android API — this replaced an earlier table
 * that only printed text. Nothing is simulated: `battery` reads the battery, `torch on` turns the
 * flashlight on, `run focus` starts a real routine.
 *
 * Commands that are not recognised return [Response.unknown] so the terminal can hand the input to
 * the AI core instead of guessing.
 */
object CommandRegistry {

    data class Response(
        val output: List<String>,
        val unknown: Boolean = false,
    )

    /** Bound so a long session cannot grow the terminal list without limit. */
    const val MAX_HISTORY_LINES = 400

    private val COMMANDS = listOf(
        "help", "status", "battery", "network", "storage", "apps",
        "alerts", "torch", "volume", "panel", "dnd", "routines", "run", "stop",
        "modules", "ai", "insta", "time", "clear", "shizuku", "shell",
    )

    fun banner(aiConfigured: Boolean, aiLabel: String?): List<String> = buildList {
        add("J.A.R.V.I.S. TERMINAL")
        add(
            if (aiConfigured && aiLabel != null) {
                "intelligence :: $aiLabel :: linked"
            } else {
                "intelligence :: not configured (settings > intelligence)"
            }
        )
        add("type 'help' · local commands run on this device")
        add("anything else is sent to your AI key, if configured")
    }

    suspend fun respond(context: Context, raw: String): Response {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Response(emptyList())

        val tokens = trimmed.split(Regex("\\s+"))
        val head = tokens.first().lowercase(Locale.US)
        val argument = tokens.drop(1).joinToString(" ").trim()
        val app = context.applicationContext

        return when (head) {
            "help" -> Response(helpLines())
            "status" -> Response(statusLines(app))
            "battery" -> Response(batteryLines(app))
            "network" -> Response(networkLines(app))
            "storage" -> Response(storageLines(app))
            "apps" -> Response(appsLines(app))
            "alerts" -> Response(alertsLines(app, argument))
            "torch" -> Response(torchLines(app, argument))
            "volume" -> Response(volumeLines(app, argument))
            "panel" -> Response(panelLines(app, argument))
            "dnd" -> Response(dndLines(app, argument))
            "shizuku" -> Response(shizukuLines(app))
            "shell" -> Response(shellLines(app, argument))
            "routines" -> Response(routineLines(app))
            "run" -> Response(runLines(app, argument))
            "stop" -> Response(stopLines())
            "modules" -> Response(moduleLines())
            "ai" -> Response(aiLines(app))
            "insta", "instagram" -> Response(instaLines(app))
            "time" -> Response(
                listOf(
                    "LOCAL TIME",
                    "  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    "  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")),
                )
            )
            else -> Response(
                output = listOf(
                    "UNKNOWN COMMAND :: $head",
                    "type 'help' for local commands.",
                ),
                unknown = true,
            )
        }
    }

    /** Handed to the model so it knows what the console can actually do. */
    fun aiSystemPrompt(context: Context): String {
        val battery = readBattery(context)
        val network = readNetwork(context)
        val storage = readStorage(context)
        val routines = AutomationStore.load(context).joinToString(", ") { it.name }

        return """
            You are JARVIS, an on-device assistant inside an Android HUD app.
            You are talking to the phone's owner through a small terminal panel on their screen.

            Style: plain text only. No markdown, no asterisks, no code fences, no emoji.
            Keep replies under 80 words unless the operator asks you to explain something.
            Short lines read better than paragraphs on this panel.

            Device right now: battery ${battery.percent}%, network ${network.transport},
            storage ${storage.freeLabel} free of ${storage.totalLabel}.
            Saved routines: ${routines.ifBlank { "none" }}.

            Local commands the operator can run: ${COMMANDS.joinToString(", ")}.
            Use the real syntax: torch on, volume 40, panel wifi, run <routine>, stop, battery.

            You cannot read the screen, send messages, or place calls.
            Shell commands only work if Shizuku is connected; never assume it is.
            Say so plainly if asked rather than pretending.
            If exactly one local command would help, end with one line formatted as:
            RUN: <command>
            Otherwise omit that line.
        """.trimIndent()
    }

    // -----------------------------------------------------------------------------------------
    // Command bodies
    // -----------------------------------------------------------------------------------------

    private fun helpLines(): List<String> = buildList {
        add("LOCAL COMMANDS")
        add("  status              core summary")
        add("  battery             live cell state")
        add("  network             link state")
        add("  storage             free space")
        add("  apps                installed launcher apps")
        add("  alerts              notification count")
        add("  alerts clear        dismiss clearable alerts")
        add("  torch [on|off]      flashlight")
        add("  volume [0-100|up|down]")
        add("  panel wifi|bt|dnd|display|volume|notification|battery")
        add("  dnd [status|on|off|priority|alarms|silent]")
        add("  shizuku             privileged shell status")
        add("  shell <command>     run a command through Shizuku")
        add("  routines            list saved routines")
        add("  run <name>          start a routine")
        add("  stop                halt the running routine")
        add("  modules             HUD modules")
        add("  ai                  intelligence status")
        add("  insta               instagram creator summary")
        add("  time                clock")
        add("  clear               wipe this session")
        add("Unrecognised input goes to the AI core when a key is configured.")
    }

    private fun statusLines(context: Context): List<String> {
        val config = AiKeyStore.config(context)
        val running = AutomationEngine.isBusy
        return listOf(
            "CORE :: ONLINE",
            "MODULES :: ${HudModules.all.size} REGISTERED / ${HudModules.all.count { it.isLive }} LIVE",
            "INTELLIGENCE :: " + (config?.let { "${it.label} · ${it.resolvedModel}" } ?: "NOT CONFIGURED"),
            "AUTOMATION :: " + if (running) "RUNNING" else "IDLE",
            "NOTIFICATIONS :: " + if (NotificationRepository.hasAccess(context)) "LINKED" else "NO ACCESS",
        )
    }

    private fun batteryLines(context: Context): List<String> {
        val battery = readBattery(context)
        return listOf(
            "CELL DIAGNOSTICS",
            "  LEVEL      ${battery.percent}%",
            "  STATE      ${battery.statusLabel}",
            "  PLUG       ${battery.plugLabel}",
            "  TEMP       ${"%.1f".format(Locale.US, battery.temperatureC)}°C",
            "  VOLTAGE    ${battery.voltageMv} mV",
            "  HEALTH     ${battery.healthLabel}",
        )
    }

    private fun networkLines(context: Context): List<String> {
        val network = readNetwork(context)
        return listOf(
            "LINK DIAGNOSTICS",
            "  TRANSPORT  ${network.transport}",
            "  VALIDATED  ${if (network.validated) "YES" else "NO"}",
            "  DOWNSTREAM ${network.downstreamKbps} kbps",
        )
    }

    private fun storageLines(context: Context): List<String> {
        val storage = readStorage(context)
        return listOf(
            "STORAGE",
            "  USED   ${storage.usedLabel} of ${storage.totalLabel} (${storage.percentUsed}%)",
            "  FREE   ${storage.freeLabel}",
        )
    }

    private suspend fun appsLines(context: Context): List<String> {
        val apps = AppRepository.loadApps(context)
        val preview = apps.take(6).joinToString(", ") { it.label }
        return buildList {
            add("INSTALLED APPS :: ${apps.size}")
            if (preview.isNotBlank()) add("  $preview")
            if (apps.size > 6) add("  …and ${apps.size - 6} more")
            add("open the APPS module for the full grid")
        }
    }

    private fun alertsLines(context: Context, argument: String): List<String> {
        if (!NotificationRepository.hasAccess(context)) {
            return listOf(
                "ALERTS :: NO ACCESS",
                "grant notification access, then ask again",
            )
        }
        return when (argument.lowercase(Locale.US)) {
            "", "count", "list" -> {
                val items = NotificationRepository.notifications.value
                buildList {
                    add("ALERTS :: ${items.size} active")
                    items.take(5).forEach { item ->
                        add("  ${item.appLabel}: ${item.title.take(40)}")
                    }
                }
            }
            "clear", "dismiss", "all" -> {
                val cleared = NotificationRepository.dismissAllClearable()
                listOf("ALERTS :: cleared $cleared")
            }
            else -> listOf("usage: alerts [clear]")
        }
    }

    private fun torchLines(context: Context, argument: String): List<String> {
        val available = QuickControls.Torch.isAvailable(context)
        if (!available) return listOf("TORCH :: no flashlight on this device")

        return when (argument.lowercase(Locale.US)) {
            "on" -> {
                if (QuickControls.Torch.isOn) {
                    listOf("TORCH :: already on")
                } else {
                    QuickControls.Torch.toggle(context)
                    listOf("TORCH :: on")
                }
            }
            "off" -> {
                QuickControls.Torch.setOff(context)
                listOf("TORCH :: off")
            }
            "" -> {
                val state = QuickControls.Torch.toggle(context)
                    ?: return listOf("TORCH :: unavailable")
                listOf("TORCH :: " + if (state) "on" else "off")
            }
            else -> listOf("usage: torch [on|off]")
        }
    }

    private fun volumeLines(context: Context, argument: String): List<String> {
        val target = argument.lowercase(Locale.US)
        val result = when {
            target == "up" -> QuickControls.adjustVolume(context, +1)
            target == "down" -> QuickControls.adjustVolume(context, -1)
            target.toIntOrNull() != null -> QuickControls.setMediaVolume(context, target.toInt())
            else -> return listOf("usage: volume [0-100|up|down]")
        }
        return listOf("MEDIA VOLUME :: $result%")
    }

    private fun panelLines(context: Context, argument: String): List<String> {
        return when (argument.lowercase(Locale.US)) {
            "wifi", "internet" -> {
                QuickControls.openInternetPanel(context); listOf("PANEL :: internet")
            }
            "bt", "bluetooth" -> {
                QuickControls.openBluetoothSettings(context); listOf("PANEL :: bluetooth")
            }
            "dnd", "silent" -> {
                QuickControls.openDndSettings(context); listOf("PANEL :: do not disturb")
            }
            "display", "screen" -> {
                QuickControls.openDisplaySettings(context); listOf("PANEL :: display")
            }
            "volume", "audio" -> {
                QuickControls.openVolumePanel(context); listOf("PANEL :: volume")
            }
            "notification", "notifications", "access" -> {
                QuickControls.openNotificationAccessSettings(context)
                listOf("PANEL :: notification access")
            }
            "battery", "saver" -> {
                QuickControls.openBatterySaverSettings(context); listOf("PANEL :: battery saver")
            }
            else -> listOf("usage: panel wifi|bt|dnd|display|volume|notification|battery")
        }
    }

    private fun dndLines(context: Context, argument: String): List<String> {
        val key = argument.lowercase(Locale.US).trim()
        if (key.isEmpty() || key == "status") {
            return listOf(
                "INTERRUPTIONS :: " + DndControl.label(context),
                "  ACCESS  " + if (DndControl.hasAccess(context)) "GRANTED" else "NOT GRANTED",
                "  MODES   off · priority · alarms · silent",
            )
        }
        if (!DndControl.hasAccess(context)) {
            QuickControls.openDndAccessSettings(context)
            return listOf(
                "DND :: access not granted",
                "grant 'Do Not Disturb access' to JARVIS, then try again",
                "opened the system screen",
            )
        }
        val mode = when (key) {
            "on" -> DndControl.Mode.SILENT
            "off" -> DndControl.Mode.OFF
            else -> DndControl.Mode.fromText(key)
        } ?: return listOf("usage: dnd [status|on|off|priority|alarms|silent]")

        return when (DndControl.set(context, mode)) {
            true -> listOf("INTERRUPTIONS :: ${mode.label}")
            false -> listOf("DND :: android refused the change")
            null -> listOf("DND :: access not granted")
        }
    }

    private suspend fun shizukuLines(context: Context): List<String> {
        ShizukuShell.attach(context)
        ShizukuShell.refresh(context)
        val state = ShizukuShell.state.value
        return listOf(
            "PRIVILEGED SHELL :: " + ShizukuShell.describe(state),
            "  PRIVILEGE  " + state.privilege.ifBlank { "—" },
            "  NOTE       " + state.detail.ifBlank { "adb-level access, no root needed" },
            "  USAGE      shell <command>",
        )
    }

    private suspend fun shellLines(context: Context, argument: String): List<String> {
        if (argument.isBlank()) {
            return listOf("usage: shell <command>", "type 'shizuku' for connection status")
        }
        return when (val outcome = ShizukuShell.exec(context, argument)) {
            is ShizukuShell.Outcome.Failed -> listOf(
                "SHELL :: " + outcome.reason,
                "type 'shizuku' for status",
            )

            is ShizukuShell.Outcome.Ok -> buildList {
                add("SHELL :: exit ${outcome.exitCode}")
                if (outcome.output.isBlank()) {
                    add("  (no output)")
                } else {
                    outcome.output.lineSequence().take(12).forEach { add("  $it") }
                }
            }
        }
    }

    private fun routineLines(context: Context): List<String> {
        val routines = AutomationStore.load(context)
        if (routines.isEmpty()) return listOf("ROUTINES :: none saved")
        return buildList {
            add("ROUTINES :: ${routines.size}")
            routines.forEach { routine ->
                val sensitive = routine.sensitiveCount
                add(
                    "  ${routine.name}  ·  ${routine.steps.size} steps" +
                        if (sensitive > 0) "  ·  $sensitive confirm" else ""
                )
            }
            add("run <name> to start one")
        }
    }

    private fun runLines(context: Context, argument: String): List<String> {
        if (argument.isBlank()) return listOf("usage: run <routine name>")
        if (AutomationEngine.isBusy) return listOf("AUTOMATION :: already running — 'stop' first")
        val routines = AutomationStore.load(context)
        val match = routines.firstOrNull { it.name.equals(argument, ignoreCase = true) }
            ?: routines.firstOrNull { it.name.contains(argument, ignoreCase = true) }
            ?: return listOf(
                "ROUTINE NOT FOUND :: $argument",
                "type 'routines' to list saved routines",
            )
        AutomationEngine.run(context, match)
        return listOf("AUTOMATION :: started '${match.name}'")
    }

    private fun stopLines(): List<String> {
        if (!AutomationEngine.isBusy) return listOf("AUTOMATION :: nothing running")
        AutomationEngine.stop()
        return listOf("AUTOMATION :: stop requested")
    }

    private fun moduleLines(): List<String> = buildList {
        add("HUD MODULES")
        HudModules.all.forEach { module ->
            add("  ${module.title.padEnd(12)} ${if (module.isLive) "LIVE" else "STAGED"}")
        }
    }

    /**
     * Instagram summary from the cached sync — deliberately offline. A terminal command should
     * answer instantly; the module's REFRESH is the place that spends requests against Meta.
     */
    private fun instaLines(context: Context): List<String> {
        if (!InstagramStore.isConnected(context)) {
            return listOf(
                "INSTAGRAM :: NOT LINKED",
                "open HUD > INSTAGRAM to paste an access token",
            )
        }
        val snapshot = InstagramStore.cachedSnapshot(context)
            ?: return listOf(
                "INSTAGRAM :: LINKED · no reading yet",
                "open the INSTAGRAM module and tap SYNC NOW",
            )
        val stats = InstagramAnalytics.compute(snapshot, InstagramStore.growth(context))
        val minutes = ((System.currentTimeMillis() - snapshot.fetchedAt) / 60_000L).coerceAtLeast(0L)
        return listOf(
            "INSTAGRAM :: ${snapshot.profile.handle} · ${snapshot.profile.followers} followers",
            "  POSTS   ${stats.postCount} analysed · ${stats.averageEngagement.toInt()} avg interactions",
            "  RATE    " + String.format(Locale.US, "%.2f%%", stats.engagementRate),
            "  BEST    " + (
                stats.bestFormat?.let { format ->
                    format.format + " · " + String.format(Locale.US, "%.2f%%", format.averageRate)
                } ?: "—"
                ),
            "  TIMING  " + (
                stats.bestDay?.let { day ->
                    day + (stats.bestHour?.let { String.format(Locale.US, " %02d:00", it) } ?: "")
                } ?: "not enough posts"
                ),
            "  GROWTH  " + (
                stats.followerDelta?.let { delta -> (if (delta >= 0) "+" else "") + delta + " since first sync" }
                    ?: "first reading"
                ),
            "  SYNCED  " + when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 1_440 -> "${minutes / 60} h ago"
                else -> "${minutes / 1_440} d ago"
            },
        )
    }

    private fun aiLines(context: Context): List<String> {
        val config = AiKeyStore.config(context)
            ?: return listOf(
                "INTELLIGENCE :: NOT CONFIGURED",
                "settings > intelligence > add an API key",
                "any supported provider works; the key never leaves this device",
            )
        return listOf(
            "INTELLIGENCE :: LINKED",
            "  PROVIDER  ${config.label}",
            "  MODEL     ${config.resolvedModel}",
            "  KEY       ${config.maskedKey}",
            "  WIRE      ${config.provider.wire}",
        )
    }
}
