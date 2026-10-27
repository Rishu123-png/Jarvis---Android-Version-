package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.device.CalendarBridge
import com.jarvis.assistant.device.HealthLog
import com.jarvis.assistant.device.LockGate
import com.jarvis.assistant.device.Nudges
import com.jarvis.assistant.device.NotifyBrain
import com.jarvis.assistant.device.Whereabouts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.AutomationEngine
import com.jarvis.assistant.automation.AutomationStore
import com.jarvis.assistant.automation.AutomationTriggers
import com.jarvis.assistant.automation.RunRecord
import com.jarvis.assistant.automation.RunState
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.DockPrefs
import com.jarvis.assistant.data.HudModules
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.device.BiometricPolicy
import com.jarvis.assistant.device.Biometrics
import com.jarvis.assistant.device.Bubble
import com.jarvis.assistant.device.DndControl
import com.jarvis.assistant.device.JarvisWidget
import com.jarvis.assistant.device.Privacy
import com.jarvis.assistant.device.LauncherStatus
import com.jarvis.assistant.device.NotificationAccess
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.QuickControls
import com.jarvis.assistant.device.readStorage
import com.jarvis.assistant.device.rememberDeviceInfo
import com.jarvis.assistant.device.rememberSystemStatus
import com.jarvis.assistant.shell.ShizukuShell
import com.jarvis.assistant.ui.components.ConfirmationOverlay
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.rememberClock
import com.jarvis.assistant.ui.components.rememberIsResumed
import com.jarvis.assistant.ui.screens.AiSettingsScreen
import com.jarvis.assistant.ui.screens.ConnectorsScreen
import com.jarvis.assistant.ui.screens.InstagramScreen
import com.jarvis.assistant.ui.screens.AppDrawerScreen
import com.jarvis.assistant.ui.screens.AutomationEditorScreen
import com.jarvis.assistant.ui.screens.AutomationScreen
import com.jarvis.assistant.ui.screens.ComposeScreen
import com.jarvis.assistant.ui.screens.ControlsScreen
import com.jarvis.assistant.ui.screens.DashboardScreen
import com.jarvis.assistant.ui.screens.DiagnosticsScreen
import com.jarvis.assistant.ui.screens.DockPickerScreen
import com.jarvis.assistant.ui.screens.HomeScreen
import com.jarvis.assistant.ui.screens.KnowledgeScreen
import com.jarvis.assistant.ui.screens.LauncherSettingsScreen
import com.jarvis.assistant.ui.screens.ModuleScaffold
import com.jarvis.assistant.ui.screens.NetworkScreen
import com.jarvis.assistant.ui.screens.NotificationsScreen
import com.jarvis.assistant.ui.screens.PrivacyScreen
import com.jarvis.assistant.ui.screens.PowerScreen
import com.jarvis.assistant.ui.screens.LockScreen
import com.jarvis.assistant.ui.screens.TerminalScreen
import com.jarvis.assistant.voice.Brain
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.VoidBlack
import com.jarvis.assistant.voice.VoiceController
import com.jarvis.assistant.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.mutableLongStateOf

private const val ROUTE_HOME = "home"
private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TERMINAL = "terminal"
private const val ROUTE_DRAWER = "drawer"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DOCK = "dock"
private const val ROUTE_ROUTINE = "routine"
private const val ROUTE_AI = "ai"
private const val ROUTE_COMPOSE = "compose"
private const val ROUTE_ADVANCED = "advanced"

