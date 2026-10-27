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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        val depth: Int = 0,
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
        val workflowId: String = "",
        val triggerLabel: String = "Manual",
    ) : RunState
}

/**
 * Sequential routine runner with control flow.
 *
 * Design rules, deliberately conservative:
 *  * one routine at a time — a second run request is refused, not queued;
 *  * a hard total-step budget so a loop cannot spin forever, plus a nesting-depth ceiling;
 *  * every sensitive step suspends for explicit confirmation, and auto-declines if the operator
 *    never answers, so a routine can never hang the engine;
 *  * every step has a timeout and optional retries, so one stuck action cannot stall a run;
 *  * a failing step follows its own policy: continue (default) or halt the routine;
 *  * STOP cancels immediately and leaves nothing running in the background.
 */
object AutomationEngine {

    /** Maximum steps executed in one run, counting nested and repeated ones. */
    const val MAX_TOTAL_STEPS = 60

    /** Maximum nesting of IF / REPEAT blocks. */
    const val MAX_DEPTH = 3

    /** How long a confirmation may sit unanswered before the run declines itself. */
    private const val CONFIRMATION_TIMEOUT_MS = 5 * 60 * 1000L

    /** Name given to the throwaway workflow used to test one step in the editor. */
    const val STEP_TEST_NAME = "STEP TEST"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var pendingConfirmation: CompletableDeferred<Boolean>? = null
    private val log = mutableListOf<RunLogEntry>()

    val isBusy: Boolean get() = currentJob?.isActive == true

    private class Budget(var remaining: Int)

    private class Counters {
        var succeeded = 0
        var failed = 0
        var skipped = 0
        var stopped = false
    }

    // -----------------------------------------------------------------------------------------
    // Entry points
    // -----------------------------------------------------------------------------------------

    /**
     * Runs exactly one step so the operator can prove it works before wiring it into a routine.
     *
     * Implemented as a one-step workflow, which means it inherits every guard the full runner has:
     * the confirmation gate for sensitive steps, the run log, and STOP.
     */
    fun testStep(context: Context, step: WorkflowStep): Boolean {
        if (isBusy) return false
        run(
            context,
            Workflow(id = "__step_test__", name = STEP_TEST_NAME, steps = listOf(step)),
            triggerLabel = "Step test",
        )
        return true
    }

    fun run(
        context: Context,
        workflow: Workflow,
        triggerLabel: String = "Manual",
    ) {
        if (isBusy) return
        val appContext = context.applicationContext

        currentJob = scope.launch {
            log.clear()
            val counters = Counters()
            val budget = Budget(MAX_TOTAL_STEPS)

            if (workflow.steps.isEmpty()) {
                log.add(RunLogEntry(0, "ROUTINE", "No steps configured", ok = false))
                finish(appContext, workflow, counters, triggerLabel, stopped = false)
                return@launch
            }

            _state.value = RunState.Running(
                workflowName = workflow.name,
                stepIndex = 0,
                stepCount = workflow.totalSteps,
                currentAction = "Starting",
                log = emptyList(),
            )

            val halted = try {
                executeList(appContext, workflow, workflow.steps, depth = 0, budget = budget, counters)
            } catch (t: Throwable) {
                log.add(RunLogEntry(0, "ROUTINE", "Aborted: ${t.javaClass.simpleName}", ok = false))
                counters.failed++
                true
            }

            counters.stopped = halted || !isActive
            finish(appContext, workflow, counters, triggerLabel, stopped = counters.stopped)
        }
    }

    private fun finish(
        context: Context,
        workflow: Workflow,
        counters: Counters,
        triggerLabel: String,
        stopped: Boolean,
    ) {
        val finished = RunState.Finished(
            workflowName = workflow.name,
            succeeded = counters.succeeded,
            failed = counters.failed,
            skipped = counters.skipped,
            stopped = stopped,
            log = log.toList(),
            workflowId = workflow.id,
            triggerLabel = triggerLabel,
        )
        _state.value = finished

        // Only real routines go into history; a step test is not a run worth remembering.
        if (workflow.id != "__step_test__") {
            AutomationStore.recordRun(context, finished)
        }
    }

