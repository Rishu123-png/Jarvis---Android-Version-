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
 *  - **One session, many sentences.** When conversation mode is on (see [Conversation]) the ear
 *    stays open after a finished turn, so an obvious next sentence does not need the orb again.
 */
object VoiceController {

    private const val PLAN_TIMEOUT_MS = 45_000L
    private const val STEP_TIMEOUT_MS = 25_000L
    private const val AUTO_DISMISS_MS = 2_400L
    private const val SAY_TIMEOUT_MS = 22_000L
    private const val FOLLOW_UP_TRANSCRIBE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceTask())
    val state: StateFlow<VoiceTask> = _state.asStateFlow()

    private var job: Job? = null
    private var mic: MicRecorder? = null
    private var local: LocalSpeech? = null
    private var taskId = 0

    /**
     * How many follow-up sentences have been taken in this session.
     *
     * Reset whenever a session starts or ends, so every session gets the same budget and the count
     * can never creep across an idle period.
     */
    private var conversationTurns = 0

    /** The last thing the operator said, so follow-ups ("again, but quieter") have a subject. */
    @Volatile
    private var lastHeard: String? = null

    fun hasMicrophone(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** The orb. First tap listens, second tap stops and runs. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        when (_state.value.phase) {
            VoicePhase.LISTENING ->
                // While a conversation is open the microphone is held on purpose: a tap means
                // "that is all", so the session ends instead of transcribing half a sentence.
                if (_state.value.conversation) endConversation() else stopListening(app)
            VoicePhase.THINKING, VoicePhase.RUNNING -> cancel()
            else -> startListening(app)
        }
    }

    fun cancel() {
        taskId += 1
        conversationTurns = 0
        job?.cancel()
        job = null
        releaseEngines()
        _state.value = VoiceTask(taskId = taskId)
    }

    /** Closes an open conversation without pretending anything failed. */
    private fun endConversation() {
        taskId += 1
        conversationTurns = 0
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
        conversationTurns = 0
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

        val route = VoiceSettings.route(context)
        val useWhisper = when (route) {
            SpeechRoute.WHISPER -> Whisper.ready(context)
            SpeechRoute.DEVICE, SpeechRoute.OFFLINE -> false
            SpeechRoute.AUTO -> Whisper.ready(context)
        }

        if (useWhisper) {
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
            val session = LocalSpeech(
                context,
                preferOffline = route == SpeechRoute.OFFLINE,
                languageTag = SpokenLanguage.recognizerTag(context),
            )
            session.start()
            local = session
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.LISTENING,
                subtitle = "TAP TO STOP",
                source = if (route == SpeechRoute.OFFLINE) "DEVICE · OFFLINE" else "DEVICE",
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
                    val result = Whisper.transcribe(context, pcm, hint = transcriptHint(context))
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

    private suspend fun runTurn(
        context: Context,
        id: Int,
        heard: String,
        conversation: Boolean = false,
    ) {
        if (id != taskId) return
        _state.value = _state.value.copy(
            phase = VoicePhase.THINKING,
            heard = heard,
            conversation = conversation || _state.value.conversation,
            title = "THINKING",
            subtitle = "PLANNING",
            steps = emptyList(),
            reply = "",
            error = null,
        )

        val previous = lastHeard
        val plan = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            try {
                IntentRouter.plan(context, heard, previous)
            } catch (t: Throwable) {
                null
            }
        }
        if (id != taskId) return
        if (plan == null) {
            fail(id, "NO PLAN", "I could not turn that into an action in time.")
            return
        }

        val hasScreenTask = plan.screenGoal != null
        _state.value = _state.value.copy(
            title = plan.title,
            subtitle = plan.subtitle,
            source = plan.source,
            steps = plan.steps.map { VoiceStep(it.label) },
            reply = plan.say,
            phase = if (plan.steps.isEmpty() && !hasScreenTask) VoicePhase.DONE else VoicePhase.RUNNING,
        )

        runCatching {
            VoiceMemory.remember(context, heard, plan.title)
            FollowUp.remember(
                context,
                FollowUp.Turn(
                    heard = heard,
                    title = plan.title,
                    said = plan.say,
                    slot = plan.steps.firstOrNull()?.param.orEmpty(),
                ),
            )
            // The thread is what makes the next sentence make sense, and `absorb` is what turns a
            // plain sentence into a memory ("my sister is Khushi", "I live in Delhi").
            Brain.remember(
                context,
                Brain.Turn(
                    heard = heard,
                    reply = plan.say,
                    kind = if (plan.steps.isEmpty()) Brain.Kind.CHAT else Brain.Kind.COMMAND,
                    title = plan.title,
                ),
            )
            Brain.absorb(context, heard)
        }
        lastHeard = heard
        val spokenLang = Language.detect(heard)
        SpokenLanguage.remember(context, spokenLang)
        val spokenLanguage = spokenLang.tag

        if (plan.steps.isEmpty() && !hasScreenTask) {
            continueOrDismiss(context, id, plan.say, spokenLang, plan.title)
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

        // The fixed steps are done; if the request needed an app JARVIS cannot predict, the
        // screen-aware loop finishes the job one tap at a time.
        val goal = plan.screenGoal
        if (failure == null && goal != null) {
            failure = runScreenTask(context, id, goal)
            if (id != taskId) return
        }

        if (failure == null) {
            _state.value = _state.value.copy(phase = VoicePhase.DONE, reply = plan.say, error = null)
            continueOrDismiss(context, id, plan.say, spokenLang, plan.title)
        } else {
            // A failure ends the session: holding the ear open after something broke would invite
            // a second doomed attempt without explaining the first.
            conversationTurns = 0
            _state.value = _state.value.copy(
                phase = VoicePhase.FAILED,
                reply = failure,
                error = failure,
                conversation = false,
                turn = 0,
            )
            speak(context, failure, spokenLanguage)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs the observe → decide → act loop and streams every action onto the card, so a long task
     * is watchable instead of a spinner.
     */
    private suspend fun runScreenTask(context: Context, id: Int, goal: String): String? {
        appendStep(id, "SCREEN TASK", "READING THE SCREEN")
        val failure = try {
            ScreenAgent.run(context, goal) { event -> appendAgentEvent(id, event.label, event.detail) }
        } catch (t: Throwable) {
            "The screen task stopped (${t.javaClass.simpleName})."
        }
        closeLastStep(id, failure == null)
        return failure
    }

    // ---------------------------------------------------------------------------------------------
    // Conversation mode
    // ---------------------------------------------------------------------------------------------

    /**
     * The end of every turn: answer, then either close or hold the ear open.
     *
     * The session is continued only when the operator turned it on, the turn budget is not spent,
     * and the last turn actually succeeded — a conversation that keeps asking "what next?" after a
     * failure is worse than one that stops.
     */
    private suspend fun continueOrDismiss(
        context: Context,
        id: Int,
        answer: String,
        lang: Lang,
        title: String,
    ) {
        if (!Conversation.isEnabled(context) || conversationTurns >= Conversation.maxTurns(context)) {
            _state.value = _state.value.copy(conversation = false, turn = 0)
            speak(context, answer, lang.tag)
            scheduleDismiss(id)
            return
        }

        // Speak first, listen after: a microphone opened while the answer is still playing
        // transcribes JARVIS instead of the operator.
        sayAndWait(context, answer, lang.tag)
        if (id != taskId) return

        conversationTurns += 1
        val ask = Conversation.prompt(lang, title)
        _state.value = _state.value.copy(
            phase = VoicePhase.LISTENING,
            conversation = true,
            turn = conversationTurns,
            reply = ask,
            subtitle = "LISTENING · ${lang.label}",
            source = try {
                VoiceSettings.activeEngine(context)
            } catch (t: Throwable) {
                "VOICE"
            },
        )
        sayAndWait(context, ask, lang.tag)
        if (id != taskId) return

        val heard = captureFollowUp(context, Conversation.windowMs(context))
        if (id != taskId) return

        when {
            // Silence is not a failure. The window simply closes.
            heard.isNullOrBlank() -> {
                _state.value = _state.value.copy(
                    phase = VoicePhase.DONE,
                    conversation = false,
                    turn = 0,
                    subtitle = "WINDOW CLOSED",
                    reply = Conversation.slept(lang),
                )
                scheduleDismiss(id)
            }

            Conversation.isExit(heard) -> {
                val bye = Conversation.goodbye(lang)
                _state.value = _state.value.copy(
                    phase = VoicePhase.DONE,
                    conversation = false,
                    turn = 0,
                    heard = heard,
                    reply = bye,
                )
                speak(context, bye, lang.tag)
                scheduleDismiss(id)
            }

            // Anything else is just the next sentence of the same session.
            else -> runTurn(context, id, heard, conversation = true)
        }
    }

    /**
     * One follow-up sentence, captured without a second tap.
     *
     * Two routes, same contract: return what was said, or null when there was nothing worth acting
     * on. Both are bounded — a window that never closes would leave a hot microphone behind a HUD
     * that looks idle.
     */
    private suspend fun captureFollowUp(context: Context, windowMs: Long): String? {
        val route = VoiceSettings.route(context)
        val useWhisper = when (route) {
            SpeechRoute.WHISPER -> Whisper.ready(context)
            SpeechRoute.DEVICE, SpeechRoute.OFFLINE -> false
            SpeechRoute.AUTO -> Whisper.ready(context)
        }
        return if (useWhisper) captureWithWhisper(context, windowMs)
        else captureWithDevice(context, windowMs)
    }

    /** The device recognizer ends on its own silence, which is exactly what a window needs. */
    private suspend fun captureWithDevice(context: Context, windowMs: Long): String? {
        val session = LocalSpeech(
            context,
            preferOffline = VoiceSettings.preferOffline(context),
            languageTag = SpokenLanguage.recognizerTag(context),
        )
        session.start()
        local = session
        val outcome = try {
            session.await(windowMs + 5_000L)
        } catch (t: Throwable) {
            null
        }
        local = null
        session.cancel()
        return outcome?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * The provider route records the window itself, and stops as soon as the operator has been
     * quiet for long enough — waiting the full nine seconds after a two-second sentence is what
     * makes a voice assistant feel slow.
     */
    private suspend fun captureWithWhisper(context: Context, windowMs: Long): String? {
        val recorder = MicRecorder()
        if (recorder.start() != null) return null
        mic = recorder

        val deadline = System.currentTimeMillis() + windowMs
        var speechSeen = false
        while (System.currentTimeMillis() < deadline) {
            delay(200L)
            if (recorder.isRunning) {
                val snapshot = recorder.snapshot()
                if (!speechSeen) {
                    if (Conversation.hasSpeech(snapshot)) speechSeen = true
                } else if (Conversation.silenceAtEndMs(snapshot) >= Conversation.SILENCE_DONE_MS) {
                    break
                }
            }
        }

        val pcm = try {
            withContext(Dispatchers.IO) { recorder.stop() }
        } catch (t: Throwable) {
            ByteArray(0)
        }
        mic = null

        // Room tone is not a question: a silent window ends the session without spending a request.
        if (!Conversation.hasSpeech(pcm)) return null

        val heard = withTimeoutOrNull(FOLLOW_UP_TRANSCRIBE_MS) {
            try {
                Whisper.transcribe(context, pcm, hint = transcriptHint(context))
            } catch (t: Throwable) {
                null
            }
        }
        return heard?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * The Roman-letter nudge for transcription, or null.
     *
     * Only for an operator who was already speaking Hinglish: their words should come back as
     * Roman letters so the reply is Hinglish too, not Devanagari Hindi.
     */
    private fun transcriptHint(context: Context): String? =
        if (SpokenLanguage.last(context) == Lang.HINGLISH) {
            "Hinglish in Roman letters: kya haal hai bhai, whatsapp kholo, kal milte hain."
        } else {
            null
        }

    /** Speech that blocks until it is finished, used where the microphone opens straight after. */
    private suspend fun sayAndWait(context: Context, text: String, language: String?) {
        if (text.isBlank()) return
        try {
            withTimeoutOrNull(SAY_TIMEOUT_MS) {
                SpeechOut.speak(context, text.take(240), language = language)
            }
        } catch (t: Throwable) {
            // Speech is a courtesy; silence is not an error, and the window still opens.
        }
    }

    private fun appendStep(id: Int, label: String, detail: String) {
        if (id != taskId) return
        val current = _state.value
        _state.value = current.copy(steps = current.steps + VoiceStep(label, StepPhase.ACTIVE, detail))
    }

    private fun appendAgentEvent(id: Int, label: String, detail: String) {
        if (id != taskId) return
        val current = _state.value
        val closed = current.steps.mapIndexed { index, step ->
            if (index == current.steps.lastIndex && step.phase == StepPhase.ACTIVE) {
                step.copy(phase = StepPhase.DONE)
            } else {
                step
            }
        }
        _state.value = current.copy(steps = closed + VoiceStep(label, StepPhase.ACTIVE, detail))
    }

    private fun closeLastStep(id: Int, ok: Boolean) {
        if (id != taskId) return
        val current = _state.value
        if (current.steps.isEmpty()) return
        val updated = current.steps.mapIndexed { index, step ->
            if (index == current.steps.lastIndex) {
                step.copy(phase = if (ok) StepPhase.DONE else StepPhase.FAILED)
            } else {
                step
            }
        }
        _state.value = current.copy(steps = updated)
    }

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

    private fun speak(context: Context, text: String, language: String? = null) {
        if (text.isBlank()) return
        scope.launch {
            try {
                SpeechOut.speak(context, text.take(240), language = language)
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
