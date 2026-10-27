package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.SpeechOut
import com.jarvis.assistant.automation.StepExecutor
import com.jarvis.assistant.automation.WorkflowStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the whole voice turn: tap, speak, plan, execute, speak back.
 *
 * Rules this file exists to enforce:
 *
 *  - **One turn at a time.** Every turn carries an id; a late coroutine from a cancelled turn is
 *    ignored instead of writing over the card.
 *  - **Nothing can hang.** Recording, transcription, planning and every step are bounded.
 *  - **Nothing runs on the main thread** except state updates and speech.
 *  - **Honest outcomes.** A step that failed says why and stops the turn; it never reports success.
 */
object VoiceController {

    private const val PLAN_TIMEOUT_MS = 45_000L
    private const val STEP_TIMEOUT_MS = 25_000L
    private const val AUTO_DISMISS_MS = 2_400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceTask())
    val state: StateFlow<VoiceTask> = _state.asStateFlow()

    private var job: Job? = null
    private var mic: MicRecorder? = null
    private var local: LocalSpeech? = null
    private var taskId = 0

    fun hasMicrophone(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** The orb. First tap listens, second tap stops and runs. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        when (_state.value.phase) {
            VoicePhase.LISTENING -> stopListening(app)
            VoicePhase.THINKING, VoicePhase.RUNNING -> cancel()
            else -> startListening(app)
        }
    }

    fun cancel() {
        taskId += 1
        job?.cancel()
        job = null
        releaseEngines()
        _state.value = VoiceTask(taskId = taskId)
    }

    fun dismiss() {
        if (_state.value.phase.isBusy) return
        _state.value = VoiceTask(taskId = taskId)
    }

    /** Surfaces a local problem (permission denied, no recognizer) on the same card. */
    fun note(context: Context, message: String, title: String = "VOICE") {
        taskId += 1
        releaseEngines()
        _state.value = VoiceTask(
            taskId = taskId,
            phase = VoicePhase.FAILED,
            title = title,
            error = message,
            reply = message,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Listening
    // ---------------------------------------------------------------------------------------------

    private fun startListening(context: Context) {
        taskId += 1
        val id = taskId
        job?.cancel()
        job = null
        releaseEngines()
        Contacts.invalidate()

        if (!hasMicrophone(context)) {
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.FAILED,
                title = "MICROPHONE",
                error = "Microphone permission is off.",
                reply = "Allow the microphone, then tap the orb again.",
            )
            return
        }

        if (Whisper.ready(context)) {
            val recorder = MicRecorder()
            val problem = recorder.start()
            if (problem != null) {
                _state.value = VoiceTask(
                    taskId = id,
                    phase = VoicePhase.FAILED,
                    title = "MICROPHONE",
                    error = problem,
                    reply = problem,
                )
                return
            }
            mic = recorder
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.LISTENING,
                subtitle = "TAP TO STOP",
                source = "WHISPER",
            )
        } else {
            val session = LocalSpeech(context)
            session.start()
            local = session
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.LISTENING,
                subtitle = "TAP TO STOP",
                source = "DEVICE",
            )
        }
    }

    private fun stopListening(context: Context) {
        val id = taskId
        val recorder = mic
        val session = local
        mic = null
        local = null

        if (recorder == null && session == null) {
            _state.value = VoiceTask(taskId = id)
            return
        }

        _state.value = _state.value.copy(phase = VoicePhase.THINKING, subtitle = "TRANSCRIBING")

        job = scope.launch {
            val heard = try {
                if (recorder != null) {
                    val pcm = withContext(Dispatchers.IO) { recorder.stop() }
                    if (pcm.isEmpty()) {
                        fail(id, "NO AUDIO", "The microphone captured nothing.")
                        return@launch
                    }
                    val result = Whisper.transcribe(context, pcm)
                    if (result.text == null) {
                        fail(id, "TRANSCRIPTION", result.error ?: "The provider returned no words.")
                        return@launch
                    }
                    result.text
                } else if (session != null) {
                    session.stop()
                    val outcome = session.await(12_000L)
                    if (outcome.text == null) {
                        fail(id, "LISTENING", outcome.error ?: "I did not catch any words.")
                        return@launch
                    }
                    outcome.text
                } else {
                    null
                }
            } catch (t: Throwable) {
                fail(id, "LISTENING", "That did not work (${t.javaClass.simpleName}).")
                return@launch
            }

            if (heard.isNullOrBlank()) {
                fail(id, "LISTENING", "I did not catch any words.")
                return@launch
            }
            runTurn(context, id, heard)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Planning + executing
    // ---------------------------------------------------------------------------------------------

    private suspend fun runTurn(context: Context, id: Int, heard: String) {
        if (id != taskId) return
        _state.value = _state.value.copy(
            phase = VoicePhase.THINKING,
            heard = heard,
            title = "THINKING",
            subtitle = "PLANNING",
            steps = emptyList(),
            reply = "",
            error = null,
        )

        val plan = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            try {
                IntentRouter.plan(context, heard)
            } catch (t: Throwable) {
                null
            }
        }
        if (id != taskId) return
        if (plan == null) {
            fail(id, "NO PLAN", "I could not turn that into an action in time.")
            return
        }

        _state.value = _state.value.copy(
            title = plan.title,
            subtitle = plan.subtitle,
            source = plan.source,
            steps = plan.steps.map { VoiceStep(it.label) },
            reply = plan.say,
            phase = if (plan.steps.isEmpty()) VoicePhase.DONE else VoicePhase.RUNNING,
        )

        runCatching { VoiceMemory.remember(context, heard, plan.title) }

        if (plan.steps.isEmpty()) {
            speak(context, plan.say)
            scheduleDismiss(id)
            return
        }

        var failure: String? = null
        for ((index, step) in plan.steps.withIndex()) {
            if (id != taskId) return
            mark(id, index, StepPhase.ACTIVE)

            val outcome = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                try {
                    StepExecutor.execute(
                        context,
                        WorkflowStep(action = step.action, param = step.param),
                    )
                } catch (t: Throwable) {
                    StepExecutor.Result.Failed("Failed (${t.javaClass.simpleName})")
                }
            } ?: StepExecutor.Result.Failed("This step did not finish in time.")

            when (outcome) {
                is StepExecutor.Result.Ok -> mark(id, index, StepPhase.DONE, outcome.detail)
                is StepExecutor.Result.Skipped -> mark(id, index, StepPhase.SKIPPED, outcome.reason)
                is StepExecutor.Result.Failed -> {
                    mark(id, index, StepPhase.FAILED, outcome.reason)
                    failure = outcome.reason
                }
            }

            if (failure != null) break
            delay(220)
        }

        if (id != taskId) return

        if (failure == null) {
            _state.value = _state.value.copy(phase = VoicePhase.DONE, reply = plan.say, error = null)
            speak(context, plan.say)
            scheduleDismiss(id)
        } else {
            _state.value = _state.value.copy(phase = VoicePhase.FAILED, reply = failure, error = failure)
            speak(context, failure)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun mark(id: Int, index: Int, phase: StepPhase, detail: String = "") {
        if (id != taskId) return
        val current = _state.value
        _state.value = current.copy(
            steps = current.steps.mapIndexed { position, step ->
                if (position == index) step.copy(phase = phase, detail = detail) else step
            },
        )
    }

    private fun fail(id: Int, title: String, message: String) {
        if (id != taskId) return
        _state.value = _state.value.copy(
            phase = VoicePhase.FAILED,
            title = title,
            error = message,
            reply = message,
        )
    }

    private fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        scope.launch {
            try {
                SpeechOut.speak(context, text.take(240))
            } catch (t: Throwable) {
                // Speech is a courtesy; silence is not an error.
            }
        }
    }

    private fun scheduleDismiss(id: Int) {
        scope.launch {
            delay(AUTO_DISMISS_MS)
            if (id == taskId && _state.value.phase == VoicePhase.DONE) {
                _state.value = VoiceTask(taskId = id)
            }
        }
    }

    private fun releaseEngines() {
        val recorder = mic
        mic = null
        val session = local
        local = null
        session?.cancel()
        // Releasing a stream joins a worker thread; never do that on the main thread.
        if (recorder != null) {
            scope.launch(Dispatchers.IO) { recorder.cancel() }
        }
    }
}
