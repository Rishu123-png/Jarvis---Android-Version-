package com.jarvis.assistant.data

/**
 * The crew: one brain, four lanes, one arbiter.
 *
 * The AUGMA concept shows three named agents working at once. On a phone that is a lie with a
 * cost — there is one screen, one microphone and one voice, so "parallel agents" means two processes
 * fighting for the same window and two model calls where one would do.
 *
 * What is true is narrower and still useful: some of his work is **read-only and parallel** (looking
 * something up, counting what is waiting), and some **owns the screen and must happen one at a time**
 * (tapping, typing, running a routine). So there are four lanes, and only two rules:
 *
 *  - [Lane.RESEARCH] and [Lane.NOTIFY] may run alongside anything, including each other;
 *  - [Lane.DEVICE] and [Lane.AUTOMATE] share one lock — whatever holds it owns the phone, and
 *    everything else queues.
 *
 * No new model calls, no invented task counts. A lane only reports what actually ran.
 *
 * Pure Kotlin, so the ordering and the wording are covered by `CrewTest`.
 */
enum class Lane(
    val id: String,
    val label: String,
    val caption: String,
    /** True when this lane needs exclusive use of the phone. */
    val exclusive: Boolean,
) {
    DEVICE(
        id = "device",
        label = "DEVICE",
        caption = "Opens, taps and types on the screen",
        exclusive = true,
    ),
    AUTOMATE(
        id = "automate",
        label = "ROUTINES",
        caption = "Your trigger-driven workflows",
        exclusive = true,
    ),
    RESEARCH(
        id = "research",
        label = "RESEARCH",
        caption = "Look-ups and the knowledge shelf",
        exclusive = false,
    ),
    NOTIFY(
        id = "notify",
        label = "ALERTS",
        caption = "What is waiting from other apps",
        exclusive = false,
    ),
    ;

    companion object {
        fun fromId(id: String?): Lane? = entries.firstOrNull { it.id == id }
    }
}

/** Where one lane is right now. */
data class LaneReport(
    val lane: Lane,
    val busy: Boolean = false,
    /** Who holds it — "VOICE", "ROUTINE: Evening", "SIGHT". Empty when idle. */
    val owner: String = "",
    val detail: String = "",
    /** True when an exclusive lane is waiting for the phone rather than using it. */
    val queued: Boolean = false,
    val at: Long = 0L,
) {
    /** The chip shown for this lane. Short, and true. */
    val status: String
        get() = when {
            queued -> "QUEUED"
            busy -> "LIVE"
            else -> "IDLE"
        }

    val line: String
        get() = when {
            queued -> "waiting for the phone"
            busy && detail.isNotBlank() -> "$owner · $detail"
            busy -> owner
            detail.isNotBlank() -> detail
            else -> "nothing running"
        }
}

/** The whole board, in display order. */
data class CrewSnapshot(val reports: List<LaneReport> = Lane.entries.map { LaneReport(it) }) {

    fun of(lane: Lane): LaneReport = reports.firstOrNull { it.lane == lane } ?: LaneReport(lane)

    /** Lanes that are doing something right now. */
    val active: List<LaneReport> get() = reports.filter { it.busy && !it.queued }

    /** One line for the HUD: "2 LIVE · DEVICE BUSY" or "ALL IDLE". */
    fun summary(): String {
        val live = active.count()
        if (live == 0) return "ALL IDLE"
        val device = of(Lane.DEVICE)
        return buildString {
            append("$live LIVE")
            if (device.busy) append(" \u00B7 PHONE IN USE")
        }
    }

    companion object {
        /** Display order: what holds the phone first, then what is read-only. */
        val ORDER: List<Lane> = listOf(Lane.DEVICE, Lane.AUTOMATE, Lane.RESEARCH, Lane.NOTIFY)

        fun ordered(reports: List<LaneReport>): List<LaneReport> =
            ORDER.mapNotNull { lane -> reports.firstOrNull { it.lane == lane } }

        fun from(map: Map<Lane, LaneReport>): CrewSnapshot =
            CrewSnapshot(Lane.entries.map { map[it] ?: LaneReport(it) })
    }
}

/** One finished piece of work, kept so the board can show history rather than promise. */
data class CrewRecord(
    val lane: Lane,
    val owner: String,
    val detail: String,
    val at: Long,
    val ok: Boolean,
)