    /**
     * Executes a list of steps. Returns true when the routine must stop (an explicit STOP, an
     * exhausted budget, or a failure whose policy is HALT).
     */
    private suspend fun executeList(
        context: Context,
        workflow: Workflow,
        steps: List<WorkflowStep>,
        depth: Int,
        budget: Budget,
        counters: Counters,
    ): Boolean {
        for ((index, step) in steps.withIndex()) {
            if (!currentCoroutineContext().isActive) return true

            if (budget.remaining <= 0) {
                log.add(
                    RunLogEntry(
                        stepIndex = index,
                        actionLabel = step.action.label,
                        outcome = "Stopped: $MAX_TOTAL_STEPS-step budget exhausted",
                        ok = false,
                    )
                )
                counters.failed++
                return true
            }
            budget.remaining--

            _state.value = RunState.Running(
                workflowName = workflow.name,
                stepIndex = workflow.totalSteps - budget.remaining,
                stepCount = workflow.totalSteps,
                currentAction = step.action.label,
                log = log.toList(),
                depth = depth,
            )
            delay(120) // let the UI paint between steps

            // ---- control flow ----
            when (step.action) {
                ActionType.STOP_ROUTINE -> {
                    log.add(RunLogEntry(index, step.action.label, "Routine stopped here", ok = true))
                    counters.succeeded++
                    return true
                }

                ActionType.IF_BLOCK -> {
                    val condition = step.condition
                    if (condition == null) {
                        log.add(RunLogEntry(index, "IF", "No condition set", ok = false))
                        counters.failed++
                        continue
                    }
                    val evaluation = ConditionEvaluator.evaluate(context, condition)
                    val branch = if (evaluation.passed) step.children else step.elseChildren
                    val branchName = if (evaluation.passed) "IF" else "ELSE"
                    log.add(
                        RunLogEntry(
                            index,
                            "$branchName ${condition.display}",
                            evaluation.describe,
                            ok = true,
                        )
                    )
                    if (branch.isEmpty()) continue
                    if (depth + 1 > MAX_DEPTH) {
                        log.add(RunLogEntry(index, "IF", "Nesting limit reached", ok = false))
                        counters.failed++
                        continue
                    }
                    val stopped = executeList(context, workflow, branch, depth + 1, budget, counters)
                    if (stopped) return true
                    continue
                }

                ActionType.REPEAT_BLOCK -> {
                    val times = (step.param.toIntOrNull() ?: 0).coerceIn(1, 10)
                    if (step.children.isEmpty()) {
                        log.add(RunLogEntry(index, "REPEAT", "Nothing inside to repeat", ok = false))
                        counters.failed++
                        continue
                    }
                    if (depth + 1 > MAX_DEPTH) {
                        log.add(RunLogEntry(index, "REPEAT", "Nesting limit reached", ok = false))
                        counters.failed++
                        continue
                    }
                    log.add(RunLogEntry(index, "REPEAT", "$times×", ok = true))
                    var stopped = false
                    for (round in 1..times) {
                        if (!currentCoroutineContext().isActive) {
                            stopped = true
                            break
                        }
                        if (budget.remaining <= 0) {
                            log.add(
                                RunLogEntry(
                                    index,
                                    "REPEAT",
                                    "Stopped at round $round: budget exhausted",
                                    ok = false,
                                )
                            )
                            counters.failed++
                            stopped = true
                            break
                        }
                        if (executeList(context, workflow, step.children, depth + 1, budget, counters)) {
                            stopped = true
                            break
                        }
                    }
                    if (stopped) return true
                    continue
                }

                ActionType.WAIT_UNTIL -> {
                    val condition = step.condition
                    if (condition == null) {
                        log.add(RunLogEntry(index, "WAIT UNTIL", "No condition set", ok = false))
                        counters.failed++
                        continue
                    }
                    val outcome = waitUntil(context, condition, step.timeoutSec)
                    log.add(
                        RunLogEntry(
                            index,
                            "WAIT UNTIL ${condition.display}",
                            outcome.first,
                            ok = outcome.second,
                        )
                    )
                    if (outcome.second) {
                        counters.succeeded++
                    } else {
                        counters.failed++
                        if (step.onFail == FailPolicy.HALT) return true
                    }
                    continue
                }

                else -> Unit
            }

            // ---- ordinary step ----
            // Sensitive steps stop the routine until a human says yes.
            if (step.action.sensitive) {
                val approved = awaitConfirmation(workflow, step, index, workflow.totalSteps)
                if (!approved) {
                    log.add(
                        RunLogEntry(
                            stepIndex = index,
                            actionLabel = step.action.label,
                            outcome = "Declined by operator",
                            ok = false,
                        )
                    )
                    counters.skipped++
                    return true
                }
            }

            val result = executeWithRetry(context, step)

            when (result) {
                is StepExecutor.Result.Ok -> {
                    counters.succeeded++
                    log.add(RunLogEntry(index, step.action.label, result.detail, ok = true))
                }

                is StepExecutor.Result.Failed -> {
                    counters.failed++
                    log.add(RunLogEntry(index, step.action.label, result.reason, ok = false))
                    if (step.onFail == FailPolicy.HALT) return true
                }

                is StepExecutor.Result.Skipped -> {
                    counters.skipped++
                    log.add(RunLogEntry(index, step.action.label, result.reason, ok = false))
                }
            }

            _state.value = RunState.Running(
                workflowName = workflow.name,
                stepIndex = workflow.totalSteps - budget.remaining,
                stepCount = workflow.totalSteps,
                currentAction = step.action.label,
                log = log.toList(),
                depth = depth,
            )
        }
        return false
    }

