package com.jarvis.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.assistant.device.SystemStatus
import com.jarvis.assistant.data.HudModule
import com.jarvis.assistant.data.HudModules
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.components.ClockSnapshot
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.MicroReading
import com.jarvis.assistant.ui.components.MicroStrip
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.components.RingNav
import com.jarvis.assistant.ui.components.VoiceTaskCard
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.voice.Contacts
import com.jarvis.assistant.voice.VoiceController
import com.jarvis.assistant.voice.VoicePhase
import com.jarvis.assistant.voice.VoiceTask

/**
 * The focus home: a top bar, one orb, nothing else.
 *
 * The tiles, telemetry, dock and system log all still exist — they moved behind the grid button in
 * the top bar, so this screen never has to scroll or lay out a dozen panels just to show the time.
 * Tapping the orb starts listening; tapping it again stops and runs the turn; the card that appears
 * over it is the only thing that grows while JARVIS works.
 */
@Composable
fun HomeScreen(
    clock: ClockSnapshot,
    status: SystemStatus,
    isHomeApp: Boolean,
    appCount: Int,
    notificationCount: Int,
    onOpenModules: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModule: (HudModule) -> Unit,
) {
    val context = LocalContext.current
    val task by VoiceController.state.collectAsState()

    var contactsGranted by remember { mutableStateOf(granted(context, Manifest.permission.READ_CONTACTS)) }
    var callsGranted by remember { mutableStateOf(granted(context, Manifest.permission.CALL_PHONE)) }
    var cameraGranted by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) {
            VoiceController.toggle(context)
        } else {
            VoiceController.note(
                context,
                "Microphone permission is off. Allow it, then tap the orb again.",
                "MICROPHONE",
            )
        }
    }

    val contactAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        contactsGranted = allowed
        if (allowed) Contacts.invalidate()
    }

    val callAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        callsGranted = allowed
    }

    val cameraAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        cameraGranted = allowed
    }

    fun tapOrb() {
        if (VoiceController.hasMicrophone(context)) {
            VoiceController.toggle(context)
        } else {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))

            TopBar(
                isHomeApp = isHomeApp,
                appCount = appCount,
                notificationCount = notificationCount,
                onOpenDrawer = onOpenDrawer,
                onOpenModules = onOpenModules,
                onOpenSettings = onOpenSettings,
            )

            Spacer(Modifier.weight(1f))

            OrbBlock(
                clock = clock,
                status = status,
                task = task,
                notificationCount = notificationCount,
                onTap = { tapOrb() },
                onOpenModule = onOpenModule,
            )

            Spacer(Modifier.height(12.dp))
            PhaseHint(task)

            Spacer(Modifier.height(10.dp))
            MicroStrip(
                readings = buildList {
                    add(MicroReading("BAT", "${status.battery.percent}%",
                        if (status.battery.isCharging) StatusGreen else IceBlue))
                    add(MicroReading("MEM", "${status.memory.percent}%"))
                    add(MicroReading("LINK", status.network.transport,
                        if (status.network.connected) ElectricCyan else AlertRed))
                    add(MicroReading("MOD", "${HudModules.all.count { it.isLive }}",
                        IceBlue))
                },
                leadingDot = if (task.phase == VoicePhase.LISTENING) Amber else StatusGreen,
            )

            Spacer(Modifier.weight(0.7f))

            if (!contactsGranted || !callsGranted || !cameraGranted) {
                PermissionStrip(
                    contactsGranted = contactsGranted,
                    callsGranted = callsGranted,
                    cameraGranted = cameraGranted,
                    onContacts = { contactAccess.launch(Manifest.permission.READ_CONTACTS) },
                    onCalls = { callAccess.launch(Manifest.permission.CALL_PHONE) },
                    onCamera = { cameraAccess.launch(Manifest.permission.CAMERA) },
                )
                Spacer(Modifier.height(10.dp))
            }

            Text(
                text = "J.A.R.V.I.S. \u00B7 CORE INTERFACE",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 2.4.sp),
                color = TextMuted.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(Modifier.height(14.dp))
        }

        AnimatedVisibility(
            visible = task.visible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 18.dp, end = 18.dp, bottom = 74.dp),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            VoiceTaskCard(
                task = task,
                onCancel = { VoiceController.cancel() },
                onDismiss = { VoiceController.dismiss() },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun TopBar(
    isHomeApp: Boolean,
    appCount: Int,
    notificationCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenModules: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "J.A.R.V.I.S.",
            style = HudType.value.copy(fontSize = 13.sp, letterSpacing = 4.sp),
            color = TextPrimary,
        )
        Spacer(Modifier.width(8.dp))
        PulseDot(color = ElectricCyan, diameter = 5)
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isHomeApp) "LAUNCHER" else "$appCount APPS",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BarButton(Icons.Outlined.Apps, "App drawer", notificationCount, onOpenDrawer)
        Spacer(Modifier.width(8.dp))
        BarButton(Icons.Outlined.GridView, "System modules", 0, onOpenModules)
        Spacer(Modifier.width(8.dp))
        BarButton(Icons.Outlined.Settings, "Settings", 0, onOpenSettings)
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    badge: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(PanelNavy.copy(alpha = 0.92f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.22f), ChipShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = ElectricCyan.copy(alpha = 0.85f),
            modifier = Modifier.size(17.dp),
        )
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(5.dp)
                    .background(Amber, CircleShape),
            )
        }
    }
}

