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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.assistant.data.HudModules
import com.jarvis.assistant.device.rememberDeviceInfo
import com.jarvis.assistant.device.rememberSystemStatus
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.components.rememberClock
import com.jarvis.assistant.ui.screens.AppsScreen
import com.jarvis.assistant.ui.screens.AutomationScreen
import com.jarvis.assistant.ui.screens.DashboardScreen
import com.jarvis.assistant.ui.screens.DiagnosticsScreen
import com.jarvis.assistant.ui.screens.NetworkScreen
import com.jarvis.assistant.ui.screens.PowerScreen
import com.jarvis.assistant.ui.screens.TerminalScreen
import com.jarvis.assistant.ui.screens.VoiceScreen

private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_TERMINAL = "terminal"

/**
 * Application shell.
 *
 * Phase 1 uses a tiny string route + [AnimatedContent] instead of a navigation library: there
 * are only eight destinations, they are all leaves, and this keeps the dependency surface at
 * zero until the launcher phase needs deep links.
 */
@Composable
fun JarvisApp() {
    val clock = rememberClock()
    val status = rememberSystemStatus()
    val device = rememberDeviceInfo()

    var route by rememberSaveable { mutableStateOf(ROUTE_DASHBOARD) }

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
                    onOpenModule = { module -> route = module.id },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                )

                ROUTE_TERMINAL -> TerminalScreen(onBack = { route = ROUTE_DASHBOARD })

                HudModules.APPS -> ModuleRoute(
                    title = "APPS",
                    subtitle = "launcher grid · phase 2",
                    accent = com.jarvis.assistant.ui.theme.ElectricCyan,
                    phaseLabel = "P2",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { AppsScreen() }

                HudModules.NETWORK -> ModuleRoute(
                    title = "NETWORK",
                    subtitle = "link telemetry",
                    accent = com.jarvis.assistant.ui.theme.IceBlue,
                    phaseLabel = "P3",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { NetworkScreen(status = status) }

                HudModules.POWER -> ModuleRoute(
                    title = "POWER",
                    subtitle = "cell diagnostics",
                    accent = com.jarvis.assistant.ui.theme.StatusGreen,
                    phaseLabel = "P3",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { PowerScreen(status = status) }

                HudModules.DIAGNOSTICS -> ModuleRoute(
                    title = "DIAGNOSTICS",
                    subtitle = "core telemetry",
                    accent = com.jarvis.assistant.ui.theme.ElectricCyan,
                    phaseLabel = "P1",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { DiagnosticsScreen(status = status, device = device, clock = clock) }

                HudModules.VOICE -> ModuleRoute(
                    title = "VOICE",
                    subtitle = "speech interface · phase 4",
                    accent = com.jarvis.assistant.ui.theme.Amber,
                    phaseLabel = "P4",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { VoiceScreen() }

                HudModules.AUTOMATION -> ModuleRoute(
                    title = "AUTOMATION",
                    subtitle = "task control · phase 6",
                    accent = com.jarvis.assistant.ui.theme.AlertRed,
                    phaseLabel = "P6",
                    onBack = { route = ROUTE_DASHBOARD },
                ) { AutomationScreen() }

                else -> DashboardScreen(
                    clock = clock,
                    status = status,
                    device = device,
                    onOpenModule = { module -> route = module.id },
                    onOpenTerminal = { route = ROUTE_TERMINAL },
                )
            }
        }
    }
}

@Composable
private fun ModuleRoute(
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    phaseLabel: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    com.jarvis.assistant.ui.screens.ModuleScaffold(
        title = title,
        subtitle = subtitle,
        accent = accent,
        phaseLabel = phaseLabel,
        onBack = onBack,
        content = content,
    )
}