/** True when JARVIS may post its own notifications (always true below Android 13). */
private fun canPostNotifications(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT < 33) {
        true
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Application shell.
 *
 * Owns every piece of device state the HUD renders — installed apps, the persisted dock, the Home
 * role, notification access and the live notification list, plus the control state (torch,
 * volume, media) and the operator's AI configuration. Screens stay stateless so they can be
 * previewed and reasoned about in isolation.
 */
@Composable
fun JarvisApp(
    voiceArmed: Boolean = false,
    onVoiceConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val clock = rememberClock()
    val status = rememberSystemStatus()
    val device = rememberDeviceInfo()

    var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }

    // ---- privacy lock ----
    var locked by remember {
        mutableStateOf(
            Privacy.isEnabled(context) || LockGate.mode(context) == LockGate.Mode.PHONE_LOCK,
        )
    }
    var pinChecking by remember { mutableStateOf(false) }
    var biometricAvailable by remember { mutableStateOf(Biometrics.isAvailable(context)) }
    var biometricEnabled by remember { mutableStateOf(Biometrics.isEnabled(context)) }
    var biometricPending by remember { mutableStateOf(false) }
    var biometricFailures by remember { mutableIntStateOf(0) }
    var biometricTried by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var leftAt by remember { mutableLongStateOf(0L) }

    // ---- which lock, and what the lock screen says -------------------------------------------
    var lockMode by remember { mutableStateOf(LockGate.mode(context)) }
    var lockNoticeEnabled by remember { mutableStateOf(LockGate.noticeEnabled(context)) }
    var notifyBrainEnabled by remember { mutableStateOf(NotifyBrain.isEnabled(context)) }
    var roadRoutingEnabled by remember { mutableStateOf(Whereabouts.roadRoutingEnabled(context)) }
    var nudgesEnabled by remember { mutableStateOf(Nudges.isEnabled(context)) }

    // ---- what he remembers, shown so it can be read and wiped --------------------------------
    var rememberedPeople by remember { mutableStateOf<List<Brain.Person>>(emptyList()) }
    var rememberedFacts by remember { mutableStateOf<List<Brain.Fact>>(emptyList()) }
    var rememberedTurns by remember { mutableIntStateOf(0) }
    LaunchedEffect(route) {
        if (route == ROUTE_ADVANCED) {
            rememberedPeople = Brain.people(context)
            rememberedFacts = Brain.facts(context)
            rememberedTurns = Brain.thread(context).size
        }
    }

    // ---- today, for the dashboard ------------------------------------------------------------
    var stepsToday by remember { mutableStateOf<Int?>(null) }
    var waterToday by remember { mutableIntStateOf(0) }
    var nextEvent by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route) {
        if (route == ROUTE_DASHBOARD) {
            stepsToday = HealthLog.stepsToday(context)
            waterToday = HealthLog.waterToday(context)
            nextEvent = try {
                CalendarBridge.next(context)?.let { event ->
                    val clock = CalendarBridge.clockOf(event.startMillis)
                    "${event.title} \u00B7 $clock"
                }
            } catch (t: Throwable) {
                null
            }
        }
    }

    // The phone's own lock, shown through the system's credential screen. Android tells JARVIS
    // only pass or fail; the PIN, pattern or fingerprint itself is never visible to the app.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            locked = false
            pinError = null
            LockGate.clearNotice(context)
        }
    }
    var pinEnabled by remember { mutableStateOf(Privacy.isEnabled(context)) }
    var bubbleEnabled by remember { mutableStateOf(Bubble.isEnabled(context)) }
    var bubbleAllowed by remember { mutableStateOf(Bubble.canDraw(context)) }
    var speechRoute by remember { mutableStateOf(VoiceSettings.route(context)) }
    var dockEditSlot by rememberSaveable { mutableIntStateOf(0) }

    // ---- apps + dock ----
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var dockApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isDockCustom by remember { mutableStateOf(DockPrefs.isCustom(context)) }

    // ---- launcher + notifications ----
    var isHomeApp by remember { mutableStateOf(LauncherStatus.isDefaultLauncher(context)) }
    var hasNotificationAccess by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    val notifications by NotificationRepository.notifications.collectAsState()

    // ---- controls ----
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(true) }
    var volumePercent by remember { mutableIntStateOf(0) }
    var mediaTitle by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    // ---- automation ----
    var workflows by remember { mutableStateOf<List<Workflow>>(emptyList()) }
    var editingWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var screenPressAllowed by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(AutomationTriggers.isArmed(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(AutomationTriggers.canScheduleExact(context)) }
    var notificationsAllowed by remember { mutableStateOf(canPostNotifications(context)) }
    var history by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    val runState by AutomationEngine.state.collectAsState()

    // Android 13+ gates our own notifications; armed automation needs it for the foreground chip.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsAllowed = granted }

    fun reloadAutomation() {
        workflows = AutomationStore.load(context)
        history = AutomationStore.history(context)
        armed = AutomationTriggers.isArmed(context)
        exactAlarmsAllowed = AutomationTriggers.canScheduleExact(context)
        notificationsAllowed = canPostNotifications(context)
    }

    // ---- quiet mode + privileged shell ----
    var dndAccess by remember { mutableStateOf(DndControl.hasAccess(context)) }
    var dndMode by remember { mutableStateOf(DndControl.current(context)) }
    val shizukuState by ShizukuShell.state.collectAsState()

    fun refreshQuietMode() {
        dndAccess = DndControl.hasAccess(context)
        dndMode = DndControl.current(context)
    }

    // ---- intelligence ----
    var aiConfig by remember { mutableStateOf(AiKeyStore.config(context)) }

    // Lock again after a minute in the background, and keep permission switches honest.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    leftAt = System.currentTimeMillis()
                    // A recording that outlives the visible app is both a battery cost and a privacy
                    // problem; the operator can simply tap the orb again on return.
                    if (VoiceController.state.value.phase == com.jarvis.assistant.voice.VoicePhase.LISTENING) {
                        VoiceController.cancel()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    bubbleAllowed = Bubble.canDraw(context)
                    val gateOn = Privacy.isEnabled(context) ||
                        LockGate.mode(context) == LockGate.Mode.PHONE_LOCK
                    if (gateOn && leftAt > 0 &&
                        System.currentTimeMillis() - leftAt > Privacy.LOCK_AFTER_MS
                    ) {
                        locked = true
                        biometricTried = false
                        biometricFailures = 0
                        route = ROUTE_HOME
                        if (LockGate.noticeEnabled(context)) {
                            scope.launch {
                                val steps = HealthLog.stepsToday(context)
                                val water = HealthLog.waterToday(context)
                                val unread = NotifyBrain.countUnreadMessages(context)
                                val lines = buildList {
                                    if (steps != null) add("$steps steps")
                                    if (water > 0) add("$water ml")
                                    if (unread > 0) add("$unread unread")
                                }
                                if (lines.isEmpty()) {
                                    LockGate.clearNotice(context)
                                } else {
                                    LockGate.postNotice(context, lines)
                                }
                            }
                        }
                    }
                    biometricAvailable = Biometrics.isAvailable(context)
                    leftAt = 0L
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The brief: said once per unlock, never during quiet hours, never twice in three hours. It runs
    // outside the lock overlay so it fires when the gate actually opens, not while it is drawn.
    LaunchedEffect(locked) {
        if (!locked) {
            scope.launch { Nudges.greet(context) }
        }
    }

    // Opened from the bubble or the widget: arm the voice surface once.
    LaunchedEffect(voiceArmed) {
        if (voiceArmed) {
            onVoiceConsumed()
            if (!locked) VoiceController.toggle(context)
        }
    }

    // Prevents overlapping app refreshes when several resume events arrive in quick succession.
    val refreshGate = remember { AtomicBoolean(false) }

    // ---- storage ----
    var storage by remember { mutableStateOf(readStorage(context)) }

    suspend fun refreshApps() {
        isLoadingApps = true
        val loaded = AppRepository.loadApps(context)
        installedApps = loaded
        dockApps = DockPrefs.resolve(context, loaded)
        isDockCustom = DockPrefs.isCustom(context)
        isLoadingApps = false
    }

    suspend fun refreshControls() {
        volumePercent = QuickControls.mediaVolumePercent(context)
        hasNotificationAccess = NotificationAccess.isGranted(context)
        if (hasNotificationAccess) {
            mediaTitle = QuickControls.mediaNowPlaying(context)
            isPlaying = QuickControls.isPlaying(context)
        } else {
            mediaTitle = null
            isPlaying = false
        }
    }

    LaunchedEffect(context) {
        refreshApps()
        torchAvailable = QuickControls.Torch.isAvailable(context)
        refreshControls()
        storage = readStorage(context)
        workflows = AutomationStore.load(context)
        accessibilityEnabled = AccessibilityStatus.isServiceEnabled(context)
        screenPressAllowed = AccessibilityStatus.isScreenPressAllowed(context)
        aiConfig = withContext(Dispatchers.IO) { AiKeyStore.config(context) }
        history = AutomationStore.history(context)
        notificationsAllowed = canPostNotifications(context)
        refreshQuietMode()
        // Only registers Shizuku listeners; if Shizuku is absent this is a no-op.
        ShizukuShell.attach(context)
        ShizukuShell.refresh(context)
    }

    // Refresh on every foreground pass: apps change, access can be revoked, media comes and goes.
    val isResumed = rememberIsResumed()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isHomeApp = LauncherStatus.isDefaultLauncher(context)
                accessibilityEnabled = AccessibilityStatus.isServiceEnabled(context)
                screenPressAllowed = AccessibilityStatus.isScreenPressAllowed(context)
                aiConfig = AiKeyStore.config(context)
                workflows = AutomationStore.load(context)
                history = AutomationStore.history(context)
                armed = AutomationTriggers.isArmed(context)
                exactAlarmsAllowed = AutomationTriggers.canScheduleExact(context)
                notificationsAllowed = canPostNotifications(context)
                refreshQuietMode()
                scope.launch { ShizukuShell.refresh(context) }
                if (refreshGate.compareAndSet(false, true)) {
                    scope.launch {
                        try {
                            refreshApps()
                            refreshControls()
                            storage = readStorage(context)
                        } finally {
                            refreshGate.set(false)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Media state is polled while anything is playing; cheap, and avoids holding a MediaController.
    LaunchedEffect(route, hasNotificationAccess, isResumed) {
        if (!hasNotificationAccess || !isResumed) return@LaunchedEffect
        while (true) {
            mediaTitle = QuickControls.mediaNowPlaying(context)
            isPlaying = QuickControls.isPlaying(context)
            delay(2_000)
        }
    }

    val launchApp: (InstalledApp) -> Unit = { app -> LauncherStatus.launch(context, app) }

    /**
     * Sets the interruption filter for real. Returns the system's answer through the recomposed
     * state; without the special grant it opens the one screen that can give it instead of failing
     * silently.
     */
    val applyDnd: (DndControl.Mode) -> Unit = { mode ->
        when (DndControl.set(context, mode)) {
            null -> QuickControls.openDndAccessSettings(context)
            else -> Unit
        }
        refreshQuietMode()
    }

    val shizukuAction: () -> Unit = {
        when (shizukuState.status) {
            ShizukuShell.Status.PERMISSION_NEEDED -> ShizukuShell.requestPermission(context)
            ShizukuShell.Status.NOT_RUNNING -> {
                val intent = ShizukuShell.launchIntent(context)
                if (intent != null) LauncherStatus.openSystemScreen(context, intent)
            }

            ShizukuShell.Status.NOT_INSTALLED -> {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (t: Throwable) {
                    // No browser installed; the status row already says what is missing.
                }
            }

            ShizukuShell.Status.READY -> Unit
        }
    }

    BackHandler(enabled = route != ROUTE_HOME) {
        route = if (route == ROUTE_ADVANCED) ROUTE_SETTINGS else ROUTE_HOME
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HudBackground()

        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState != ROUTE_HOME
                (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { if (forward) it / 12 else -it / 12 })
                    .togetherWith(
                        fadeOut(tween(140)) +
                            slideOutHorizontally(tween(200)) { if (forward) -it / 16 else it / 16 }
                    )
            },
            label = "route",
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) { current ->
            when (current) {
                ROUTE_HOME -> HomeScreen(
                    clock = clock,
                    status = status,
                    isHomeApp = isHomeApp,
                    appCount = installedApps.size,
                    notificationCount = notifications.size,
                    onOpenModules = { route = ROUTE_DASHBOARD },
                    onOpenDrawer = { route = ROUTE_DRAWER },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                )

                ROUTE_DASHBOARD -> DashboardScreen(
                    stepsToday = stepsToday,
                    waterToday = waterToday,
                    onAddWater = {
                        HealthLog.addWater(context, 250)
                        waterToday = HealthLog.waterToday(context)
                    },
                    nextEvent = nextEvent,
                    clock = clock,
                    status = status,
                    storage = storage,
                    device = device,
                    dockApps = dockApps,
                    appCount = installedApps.size,
                    isHomeApp = isHomeApp,
                    notificationCount = notifications.size,
                    isDockCustom = isDockCustom,
                    onLaunchApp = launchApp,
                    onEditDock = { slot ->
                        dockEditSlot = slot
                        route = ROUTE_DOCK
                    },
                    onOpenDrawer = { route = ROUTE_DRAWER },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                    onOpenModule = { module ->
                        route = if (module.id == HudModules.VOICE) ROUTE_HOME else module.id
                    },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                )

                ROUTE_TERMINAL -> TerminalScreen(
                    aiConfigured = aiConfig != null,
                    aiLabel = aiConfig?.label,
                    onOpenAiSettings = { route = ROUTE_AI },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                ROUTE_AI -> AiSettingsScreen(
                    onBack = {
                        scope.launch {
                            aiConfig = withContext(Dispatchers.IO) { AiKeyStore.config(context) }
                        }
                        route = ROUTE_SETTINGS
                    },
                )

                ROUTE_DRAWER -> AppDrawerScreen(
                    apps = installedApps,
                    isLoading = isLoadingApps,
                    onLaunch = launchApp,
                    onRefresh = { scope.launch { refreshApps() } },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                ROUTE_SETTINGS -> LauncherSettingsScreen(
                    appCount = installedApps.size,
                    notificationAccess = hasNotificationAccess,
                    isDockCustom = isDockCustom,
                    aiConfigured = aiConfig != null,
                    aiLabel = aiConfig?.label,
                    onEditDock = { dockEditSlot = 0; route = ROUTE_DOCK },
                    onOpenNotificationAccess = {
                        QuickControls.openNotificationAccessSettings(context)
                    },
                    onOpenIntelligence = { route = ROUTE_AI },
                    dndAccess = dndAccess,
                    dndLabel = DndControl.label(context),
                    shizuku = shizukuState,
                    onOpenDndAccess = { QuickControls.openDndAccessSettings(context) },
                    onShizukuAction = shizukuAction,
                    onOpenAdvanced = { route = ROUTE_ADVANCED },
                    pinEnabled = pinEnabled,
                    onBack = { route = ROUTE_DASHBOARD },
                )

                ROUTE_ADVANCED -> PrivacyScreen(
                    pinEnabled = pinEnabled,
                    bubbleEnabled = bubbleEnabled,
                    bubbleAllowed = bubbleAllowed,
                    speechRoute = speechRoute,
                    onSetPin = { pin ->
                        scope.launch {
                            val failure = withContext(Dispatchers.Default) { Privacy.setPin(context, pin) }
                            if (failure == null) {
                                pinEnabled = true
                                locked = false
                            }
                        }
                    },
                    onClearPin = {
                        Privacy.disable(context)
                        pinEnabled = false
                        locked = false
                    },
                    onToggleBubble = { enable ->
                        if (enable) {
                            val started = Bubble.start(context)
                            bubbleEnabled = started
                            if (!started) bubbleAllowed = Bubble.canDraw(context)
                        } else {
                            Bubble.stop(context)
                            bubbleEnabled = false
                        }
                    },
                    onOpenOverlaySettings = {
                        try {
                            context.startActivity(
                                Bubble.permissionIntent(context)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (t: Throwable) {
                            // Some OEMs hide this screen; the row already says it is not granted.
                        }
                    },
                    onSelectRoute = { picked ->
                        VoiceSettings.setRoute(context, picked)
                        speechRoute = picked
                    },
                    biometricStatus = Biometrics.availability(context),
                    biometricEnabled = biometricEnabled,
                    onToggleBiometric = { enable ->
                        Biometrics.setEnabled(context, enable)
                        biometricEnabled = enable
                        if (enable) biometricTried = false
                    },
                    onAddWidget = { requestWidget(context) },
                    phoneLockEnabled = lockMode == LockGate.Mode.PHONE_LOCK,
                    deviceLockAvailable = LockGate.deviceLockAvailable(context),
                    onSelectLockMode = { usePhoneLock ->
                        val mode = if (usePhoneLock) LockGate.Mode.PHONE_LOCK else LockGate.Mode.APP_PIN
                        LockGate.setMode(context, mode)
                        lockMode = mode
                        // Choosing the phone lock immediately proves it works.
                        if (usePhoneLock) LockGate.credentialIntent(context)?.let {
                            credentialLauncher.launch(it)
                        }
                    },
                    notifyBrainEnabled = notifyBrainEnabled,
                    onToggleNotifyBrain = { enable ->
                        NotifyBrain.setEnabled(context, enable)
                        notifyBrainEnabled = enable
                    },
                    roadRoutingEnabled = roadRoutingEnabled,
                    onToggleRoadRouting = { enable ->
                        Whereabouts.setRoadRouting(context, enable)
                        roadRoutingEnabled = enable
                    },
                    nudgesEnabled = nudgesEnabled,
                    onToggleNudges = { enable ->
                        Nudges.setEnabled(context, enable)
                        nudgesEnabled = enable
                    },
                    rememberedPeople = rememberedPeople,
                    rememberedFacts = rememberedFacts,
                    rememberedTurns = rememberedTurns,
                    onForgetMemory = {
                        Brain.forget(context)
                        rememberedPeople = emptyList()
                        rememberedFacts = emptyList()
                        rememberedTurns = 0
                    },
                    lockNoticeEnabled = lockNoticeEnabled,
                    onToggleLockNotice = { enable ->
                        LockGate.setNoticeEnabled(context, enable)
                        lockNoticeEnabled = enable
                    },
                    onBack = { route = ROUTE_SETTINGS },
                )

                ROUTE_DOCK -> DockPickerScreen(
                    apps = installedApps,
                    currentDock = dockApps,
                    isCustom = isDockCustom,
                    initialSlot = dockEditSlot,
                    onPick = { slot, app ->
                        if (app == null) {
                            DockPrefs.setSlot(context, slot, null)
                        } else {
                            DockPrefs.setSlot(context, slot, app)
                        }
                        scope.launch { refreshApps() }
                    },
                    onReset = {
                        DockPrefs.clear(context)
                        scope.launch { refreshApps() }
                    },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.APPS -> AppDrawerScreen(
                    apps = installedApps,
                    isLoading = isLoadingApps,
                    onLaunch = launchApp,
                    onRefresh = { scope.launch { refreshApps() } },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.NOTIFICATIONS -> NotificationsScreen(
                    notifications = notifications,
                    hasAccess = hasNotificationAccess,
                    onEnableAccess = { QuickControls.openNotificationAccessSettings(context) },
                    onDismiss = { item -> NotificationRepository.dismiss(item.key) },
                    onDismissAll = { NotificationRepository.dismissAllClearable() },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.CONTROLS -> ControlsScreen(
                    torchOn = torchOn,
                    torchAvailable = torchAvailable,
                    volumePercent = volumePercent,
                    mediaTitle = mediaTitle,
                    isPlaying = isPlaying,
                    hasNotificationAccess = hasNotificationAccess,
                    onToggleTorch = {
                        val result = QuickControls.Torch.toggle(context)
                        if (result == null) {
                            torchAvailable = false
                        } else {
                            torchOn = result
                        }
                    },
                    onVolumeDown = { volumePercent = QuickControls.adjustVolume(context, -1) },
                    onVolumeUp = { volumePercent = QuickControls.adjustVolume(context, +1) },
                    onTogglePlayback = {
                        QuickControls.togglePlayback(context)?.let { playing ->
                            isPlaying = playing
                        }
                        mediaTitle = QuickControls.mediaNowPlaying(context)
                    },
                    onNext = {
                        QuickControls.skipNext(context)
                        mediaTitle = QuickControls.mediaNowPlaying(context)
                    },
                    onPrevious = {
                        QuickControls.skipPrevious(context)
                        mediaTitle = QuickControls.mediaNowPlaying(context)
                    },
                    onOpenInternetPanel = { QuickControls.openInternetPanel(context) },
                    onOpenBluetoothSettings = { QuickControls.openBluetoothSettings(context) },
                    onOpenDndSettings = { QuickControls.openDndSettings(context) },
                    dndAccess = dndAccess,
                    dndMode = dndMode,
                    onSetDnd = applyDnd,
                    onOpenDndAccess = { QuickControls.openDndAccessSettings(context) },
                    onOpenDisplaySettings = { QuickControls.openDisplaySettings(context) },
                    onOpenNotificationAccess = {
                        QuickControls.openNotificationAccessSettings(context)
                    },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.NETWORK -> ModuleScaffold(
                    title = "NETWORK",
                    subtitle = "link telemetry",
                    accent = IceBlue,
                    statusLabel = "LIVE",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { NetworkScreen(status = status) }

                HudModules.POWER -> ModuleScaffold(
                    title = "POWER",
                    subtitle = "cell diagnostics",
                    accent = StatusGreen,
                    statusLabel = "LIVE",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { PowerScreen(status = status) }

                HudModules.DIAGNOSTICS -> ModuleScaffold(
                    title = "DIAGNOSTICS",
                    subtitle = "core telemetry",
                    accent = ElectricCyan,
                    statusLabel = "LIVE",
                    onBack = { route = ROUTE_DASHBOARD },
                ) {
                    DiagnosticsScreen(
                        status = status,
                        device = device,
                        clock = clock,
                        storage = storage,
                        aiLabel = aiConfig?.label,
                    )
                }

                HudModules.INSTAGRAM -> InstagramScreen(
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.CONNECT -> ConnectorsScreen(
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.KNOWLEDGE -> KnowledgeScreen(
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.VOICE -> HomeScreen(
                    clock = clock,
                    status = status,
                    isHomeApp = isHomeApp,
                    appCount = installedApps.size,
                    notificationCount = notifications.size,
                    onOpenModules = { route = ROUTE_DASHBOARD },
                    onOpenDrawer = { route = ROUTE_DRAWER },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                )

                HudModules.AUTOMATION -> AutomationScreen(
                    workflows = workflows,
                    runState = runState,
                    accessibilityEnabled = accessibilityEnabled,
                    screenPressAllowed = screenPressAllowed,
                    notificationAccess = hasNotificationAccess,
                    onRun = { workflow -> AutomationEngine.run(context, workflow) },
                    onStop = { AutomationEngine.stop() },
                    onActivateAll = {
                        // Everything JARVIS may switch on itself, then one system page.
                        if (accessibilityEnabled) {
                            if (!screenPressAllowed) {
                                AccessibilityStatus.setScreenPressAllowed(context, true)
                                screenPressAllowed = true
                            }
                            if (!hasNotificationAccess) {
                                QuickControls.openNotificationAccessSettings(context)
                            }
                        } else {
                            LauncherStatus.openSystemScreen(
                                context,
                                AccessibilityStatus.settingsIntent(),
                            )
                        }
                    },
                    onDismissFinished = { AutomationEngine.dismissFinished() },
                    armed = armed,
                    exactAlarmsAllowed = exactAlarmsAllowed,
                    notificationsAllowed = notificationsAllowed,
                    history = history,
                    onToggleEnabled = { workflow, enabled ->
                        AutomationStore.setEnabled(context, workflow.id, enabled)
                        AutomationTriggers.sync(context)
                        reloadAutomation()
                    },
                    onArm = { wantArmed ->
                        if (!wantArmed) {
                            AutomationTriggers.setArmed(context, false)
                            reloadAutomation()
                        } else if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                            // Ask first: without it the foreground chip is invisible and the
                            // service is harder to explain to the user looking at the shade.
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            AutomationTriggers.setArmed(context, true)
                            reloadAutomation()
                        } else {
                            AutomationTriggers.setArmed(context, true)
                            reloadAutomation()
                        }
                    },
                    onRequestExactAlarms = { AutomationTriggers.requestExactPermission(context) },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onCompose = { route = ROUTE_COMPOSE },
                    onClearHistory = {
                        AutomationStore.clearHistory(context)
                        history = AutomationStore.history(context)
                    },
                    onEdit = { workflow ->
                        editingWorkflow = workflow
                        route = ROUTE_ROUTINE
                    },
                    onNew = {
                        editingWorkflow = AutomationStore.newWorkflow()
                        route = ROUTE_ROUTINE
                    },
                    onDelete = { workflow ->
                        AutomationStore.delete(context, workflow.id)
                        AutomationTriggers.sync(context)
                        reloadAutomation()
                    },
                    onOpenAccessibility = {
                        LauncherStatus.openSystemScreen(context, AccessibilityStatus.settingsIntent())
                    },
                    onToggleScreenPress = { allowed ->
                        AccessibilityStatus.setScreenPressAllowed(context, allowed)
                        screenPressAllowed = allowed
                    },
                    onOpenNotificationAccess = {
                        QuickControls.openNotificationAccessSettings(context)
                    },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                ROUTE_COMPOSE -> ComposeScreen(
                    aiConfigured = aiConfig != null,
                    onSave = { planned ->
                        AutomationStore.upsert(context, planned)
                        AutomationTriggers.sync(context)
                        reloadAutomation()
                        editingWorkflow = planned
                        route = ROUTE_ROUTINE
                    },
                    onOpenAiSettings = { route = ROUTE_AI },
                    onBack = { route = ROUTE_DASHBOARD },
                )

                ROUTE_ROUTINE -> {
                    val draft = editingWorkflow
                    if (draft == null) {
                        LaunchedEffect(Unit) { route = ROUTE_DASHBOARD }
                    } else {
                        AutomationEditorScreen(
                            workflow = draft,
                            apps = installedApps,
                            runState = runState,
                            accessibilityEnabled = accessibilityEnabled,
                            screenPressAllowed = screenPressAllowed,
                            onTestStep = { step -> AutomationEngine.testStep(context, step) },
                            onStopTest = { AutomationEngine.stop() },
                            onActivateAccessibility = {
                                LauncherStatus.openSystemScreen(
                                    context,
                                    AccessibilityStatus.settingsIntent(),
                                )
                            },
                            onAllowScreenPress = {
                                AccessibilityStatus.setScreenPressAllowed(context, true)
                                screenPressAllowed = true
                            },
                            onChange = { updated -> editingWorkflow = updated },
                            onSave = {
                                AutomationStore.upsert(context, draft)
                                AutomationTriggers.sync(context)
                                reloadAutomation()
                                editingWorkflow = null
                                route = ROUTE_DASHBOARD
                            },
                            onBack = {
                                editingWorkflow = null
                                route = ROUTE_DASHBOARD
                            },
                        )
                    }
                }

                else -> DashboardScreen(
                    clock = clock,
                    status = status,
                    storage = storage,
                    device = device,
                    dockApps = dockApps,
                    appCount = installedApps.size,
                    isHomeApp = isHomeApp,
                    notificationCount = notifications.size,
                    stepsToday = stepsToday,
                    waterToday = waterToday,
                    onAddWater = {
                        HealthLog.addWater(context, 250)
                        waterToday = HealthLog.waterToday(context)
                    },
                    nextEvent = nextEvent,
                    isDockCustom = isDockCustom,
                    onLaunchApp = launchApp,
                    onEditDock = { slot -> dockEditSlot = slot; route = ROUTE_DOCK },
                    onOpenDrawer = { route = ROUTE_DRAWER },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                    onOpenModule = { module -> route = module.id },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                )
            }
        }

        // The privacy gate covers everything, including a suspended routine: an unlocked screen is
        // the only place JARVIS may be read.
        if (locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VoidBlack)
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                // The prompt outlives the composable if the screen goes away (a PIN unlocking the
                // app while the sheet is still up, or the process being backgrounded), so the signal
                // is held and cancelled on the way out instead of leaving an orphan system dialog.
                var biometricSignal by remember { mutableStateOf<CancellationSignal?>(null) }

                fun offerBiometric() {
                    val activity = context as? android.app.Activity ?: return
                    if (biometricPending) return
                    biometricPending = true
                    biometricTried = true
                    biometricSignal = Biometrics.prompt(activity) { outcome ->
                        biometricPending = false
                        biometricSignal = null
                        when (outcome) {
                            Biometrics.Outcome.SUCCESS -> {
                                pinError = null
                                biometricFailures = 0
                                locked = false
                            }
                            // Choosing the keypad is not a sensor failure, so it must not burn one
                            // of the automatic attempts.
                            Biometrics.Outcome.CANCELLED -> Unit
                            Biometrics.Outcome.FAILED -> biometricFailures += 1
                        }
                    }
                }

                DisposableEffect(locked) {
                    onDispose { Biometrics.cancel(biometricSignal) }
                }

                // PHONE LOCK: the real system credential screen, once per lock appearance. If the
                // operator backs out, the keypad is still there underneath.
                LaunchedEffect(locked, lockMode) {
                    if (locked && lockMode == LockGate.Mode.PHONE_LOCK) {
                        LockGate.credentialIntent(context)?.let { credentialLauncher.launch(it) }
                    }
                }

                // One automatic attempt per lock appearance; after that only the button fires it.
                LaunchedEffect(locked, biometricFailures) {
                    if (locked && !biometricTried &&
                        BiometricPolicy.shouldAutoPrompt(biometricEnabled, biometricAvailable, biometricFailures)
                    ) {
                        offerBiometric()
                    }
                }

                LockScreen(
                    verifying = pinChecking,
                    error = pinError,
                    systemUnlockOnly = lockMode == LockGate.Mode.PHONE_LOCK && !Privacy.isEnabled(context),
                    onSystemUnlock = {
                        LockGate.credentialIntent(context)?.let { credentialLauncher.launch(it) }
                    },
                    biometricOffered = BiometricPolicy.canOffer(biometricEnabled, biometricAvailable),
                    biometricPending = biometricPending,
                    onUsePin = {
                        // Dismiss the sheet so the keypad is actually usable.
                        biometricTried = true
                        Biometrics.cancel(biometricSignal)
                        biometricSignal = null
                        biometricPending = false
                    },
                    onBiometric = { offerBiometric() },
                    onSubmit = { pin ->
                        if (!pinChecking) {
                            pinChecking = true
                            pinError = null
                            scope.launch {
                                // PBKDF2 at 120k iterations belongs nowhere near the main thread.
                                val ok = withContext(Dispatchers.Default) { Privacy.verify(context, pin) }
                                pinChecking = false
                                if (ok) {
                                    pinError = null
                                    locked = false
                                    LockGate.clearNotice(context)
                                } else {
                                    pinError = "Wrong PIN"
                                }
                            }
                        }
                    },
                )
            }
        }

        // A suspended routine must stay answerable from any screen. Drawn last so it sits above
        // the route content, including the automation screen.
        (runState as? RunState.AwaitingConfirmation)?.let { pending ->
            ConfirmationOverlay(
                workflowName = pending.workflowName,
                actionLabel = pending.actionLabel,
                detail = pending.detail,
                stepIndex = pending.stepIndex,
                stepCount = pending.stepCount,
                onApprove = { AutomationEngine.confirm(true) },
                onDecline = { AutomationEngine.confirm(false) },
            )
        }
    }
}

/**
 * Asks the launcher to pin the JARVIS widget.
 *
 * Android 8+ supports the system prompt; older launchers (and some OEM ones) silently refuse, in which
 * case the existing widgets are simply refreshed and the operator can add it by hand.
 */
private fun requestWidget(context: android.content.Context) {
    try {
        val manager = android.appwidget.AppWidgetManager.getInstance(context) ?: return
        val provider = android.content.ComponentName(context, JarvisWidget::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        }
        JarvisWidget.refresh(context)
    } catch (t: Throwable) {
        // Launcher does not support pinning; nothing else to do.
    }
}
