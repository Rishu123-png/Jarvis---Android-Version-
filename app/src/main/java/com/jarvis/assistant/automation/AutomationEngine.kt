package com.jarvis.assistant.automation

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single line in the run log. */
data class RunLogEntry(
    val stepIndex: Int,
    val actionLabel: String,
    val outcome: String,
    val ok: Boolean,
    val at: Long = System.currentTimeMillis(),
) {
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
}

/**
 * Run state. [AwaitingConfirmation] is a real pause: the engine is suspended mid-routine until the
 * operator approves or declines, which is how sensitive steps are gated.
 */
sealed interface RunState {
    data object Idle : RunState
    data class Running(
        val workflowName: String,
        val stepIndex: Int,
        val stepCount: Int,
        val currentAction: String,
        val log: List<RunLogEntry>,
    ) : RunState

    data class AwaitingConfirmation(
        val workflowName: String,
        val stepIndex: Int,
        val stepCount: Int,
        val actionLabel: String,
        val detail: String,
        val log: List<RunLogEntry>,
    ) : RunState

    data class Finished(
        val workflowName: String,
        val succeeded: Int,
        val failed: Int,
        val skipped: Int,
        val stopped: Boolean,
        val log: List<RunLogEntry>,
    ) : RunState
}

/**
 * Sequential routine runner.
 *
 * Design rules, deliberately conservative:
 *  * one routine at a time — a second run request is refused, not queued;
 *  * a hard step ceiling so a malformed routine cannot loop forever;
 *  * every sensitive step suspends for explicit confirmation;
 *  * STOP cancels immediately and leaves nothing running in the background;
 *  * a failing step does not abort the run — it is logged and the routine continues, so one
 *    unavailable action does not strand the rest.
 */
object AutomationEngine {

    private const val MAX_STEPS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null
    private val log = mutableListOf<RunLogEntry>()

    val isBusy: Boolean get() = currentJob?.isActive == true

    fun run(context: Context, workflow: Workflow) {
        if (isBusy) return
        val appContext = context.applicationContext

        currentJob = scope.launch {
            log.clear()
            var succeeded = 0
            var failed = 0
            var skipped = 0
            var stopped = false

            val steps = workflow.steps.take(MAX_STEPS)

            if (steps.isEmpty()) {
                _state.value = RunState.Finished(
                    workflowName = workflow.name,
                    succeeded = 0,
                    failed = 0,
                    skipped = 0,
                    stopped = false,
                    log = listOf(
                        RunLogEntry(0, "ROUTINE", "No steps configured", ok = false),
                    ),
                )
                return@launch
            }

            for ((index, step) in steps.withIndex()) {
                // `isActive` here is the coroutine's own flag, so STOP (which cancels the job)
                // breaks the loop cleanly between steps.
                if (!isActive) {
                    stopped = true
                    break
                }

                _state.value = RunState.Running(
                    workflowName = workflow.name,
                    stepIndex = index,
                    stepCount = steps.size,
                    currentAction = step.action.label,
                    log = log.toList(),
                )
                delay(180) // let the UI paint between steps

                // Sensitive steps stop the routine until a human says yes.
                if (step.action.sensitive) {
                    val approved = awaitConfirmation(workflow, step, index, steps.size)
                    if (!approved) {
                        log.add(
                            RunLogEntry(
                                stepIndex = index,
                                actionLabel = step.action.label,
                                outcome = "Declined by operator",
                                ok = false,
                            )
                        )
                        skipped++
                        stopped = true
                        break
                    }
                }

                // Device work — including a bounded walk of the accessibility tree — must not run
                // on the main thread: on a busy screen that walk is long enough to jank the HUD.
                val result = withContext(Dispatchers.Default) {
                    StepExecutor.execute(appContext, step)
                }

                val entry = when (result) {
                    is StepExecutor.Result.Ok -> {
                        succeeded++
                        RunLogEntry(index, step.action.label, result.detail, ok = true)
                    }

                    is StepExecutor.Result.Failed -> {
                        failed++
                        RunLogEntry(index, step.action.label, result.reason, ok = false)
                    }

                    is StepExecutor.Result.Skipped -> {
                        skipped++
                        RunLogEntry(index, step.action.label, result.reason, ok = false)
                    }
                }
                log.add(entry)

                _state.value = RunState.Running(
                    workflowName = workflow.name,
                    stepIndex = index,
                    stepCount = steps.size,
                    currentAction = step.action.label,
                    log = log.toList(),
                )
            }

            _state.value = RunState.Finished(
                workflowName = workflow.name,
                succeeded = succeeded,
                failed = failed,
                skipped = skipped,
                stopped = stopped,
                log = log.toList(),
            )
        }
    }

    private suspend fun awaitConfirmation(
        workflow: Workflow,
        step: WorkflowStep,
        index: Int,
        total: Int,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmation = deferred

        _state.value = RunState.AwaitingConfirmation(
            workflowName = workflow.name,
            stepIndex = index,
            stepCount = total,
            actionLabel = step.action.label,
            detail = step.displayParam,
            log = log.toList(),
        )

        return try {
            deferred.await()
        } finally {
            pendingConfirmation = null
        }
    }

    /** Operator decision for the pending sensitive step. */
    fun confirm(approved: Boolean) {
        pendingConfirmation?.complete(approved)
    }

    fun stop() {
        pendingConfirmation?.complete(false)
        pendingConfirmation = null
        currentJob?.cancel()
        currentJob = null
        _state.value = RunState.Finished(
            workflowName = (state.value as? RunState.Running)?.workflowName
                ?: (state.value as? RunState.AwaitingConfirmation)?.workflowName
                ?: "ROUTINE",
            succeeded = log.count { it.ok },
            failed = log.count { !it.ok },
            skipped = 0,
            stopped = true,
            log = log.toList(),
        )
    }

    fun dismissFinished() {
        if (state.value is RunState.Finished) _state.value = RunState.Idle
    }
}
