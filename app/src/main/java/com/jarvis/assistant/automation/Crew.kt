package com.jarvis.assistant.automation

import com.jarvis.assistant.data.CrewRecord
import com.jarvis.assistant.data.CrewSnapshot
import com.jarvis.assistant.data.Lane
import com.jarvis.assistant.data.LaneReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One brain, four lanes, one arbiter.
 *
 * There is one screen, one microphone and one voice on a phone. So the rule is not "run everything
 * at once" — it is "run the read-only things at once, and let exactly one thing touch the phone."
 * This object is the whole of that rule:
 *
 *  - [exclusive] takes the phone lock. Two callers never hold it at the same time; the second waits,
 *    and while it waits its lane reports QUEUED so the HUD can say why nothing is happening yet.
 *  - [read] runs immediately and reports, because looking something up does not fight anybody.
 *
 * Both are honest by construction: a lane is only marked LIVE while its block is actually running,
 * and is cleared in a `finally`, so a crash cannot leave a lane stuck on "LIVE" forever.
 *
 * **Do not nest [exclusive] inside [exclusive].** The lock is not re-entrant, because a re-entrant
 * lock is exactly how two screen controllers end up racing each other. The two callers — the voice
 * step loop and the routine runner — are siblings, never parents of each other.
 */
object Crew {

    private val phoneLock = Mutex()

    private val _state = MutableStateFlow(CrewSnapshot())
    val state: StateFlow<CrewSnapshot> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<CrewRecord>>(emptyList())
    val history: StateFlow<List<CrewRecord>> = _history.asStateFlow()

    private const val MAX_HISTORY = 40

    /** True while anything owns the screen. Used by the UI to explain a wait. */
    val phoneBusy: Boolean get() = phoneLock.isLocked

    private fun update(lane: Lane, change: (LaneReport) -> LaneReport) {
        _state.value = CrewSnapshot(
            _state.value.reports.map { report ->
                if (report.lane == lane) change(report) else report
            },
        )
    }

    private fun mark(lane: Lane, owner: String, detail: String, queued: Boolean, busy: Boolean) {
        update(lane) {
            it.copy(
                busy = busy,
                queued = queued,
                owner = owner,
                detail = detail,
                at = System.currentTimeMillis(),
            )
        }
    }

    private fun clear(lane: Lane, detail: String) {
        update(lane) {
            it.copy(busy = false, queued = false, owner = "", detail = detail, at = System.currentTimeMillis())
        }
    }

    private fun record(lane: Lane, owner: String, detail: String, ok: Boolean) {
        val next = _history.value + CrewRecord(
            lane = lane,
            owner = owner,
            detail = detail,
            at = System.currentTimeMillis(),
            ok = ok,
        )
        _history.value = next.takeLast(MAX_HISTORY)
    }

    /**
     * Work that owns the phone. Waits its turn, says it is waiting, and always releases.
     *
     * The QUEUED state is set *before* the wait rather than after it, so a HUD that repaints during
     * the wait already knows this lane is blocked on something else.
     */
    suspend fun <T> exclusive(lane: Lane, owner: String, detail: String = "", block: suspend () -> T): T {
        val waiting = phoneLock.isLocked
        if (waiting) mark(lane, owner, detail, queued = true, busy = false)

        return phoneLock.withLock {
            mark(lane, owner, detail, queued = false, busy = true)
            var ok = false
            try {
                val result = block()
                ok = true
                result
            } finally {
                clear(lane, if (ok) "done" else "stopped")
                record(lane, owner, detail.ifBlank { if (ok) "completed" else "stopped" }, ok)
            }
        }
    }

    /** Work that reads and cannot collide with anything. Runs now, reports, and gets out of the way. */
    suspend fun <T> read(lane: Lane, owner: String, detail: String = "", block: suspend () -> T): T {
        mark(lane, owner, detail, queued = false, busy = true)
        var ok = false
        try {
            val result = block()
            ok = true
            return result
        } finally {
            clear(lane, if (ok) "done" else "stopped")
            record(lane, owner, detail.ifBlank { "read" }, ok)
        }
    }

    /** A quiet note for something the board should show but that ran outside a lane block. */
    fun note(lane: Lane, detail: String) {
        update(lane) { it.copy(detail = detail, at = System.currentTimeMillis()) }
    }

    fun clearHistory() {
        _history.value = emptyList()
    }
}