@Composable
private fun OrbBlock(
    clock: ClockSnapshot,
    status: SystemStatus,
    task: VoiceTask,
    notificationCount: Int,
    onTap: () -> Unit,
    onOpenModule: (HudModule) -> Unit,
) {
    val accent = when (task.phase) {
        VoicePhase.LISTENING -> Amber
        VoicePhase.FAILED -> AlertRed
        VoicePhase.DONE -> StatusGreen
        else -> ElectricCyan
    }
    val interaction = remember { MutableInteractionSource() }
    val battery = status.battery

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // The dial is the hero now: the reactor shrinks to eighty per cent of it and the modules
        // take the ring, so opening one is a single gesture from the home screen.
        val diameter: Dp = minOf(maxWidth * 0.94f, 344.dp)

        Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        ArcReactor(
            modifier = Modifier.clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
            ),
            diameter = diameter * 0.80f,
            powerLevel = battery.percent,
            isCharging = battery.isCharging,
            accent = accent,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = clock.time, style = HudType.clock, color = TextPrimary)
                    Column(modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                        Text(
                            text = clock.meridiem,
                            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
                            color = IceBlue.copy(alpha = 0.85f),
                        )
                        Text(
                            text = clock.seconds,
                            style = HudType.valueSmall.copy(fontSize = 11.sp),
                            color = TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = clock.date,
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.6.sp),
                    color = TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(
                        color = if (task.phase == VoicePhase.LISTENING) Amber else accent,
                        diameter = 4,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (task.phase) {
                            VoicePhase.LISTENING -> "LISTENING"
                            VoicePhase.THINKING -> "THINKING"
                            VoicePhase.RUNNING -> "WORKING"
                            VoicePhase.FAILED -> "FAULT"
                            else -> "POWER ${battery.percent}%"
                        },
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                        color = if (task.phase == VoicePhase.LISTENING) Amber else IceBlue,
                    )
                }
            }
        }

        // Drawn after the reactor, so these ten touch targets sit on top of it. They cover only the
        // ring, which leaves the centre — the microphone — untouched.
        RingNav(
            modules = HudModules.all,
            onOpen = onOpenModule,
            badges = if (notificationCount > 0) {
                mapOf(HudModules.NOTIFICATIONS to notificationCount.toString())
            } else {
                emptyMap()
            },
        )
        }
    }
}

@Composable
private fun PhaseHint(task: VoiceTask) {
    val text = when (task.phase) {
        VoicePhase.LISTENING -> "SPEAK NOW \u00B7 TAP TO STOP"
        VoicePhase.THINKING -> "PLANNING"
        VoicePhase.RUNNING -> "EXECUTING"
        VoicePhase.DONE, VoicePhase.FAILED -> "TAP THE ORB TO SPEAK AGAIN"
        else -> "TAP THE ORB TO SPEAK"
    }
    val color = when (task.phase) {
        VoicePhase.LISTENING -> Amber
        VoicePhase.FAILED -> AlertRed
        VoicePhase.DONE -> StatusGreen
        else -> IceBlue.copy(alpha = 0.8f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelNavy.copy(alpha = 0.55f), ChipShape)
            .border(1.dp, color.copy(alpha = 0.18f), ChipShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color = color, diameter = 5)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 2.sp),
            color = color,
        )
    }
}

@Composable
private fun PermissionStrip(
    contactsGranted: Boolean,
    callsGranted: Boolean,
    cameraGranted: Boolean,
    onContacts: () -> Unit,
    onCalls: () -> Unit,
    onCamera: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!contactsGranted) {
            Box(modifier = Modifier.clickable(onClick = onContacts)) {
                HudChip(text = "+ CONTACTS", accent = Amber)
            }
        }
        if (!callsGranted) {
            Box(modifier = Modifier.clickable(onClick = onCalls)) {
                HudChip(text = "+ CALLS", accent = Amber)
            }
        }
        if (!cameraGranted) {
            Box(modifier = Modifier.clickable(onClick = onCamera)) {
                HudChip(text = "+ CAMERA", accent = Amber)
            }
        }
    }
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
