package com.jarvis.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jarvis.assistant.automation.AccessibilityStatus
import com.jarvis.assistant.automation.AutomationEngine
import com.jarvis.assistant.automation.AutomationStore
import com.jarvis.assistant.automation.Workflow
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.DockPrefs
import com.jarvis.assistant.data.HudModules
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.device.LauncherStatus
import com.jarvis.assistant.device.NotificationAccess
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.device.QuickControls
import com.jarvis.assistant.device.readStorage
import com.jarvis.assistant.device.rememberDeviceInfo
import com.jarvis.assistant.device.rememberSystemStatus
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.rememberClock
import com.jarvis.assistant.ui.screens.AppDrawerScreen
import com.jarvis.assistant.ui.screens.AutomationEditorScreen
import com.jarvis.assistant.ui.screens.AutomationScreen
import com.jarvis.assistant.ui.screens.ControlsScreen
import com.jarvis.assistant.ui.screens.DashboardScreen
import com.jarvis.assistant.ui.screens.DiagnosticsScreen
import com.jarvis.assistant.ui.screens.DockPickerScreen
import com.jarvis.assistant.ui.screens.LauncherSettingsScreen
import com.jarvis.assistant.ui.screens.ModuleScaffold
import com.jarvis.assistant.ui.screens.NetworkScreen
import com.jarvis.assistant.ui.screens.NotificationsScreen
import com.jarvis.assistant.ui.screens.PowerScreen
import com.jarvis.assistant.ui.screens.TerminalScreen
import com.jarvis.assistant.ui.screens.VoiceScreen
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TERMINAL = "terminal"
private const val ROUTE_DRAWER = "drawer"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DOCK = "dock"
private const val ROUTE_ROUTINE = "routine"

/**
 * Application shell.
 *
 * Owns every piece of device state the HUD renders — installed apps, the persisted dock, the Home
 * role, notification access and the live notification list, plus the phase 3 control state (torch,
 * volume, media). Screens stay stateless so they can be previewed and reasoned about in isolation.
 */
@Composable
fun JarvisApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val clock = rememberClock()
    val status = rememberSystemStatus()
    val device = rememberDeviceInfo()

    var route by rememberSaveable { mutableStateOf(ROUTE_DASHBOARD) }
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
    val runState by AutomationEngine.state.collectAsState()

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
    }

    // Refresh on every foreground pass: apps change, access can be revoked, media comes and goes.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isHomeApp = LauncherStatus.isDefaultLauncher(context)
                accessibilityEnabled = AccessibilityStatus.isServiceEnabled(context)
                screenPressAllowed = AccessibilityStatus.isScreenPressAllowed(context)
                scope.launch {
                    refreshApps()
                    refreshControls()
                    storage = readStorage(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Media state is polled while anything is playing; cheap, and avoids holding a MediaController.
    LaunchedEffect(route, hasNotificationAccess) {
        if (!hasNotificationAccess) return@LaunchedEffect
        while (true) {
            mediaTitle = QuickControls.mediaNowPlaying(context)
            isPlaying = QuickControls.isPlaying(context)
            delay(2_000)
        }
    }

    val launchApp: (InstalledApp) -> Unit = { app -> LauncherStatus.launch(context, app) }

    BackHandler(enabled = route != ROUTE_DASHBOARD) { route = ROUTE_DASHBOARD }

    Box(modifier = Modifier.fillMaxSize()) {
        HudBackground()

        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState != ROUTE_DASHBOARD
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
                ROUTE_DASHBOARD -> DashboardScreen(
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
                    onOpenModule = { module -> route = module.id },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                )

                ROUTE_TERMINAL -> TerminalScreen(onBack = { route = ROUTE_DASHBOARD })

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
                    onEditDock = { dockEditSlot = 0; route = ROUTE_DOCK },
                    onOpenNotificationAccess = {
                        QuickControls.openNotificationAccessSettings(context)
                    },
                    onBack = { route = ROUTE_DASHBOARD },
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
                    phaseLabel = "P3",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { NetworkScreen(status = status) }

                HudModules.POWER -> ModuleScaffold(
                    title = "POWER",
                    subtitle = "cell diagnostics",
                    accent = StatusGreen,
                    phaseLabel = "P3",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { PowerScreen(status = status) }

                HudModules.DIAGNOSTICS -> ModuleScaffold(
                    title = "DIAGNOSTICS",
                    subtitle = "core telemetry",
                    accent = ElectricCyan,
                    phaseLabel = "P1",
                    onBack = { route = ROUTE_DASHBOARD },
                ) {
                    DiagnosticsScreen(
                        status = status,
                        device = device,
                        clock = clock,
                        storage = storage,
                    )
                }

                HudModules.VOICE -> ModuleScaffold(
                    title = "VOICE",
                    subtitle = "speech interface · phase 4",
                    accent = Amber,
                    phaseLabel = "P4",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { VoiceScreen() }

                HudModules.AUTOMATION -> AutomationScreen(
                    workflows = workflows,
                    runState = runState,
                    accessibilityEnabled = accessibilityEnabled,
                    screenPressAllowed = screenPressAllowed,
                    notificationAccess = hasNotificationAccess,
                    onRun = { workflow -> AutomationEngine.run(context, workflow) },
                    onStop = { AutomationEngine.stop() },
                    onConfirm = { approved -> AutomationEngine.confirm(approved) },
                    onDismissFinished = { AutomationEngine.dismissFinished() },
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
                        workflows = AutomationStore.load(context)
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

                ROUTE_ROUTINE -> {
                    val draft = editingWorkflow
                    if (draft == null) {
                        LaunchedEffect(Unit) { route = ROUTE_DASHBOARD }
                    } else {
                        AutomationEditorScreen(
                            workflow = draft,
                            apps = installedApps,
                            onChange = { updated -> editingWorkflow = updated },
                            onSave = {
                                AutomationStore.upsert(context, draft)
                                workflows = AutomationStore.load(context)
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
    }
}
