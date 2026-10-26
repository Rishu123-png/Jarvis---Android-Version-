package com.jarvis.assistant.data

/**
 * The boot / status log that types itself out in the HUD's terminal panel.
 *
 * These are cosmetic status messages for phase 1 — they describe the interface coming up,
 * not privileged work being done.
 */
object BootSequence {

    val lines: List<String> = listOf(
        "> jarvis core .............. online",
        "> hud renderer ............. 60 fps",
        "> arc reactor .............. stable",
        "> telemetry bus ............ linked",
        "> app grid ................. staged",
        "> launcher mode ............ phase 2",
        "> voice engine ............. phase 4",
        "> intelligence core ........ offline",
    )

    /** Short rotating status notes shown under the reactor. */
    val statusNotes: List<String> = listOf(
        "ALL SYSTEMS NOMINAL",
        "TELEMETRY BUS LINKED",
        "ARC REACTOR STABLE",
        "AWAITING OPERATOR INPUT",
    )
}
