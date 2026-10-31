package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.Crew
import com.jarvis.assistant.automation.SpeechOut
import com.jarvis.assistant.automation.StepExecutor
import com.jarvis.assistant.automation.WorkflowStep
import com.jarvis.assistant.data.ConversationLog
import com.jarvis.assistant.data.PendingConfirm
import com.jarvis.assistant.wake.WakePhrase
import com.jarvis.assistant.data.Lane
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

    /** Two seconds of 16 kHz mono 16-bit audio, the window the silence check looks at. */
    private const val TAIL_SAMPLES_BYTES = 16_000 * 2 * 2

    /**
     * A feeling has to be at least this old before he asks about it.
     *
     * Anything newer belongs to the turn that just finished — asking "how are you now?" three
     * seconds after hearing "I am tired" would be absurd.
     */
    private const val CARRY_OVER_MIN_AGE_MS = 2 * 60 * 60 * 1000L

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

    /**
     * The wake word fired and nothing came with it, so listen now.
     *
     * Deliberately not [toggle]: a wake is always an instruction to listen, and calling toggle in
     * front of an already-listening session *stops* it — which is why saying the whole sentence a
     * second time used to be what made it work.
     */
    fun startFromWake(context: Context) {
        val app = context.applicationContext
        if (_state.value.phase == VoicePhase.LISTENING) return
        startListening(app)
    }

    /**
     * The wake word fired and the sentence was already captured, "hey jarvis open whatsapp" in one
     * breath. Cloud-enabled speech routes transcribe it directly. DEVICE/OFFLINE never upload these
     * bytes; Android's live recognizer cannot consume this PCM, so the operator is prompted to repeat.
     */
    fun submitCaptured(context: Context, pcm: ByteArray) {
        val app = context.applicationContext
        val route = VoiceSettings.route(app)
        val whisperReady = Whisper.ready(app)

        // Captured wake audio may leave the phone only on an explicitly cloud-enabled route.
        // DEVICE/OFFLINE reopen the microphone for a repeat instead of uploading the PCM.
        if (!route.allowsProviderTranscription) {
            startListening(app, "Wake audio stays on-device. Say the command again.")
            return
        }
        if (route == SpeechRoute.WHISPER && !whisperReady) {
            note(app, "Whisper needs a configured provider with a supported speech endpoint.", "WHISPER")
            return
        }
        if (!whisperReady) {
            startListening(app, "Wake audio was not transcribed. Say the command again.")
            return
        }

        taskId += 1
        val id = taskId
        job?.cancel()
        job = null
        releaseEngines()
        Contacts.invalidate()

        _state.value = VoiceTask(
            taskId = id,
            phase = VoicePhase.THINKING,
            subtitle = "TRANSCRIBING",
            source = "WAKE",
        )

        job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { Whisper.transcribe(app, pcm) }
            } catch (t: Throwable) {
                Whisper.Heard(error = "That did not work (${t.javaClass.simpleName}).")
            }

            if (id != taskId) return@launch

            val text = result.text
            if (text.isNullOrBlank()) {
                // Heard the wake word but not the sentence. Listen for it instead of guessing.
                startListening(app)
                return@launch
            }

            // The audio starts just before the phrase ended, so its transcript begins with it.
            val heard = WakePhrase.strip(text)
            if (heard.isBlank()) {
                startListening(app)
                return@launch
            }
            runTurn(app, id, heard)
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

    private fun startListening(context: Context, notice: String? = null) {
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
        val whisperReady = Whisper.ready(context)
        if (route == SpeechRoute.WHISPER && !whisperReady) {
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.FAILED,
                title = "WHISPER",
                error = "Whisper needs a configured provider with a supported speech endpoint.",
                reply = "Add a compatible provider key or choose AUTO/DEVICE.",
            )
            return
        }
        if (route == SpeechRoute.OFFLINE && !LocalSpeech.offlineRecognitionAvailable(context)) {
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.FAILED,
                title = "OFFLINE SPEECH",
                error = "This phone has no dedicated offline speech model.",
                reply = "Choose DEVICE or install an offline speech model.",
            )
            return
        }
        val useWhisper = route.allowsProviderTranscription && whisperReady

        if (useWhisper) {
            // The operator should not have to tap to say they have finished: a pause ends it.
            val recorder = MicRecorder(onAutoStop = { reason ->
                scope.launch {
                    if (id != taskId || _state.value.phase != VoicePhase.LISTENING) return@launch
                    when (reason) {
                        MicRecorder.EndReason.SPEECH_FINISHED -> stopListening(context)
                        MicRecorder.EndReason.NO_SPEECH,
                        MicRecorder.EndReason.READ_ERROR -> {
                            val active = mic
                            mic = null
                            if (active != null) withContext(Dispatchers.IO) { active.cancel() }
                            val (title, message) = when (reason) {
                                MicRecorder.EndReason.NO_SPEECH -> "NO SPEECH" to "I did not hear a command. Tap and try again."
                                MicRecorder.EndReason.READ_ERROR -> "MICROPHONE" to "The microphone stream stopped unexpectedly."
                                MicRecorder.EndReason.SPEECH_FINISHED -> "VOICE" to "Recording finished."
                            }
                            fail(id, title, message)
                        }
                    }
                }
            })
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
                title = if (notice.isNullOrBlank()) "" else "WAKE AUDIO",
                subtitle = if (notice.isNullOrBlank()) "TAP TO STOP" else "SAY COMMAND AGAIN",
                reply = notice.orEmpty(),
                source = VoiceSettings.activeEngine(context),
            )
        } else {
            val session = LocalSpeech(
                context,
                preferOffline = route == SpeechRoute.OFFLINE,
                languageTag = SpokenLanguage.recognizerTag(context),
                requireOffline = route == SpeechRoute.OFFLINE,
            )
            session.start()
            local = session
            _state.value = VoiceTask(
                taskId = id,
                phase = VoicePhase.LISTENING,
                title = if (notice.isNullOrBlank()) "" else "WAKE AUDIO",
                subtitle = if (notice.isNullOrBlank()) "TAP TO STOP" else "SAY COMMAND AGAIN",
                reply = notice.orEmpty(),
                source = VoiceSettings.activeEngine(context),
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

        // Read once per turn and carried through it: the tone of the reply has to match the mood
        // of the sentence that caused it, even if the step takes a while to run.
        val affect = try {
            Feelings.affect(heard)
        } catch (t: Throwable) {
            Feelings.Affect.NEUTRAL
        }
        val mood = try {
            Feelings.moodOf(hourNow(), affect, Feelings.bond(context))
        } catch (t: Throwable) {
            Feelings.Mood.CALM
        }
        val tone = Feelings.Tone.forMood(mood)

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

        val recordHeard = plan.sourceHeard?.takeIf { it.isNotBlank() } ?: heard
        val isCommand = plan.steps.isNotEmpty() || hasScreenTask || plan.sourceHeard != null
        lastHeard = recordHeard
        val spokenLang = Language.detect(recordHeard)
        SpokenLanguage.remember(context, spokenLang)
        val spokenLanguage = spokenLang.tag

        if (plan.steps.isEmpty() && !hasScreenTask) {
            val answer = plan.spoken ?: plan.say
            rememberTurn(
                context = context,
                heard = recordHeard,
                title = plan.title,
                reply = answer,
                command = isCommand,
                rememberForRepeat = false,
                affect = affect,
            )
            continueOrDismiss(context, id, answer, spokenLang, plan.title, tone)
            return
        }

        // Every screen goal may type, submit or send; it is never covered by a harmless OPEN_APP
        // step. Gate the whole plan before it touches the phone.
        val askable = if (plan.confirmed) null else ConfirmPolicy.firstAskable(plan.steps)
        val needsScreenConfirmation = !plan.confirmed &&
            ConfirmPolicy.needsScreenConfirmation(plan.screenGoal)
        if (askable != null || needsScreenConfirmation) {
            val question = when {
                askable != null && needsScreenConfirmation ->
                    ConfirmPolicy.question(askable, spokenLang) + " " +
                        ConfirmPolicy.screenTaskQuestion(plan.screenGoal.orEmpty(), spokenLang)
                askable != null -> ConfirmPolicy.question(askable, spokenLang)
                else -> ConfirmPolicy.screenTaskQuestion(plan.screenGoal.orEmpty(), spokenLang)
            }
            PendingConfirm.ask(
                context,
                PendingConfirm.Pending(
                    question = question,
                    title = plan.title,
                    subtitle = plan.subtitle,
                    say = plan.say,
                    steps = plan.steps.map { PendingConfirm.Step(it.action.id, it.param, it.label) },
                    screenGoal = plan.screenGoal,
                    heard = recordHeard,
                ),
            )
            _state.value = _state.value.copy(
                phase = VoicePhase.DONE,
                subtitle = "CONFIRM",
                reply = question,
                error = null,
            )
            speak(context, question, spokenLanguage)
            scheduleDismiss(id)
            return
        }

        // Everything from here touches the phone, so it holds the one device lock. A routine that
        // is already running will make this wait, and the ACTIVITY board will say QUEUED rather
        // than leaving the operator wondering why nothing is happening.
        val failure = Crew.exclusive(Lane.DEVICE, "VOICE", plan.title) {
            runDeviceWork(context, id, plan)
        }

        if (id != taskId) return

        if (failure == null) {
            // A finished task should not end on a step count when the operator was clearly having a
            // hard day: one short sentence, then the conversation can continue as usual.
            val tail = try {
                if (plan.steps.isEmpty()) null else Feelings.afterCommand(affect, spokenLang, plan.steps.size)
            } catch (t: Throwable) {
                null
            }
            val said = plan.spoken ?: plan.say
            val answer = if (tail.isNullOrBlank()) said else "${said.trim()} $tail"
            rememberTurn(
                context = context,
                heard = recordHeard,
                title = plan.title,
                reply = answer,
                command = isCommand,
                rememberForRepeat = isCommand,
                affect = affect,
                slot = plan.steps.firstOrNull()?.param.orEmpty().ifBlank { plan.screenGoal.orEmpty() },
            )
            _state.value = _state.value.copy(phase = VoicePhase.DONE, reply = answer, error = null)
            continueOrDismiss(context, id, answer, spokenLang, plan.title, tone)
        } else {
            // Keep an honest failure in context, but never teach repeat/follow-up memory that this
            // command succeeded.
            rememberTurn(
                context = context,
                heard = recordHeard,
                title = plan.title,
                reply = failure,
                command = isCommand,
                rememberForRepeat = false,
                affect = affect,
            )
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
            speak(context, failure, spokenLanguage, tone)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Records outcomes only after the turn is actually answered, approved, and executed. */
    private suspend fun rememberTurn(
        context: Context,
        heard: String,
        title: String,
        reply: String,
        command: Boolean,
        rememberForRepeat: Boolean,
        affect: Feelings.Affect,
        slot: String = "",
    ) {
        try {
            if (rememberForRepeat) {
                VoiceMemory.remember(context, heard, title)
                FollowUp.remember(
                    context,
                    FollowUp.Turn(heard = heard, title = title, said = reply, slot = slot),
                )
            }
            Brain.remember(
                context,
                Brain.Turn(
                    heard = heard,
                    reply = reply,
                    kind = if (command) Brain.Kind.COMMAND else Brain.Kind.CHAT,
                    title = title,
                ),
            )
            if (!command || rememberForRepeat) Brain.absorb(context, heard)
            withContext(Dispatchers.IO) {
                ConversationLog.append(
                    context = context,
                    heard = heard,
                    reply = reply,
                    title = title,
                    command = command,
                )
            }
            Feelings.recordTurn(context, affect)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Memory is best-effort and must never turn a completed phone action into a failure.
        }
    }

    /**
     * The fixed steps, then the screen-aware loop if the request needed one.
     *
     * Extracted so the whole of it can sit inside one [Crew.exclusive] block: a half-held lock —
     * steps run, screen task forgotten — would be worse than no lock at all.
     */
    private suspend fun runDeviceWork(context: Context, id: Int, plan: VoicePlan): String? {
        var failure: String? = null
        for ((index, step) in plan.steps.withIndex()) {
            if (id != taskId) return null
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

        if (id != taskId) return failure

        // The fixed steps are done; if the request needed an app JARVIS cannot predict, the
        // screen-aware loop finishes the job one tap at a time.
        val goal = plan.screenGoal
        if (failure == null && goal != null) {
            failure = runScreenTask(context, id, goal)
            if (id != taskId) return failure
        }

        return failure
    }

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
        tone: Feelings.Tone = Feelings.Tone.NEUTRAL,
    ) {
        if (!Conversation.isEnabled(context) || conversationTurns >= Conversation.maxTurns(context)) {
            _state.value = _state.value.copy(conversation = false, turn = 0)
            speak(context, answer, lang.tag, tone)
            scheduleDismiss(id)
            return
        }

        // Speak first, listen after: a microphone opened while the answer is still playing
        // transcribes JARVIS instead of the operator.
        sayAndWait(context, answer, lang.tag, tone)
        if (id != taskId) return

        conversationTurns += 1

        // What to ask next, in order of what he actually knows: a feeling he heard earlier, then a
        // real reading of the phone, then the plain question.
        val carry = try {
            Feelings.carryOver(
                context,
                minAgeMs = CARRY_OVER_MIN_AGE_MS,
            )?.also { Feelings.clearCarryOver(context) }
        } catch (t: Throwable) {
            null
        }
        val ask = if (carry != null) {
            Feelings.carryOverLine(carry, lang).ifBlank { Conversation.prompt(lang, title) }
        } else {
            Conversation.prompt(
                lang = lang,
                lastTitle = title,
                fallback = try {
                    Feelings.careFor(context, lang)
                } catch (t: Throwable) {
                    null
                },
            )
        }
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
        sayAndWait(context, ask, lang.tag, tone)
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
        val whisperReady = Whisper.ready(context)
        if (route == SpeechRoute.WHISPER && !whisperReady) return null
        val useWhisper = route.allowsProviderTranscription && whisperReady
        return if (useWhisper) captureWithWhisper(context, windowMs)
        else captureWithDevice(context, windowMs)
    }

    /** The device recognizer ends on its own silence, which is exactly what a window needs. */
    private suspend fun captureWithDevice(context: Context, windowMs: Long): String? {
        val route = VoiceSettings.route(context)
        if (route == SpeechRoute.OFFLINE && !LocalSpeech.offlineRecognitionAvailable(context)) return null
        val session = LocalSpeech(
            context,
            preferOffline = route == SpeechRoute.OFFLINE,
            languageTag = SpokenLanguage.recognizerTag(context),
            requireOffline = route == SpeechRoute.OFFLINE,
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

        // The polling loop reads a growing buffer and takes an RMS of it, which is real work: it
        // runs off the main thread so holding the ear open can never cost the HUD a frame.
        val deadline = System.currentTimeMillis() + windowMs
        withContext(Dispatchers.Default) {
            var speechSeen = false
            while (System.currentTimeMillis() < deadline) {
                delay(200L)
                if (!recorder.isRunning) continue
                // Only the last two seconds matter: speech already seen stays seen, and trailing
                // quiet is by definition at the end. Copying the whole recording every tick would
                // cost more than the answer is worth.
                val full = recorder.snapshot()
                val tail = full.copyOfRange(
                    maxOf(0, full.size - TAIL_SAMPLES_BYTES),
                    full.size,
                )
                if (!speechSeen) {
                    if (Conversation.hasSpeech(tail)) speechSeen = true
                } else if (Conversation.silenceAtEndMs(tail) >= Conversation.SILENCE_DONE_MS) {
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
    private suspend fun sayAndWait(
        context: Context,
        text: String,
        language: String?,
        tone: Feelings.Tone = Feelings.Tone.NEUTRAL,
    ) {
        if (text.isBlank()) return
        try {
            withTimeoutOrNull(SAY_TIMEOUT_MS) {
                SpeechOut.speak(context, text.take(240), language = language, tone = tone)
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

    private fun speak(
        context: Context,
        text: String,
        language: String? = null,
        tone: Feelings.Tone = Feelings.Tone.NEUTRAL,
    ) {
        if (text.isBlank()) return
        scope.launch {
            try {
                SpeechOut.speak(context, text.take(240), language = language, tone = tone)
            } catch (t: Throwable) {
                // Speech is a courtesy; silence is not an error.
            }
        }
    }

    private fun hourNow(): Int = try {
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    } catch (t: Throwable) {
        12
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
