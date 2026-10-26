package com.jarvis.assistant.data

/**
 * Local, offline command registry for the Phase 1 terminal.
 *
 * This is intentionally *not* an AI. It is a small deterministic lookup so the interface can
 * be exercised end-to-end before the intelligence layer exists. Phase 5 replaces the body of
 * [respond] with a real backend round-trip while keeping the same UI contract:
 * in -> text, out -> lines of text.
 */
object CommandRegistry {

    data class Response(val output: List<String>)

    private val commands: List<Pair<String, (String) -> List<String>>> = listOf(
        "help" to { _ ->
            listOf(
                "AVAILABLE LOCAL COMMANDS",
                "  status      system summary",
                "  battery     cell diagnostics",
                "  network     link diagnostics",
                "  modules     list HUD modules",
                "  alerts      notification panel status",
                "  controls    quick action list",
                "  phase       roadmap position",
                "  routines    automation overview",
                "  time        current system time",
                "  clear       empty the terminal",
                "NOTE: intelligence core is offline (phase 5).",
            )
        },
        "status" to { _ ->
            listOf(
                "CORE :: ONLINE",
                "PHASE :: 6 — MOBILE AUTOMATION",
                "MODULES :: ${HudModules.all.size} REGISTERED / ${HudModules.all.count { it.isLive }} LIVE",
                "AUTOMATION :: LIVE (CONFIRM-GATED)",
                "AI CORE :: NOT CONNECTED",
            )
        },
        "battery" to { _ ->
            listOf(
                "CELL DIAGNOSTICS",
                "Open the POWER module for the live readout:",
                "level, charge state, temperature, voltage and health.",
            )
        },
        "network" to { _ ->
            listOf(
                "LINK DIAGNOSTICS",
                "Open the NETWORK module for the live readout:",
                "transport, validation state, downstream estimate.",
            )
        },
        "modules" to { _ ->
            listOf("REGISTERED HUD MODULES") + HudModules.all.map {
                "  ${it.title.padEnd(12)} phase ${it.phase}  ${if (it.isLive) "LIVE" else "STAGED"}"
            }
        },
        "phase" to { _ ->
            listOf(
                "ROADMAP",
                "  [DONE   ] 1  HUD and theme",
                "  [DONE   ] 2  Default launcher",
                "  [DONE   ] 3  Device info and controls",
                "  [QUEUED ] 4  Voice assistant",
                "  [QUEUED ] 5  AI intelligence",
                "  [ACTIVE ] 6  Mobile automation",
            )
        },
        "alerts" to { _ ->
            listOf(
                "NOTIFICATION PANEL",
                "Requires notification access in system settings.",
                "Open the ALERTS module to grant it or read the current shade.",
            )
        },
        "controls" to { _ ->
            listOf(
                "QUICK CONTROLS",
                "  torch       real toggle, no permission",
                "  volume      real toggle, no permission",
                "  media       needs notification access",
                "  wi-fi etc.  system panel only (Android restriction)",
            )
        },
        "routines" to { _ ->
            listOf(
                "AUTOMATION",
                "Routines run top to bottom, one at a time, max 20 steps.",
                "Sensitive steps pause for explicit approval.",
                "Gestures and screen presses need accessibility access.",
            )
        },
        "time" to { _ ->
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            listOf("SYSTEM TIME :: $now")
        },
    )

    private val hints = listOf(
        "Try 'help' for the local command list.",
        "Intelligence core is offline. This terminal is a local stub.",
        "Voice input arrives in phase 4.",
    )

    /** Returns the output lines for [input]. Never throws, never touches the network. */
    fun respond(input: String, context: String = ""): Response {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Response(emptyList())
        val key = trimmed.lowercase().substringBefore(' ')
        val handler = commands.firstOrNull { it.first == key }?.second
            ?: return Response(
                listOf(
                    "UNKNOWN COMMAND :: ${trimmed.uppercase()}",
                    hints.random(),
                )
            )
        return Response(handler(context))
    }

    val banner: List<String> = listOf(
        "J.A.R.V.I.S. LOCAL TERMINAL",
        "phase 1 build — no AI core attached",
        "input 'help' to list commands",
    )
}