    /**
     * Runs one step with a timeout, and retries it when the operator asked for retries.
     *
     * The timeout is never shorter than the step's own intent: a WAIT of 30 s must not be killed by
     * a 45 s default in the wrong direction, and a gesture that takes a moment gets its retry.
     */
    private suspend fun executeWithRetry(
        context: Context,
        step: WorkflowStep,
    ): StepExecutor.Result {
        val waitSeconds = if (step.action == ActionType.WAIT) {
            (step.param.toIntOrNull() ?: 0).coerceIn(0, 120)
        } else {
            0
        }
        // Device work, including the accessibility walk, must not run on the caller's thread.
        val timeoutMs = ((maxOf(step.timeoutSec, waitSeconds + 10)).toLong()) * 1000L

        var attempt = 0
        var last: StepExecutor.Result = StepExecutor.Result.Failed("Not attempted")

        while (attempt <= step.retries) {
            val outcome = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Default) { StepExecutor.execute(context, step) }
            }

            last = outcome ?: StepExecutor.Result.Failed("Timed out after ${timeoutMs / 1000}s")

            // Only a hard failure is worth retrying; a skip means the prerequisite is missing.
            if (last is StepExecutor.Result.Ok || last is StepExecutor.Result.Skipped) return last

            if (attempt < step.retries) {
                delay(400L * (attempt + 1))
            }
            attempt++
        }
        return last
    }

    private suspend fun waitUntil(
        context: Context,
        condition: Condition,
        timeoutSec: Int,
    ): Pair<String, Boolean> {
        val deadline = System.currentTimeMillis() + timeoutSec.coerceIn(5, 600) * 1000L
        var lastDescribe = ""
        while (System.currentTimeMillis() < deadline) {
            if (!currentCoroutineContext().isActive) return "Cancelled" to false
            val evaluation = ConditionEvaluator.evaluate(context, condition)
            lastDescribe = evaluation.describe
            if (evaluation.passed) return "Became true · ${evaluation.describe}" to true
            delay(700)
        }
        return "Timed out waiting · $lastDescribe" to false
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
            // An unanswered prompt must not hold the engine forever: the operator may have walked
            // away, and a suspended routine blocks every later run.
            withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { deferred.await() } ?: false
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

        val running = state.value
        val name = when (running) {
            is RunState.Running -> running.workflowName
            is RunState.AwaitingConfirmation -> running.workflowName
            else -> "ROUTINE"
        }
        val workflowId = when (running) {
            is RunState.Finished -> running.workflowId
            else -> ""
        }

        _state.value = RunState.Finished(
            workflowName = name,
            succeeded = log.count { it.ok },
            failed = log.count { !it.ok },
            skipped = 0,
            stopped = true,
            log = log.toList(),
            workflowId = workflowId,
            triggerLabel = "Stopped by operator",
        )
    }

    fun dismissFinished() {
        if (state.value is RunState.Finished) _state.value = RunState.Idle
    }
}
