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
import androidx.compose.runtime.getValue
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
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.HudModules
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.device.LauncherStatus
import com.jarvis.assistant.device.rememberDeviceInfo
import com.jarvis.assistant.device.rememberSystemStatus
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.rememberClock
import com.jarvis.assistant.ui.screens.AppDrawerScreen
import com.jarvis.assistant.ui.screens.AutomationScreen
import com.jarvis.assistant.ui.screens.DashboardScreen
import com.jarvis.assistant.ui.screens.DiagnosticsScreen
import com.jarvis.assistant.ui.screens.LauncherSettingsScreen
import com.jarvis.assistant.ui.screens.ModuleScaffold
import com.jarvis.assistant.ui.screens.NetworkScreen
import com.jarvis.assistant.ui.screens.PowerScreen
import com.jarvis.assistant.ui.screens.TerminalScreen
import com.jarvis.assistant.ui.screens.VoiceScreen
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import kotlinx.coroutines.launch

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TERMINAL = "terminal"
private const val ROUTE_DRAWER = "drawer"
private const val ROUTE_SETTINGS = "settings"

/**
 * Application shell.
 *
 * Phase 2 adds the app drawer and launcher settings, plus the state a Home app needs: the
 * installed-app index (refreshed when the app returns to the foreground, since apps get
 * installed and uninstalled while it is backgrounded) and whether JARVIS currently owns the
 * Home role.
 *
 * Navigation stays a small string route + [AnimatedContent] — eight leaf destinations do not
 * justify a navigation library.
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

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var isHomeApp by remember { mutableStateOf(LauncherStatus.isDefaultLauncher(context)) }

    suspend fun refreshApps() {
        isLoadingApps = true
        val loaded = AppRepository.loadApps(context)
        installedApps = loaded
        isLoadingApps = false
    }

    LaunchedEffect(context) { refreshApps() }

    // Re-index and re-check the Home role every time the shell comes back to the foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isHomeApp = LauncherStatus.isDefaultLauncher(context)
                scope.launch { refreshApps() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val dockApps = remember(installedApps) { AppRepository.dock(installedApps) }

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
                    device = device,
                    dockApps = dockApps,
                    appCount = installedApps.size,
                    isHomeApp = isHomeApp,
                    onLaunchApp = launchApp,
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
                    onBack = { route = ROUTE_DASHBOARD },
                )

                HudModules.APPS -> AppDrawerScreen(
                    apps = installedApps,
                    isLoading = isLoadingApps,
                    onLaunch = launchApp,
                    onRefresh = { scope.launch { refreshApps() } },
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
                ) { DiagnosticsScreen(status = status, device = device, clock = clock) }

                HudModules.VOICE -> ModuleScaffold(
                    title = "VOICE",
                    subtitle = "speech interface · phase 4",
                    accent = Amber,
                    phaseLabel = "P4",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { VoiceScreen() }

                HudModules.AUTOMATION -> ModuleScaffold(
                    title = "AUTOMATION",
                    subtitle = "task control · phase 6",
                    accent = AlertRed,
                    phaseLabel = "P6",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { AutomationScreen() }

                else -> DashboardScreen(
                    clock = clock,
                    status = status,
                    device = device,
                    dockApps = dockApps,
                    appCount = installedApps.size,
                    isHomeApp = isHomeApp,
                    onLaunchApp = launchApp,
                    onOpenDrawer = { route = ROUTE_DRAWER },
                    onOpenSettings = { route = ROUTE_SETTINGS },
                    onOpenModule = { module -> route = module.id },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                )
            }
        }
    }
}
