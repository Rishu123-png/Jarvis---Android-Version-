package com.jarvis.assistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.jarvis.assistant.ui.theme.PanelNavy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.device.LauncherStatus
import com.jarvis.assistant.shell.ShizukuShell
import com.jarvis.assistant.voice.AssistantRole
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary

/**
 * Launcher settings: shows whether JARVIS currently owns the Home role, and offers the two
 * supported ways to change it.
 *
 * Becoming the Home app is always an explicit, user-initiated action. Nothing here fires on
 * launch, because a misconfigured Home app can leave a phone without a working home screen.
 */
@Composable
fun LauncherSettingsScreen(
    appCount: Int,
    notificationAccess: Boolean,
    isDockCustom: Boolean,
    aiConfigured: Boolean,
    aiLabel: String?,
    onEditDock: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenIntelligence: () -> Unit,
    dndAccess: Boolean,
    dndLabel: String,
    shizuku: ShizukuShell.State,
    onOpenDndAccess: () -> Unit,
    onShizukuAction: () -> Unit,
    onOpenAdvanced: () -> Unit,
    pinEnabled: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isHome by remember { mutableStateOf(LauncherStatus.isDefaultLauncher(context)) }

    // The assistant seat. Nothing is requested here until the button is pressed: the role is the
    // operator's to give, and a phone that quietly claimed it would be the opposite of trustworthy.
    var assistantState by remember { mutableStateOf(AssistantRole.state(context)) }
    val assistantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result code is not reliable across OEMs; re-read the truth from RoleManager instead.
        assistantState = AssistantRole.state(context)
    }

    // Re-check whenever the screen comes back to the foreground (e.g. after the system dialog).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isHome = LauncherStatus.isDefaultLauncher(context)
                assistantState = AssistantRole.state(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ModuleScaffold(
        title = "LAUNCHER",
        subtitle = "home app configuration",
        accent = if (isHome) StatusGreen else ElectricCyan,
        statusLabel = "LIVE",
        onBack = onBack,
    ) {
        HudPanel(
            label = "ADVANCED",
            accent = Amber,
            trailing = { HudChip(text = if (pinEnabled) "LOCKED" else "OPEN", accent = if (pinEnabled) StatusGreen else Amber) },
        ) {
            Text(
                text = "Privacy lock, floating bubble, speech engine and the home-screen widget.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .background(PanelNavy.copy(alpha = 0.9f), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.28f), ChipShape)
                        .clickable(onClick = onOpenAdvanced)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "OPEN ADVANCED SETTINGS",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                        color = ElectricCyan,
                    )
                }
            }
        }

        HudPanel(
            label = "HOME ROLE",
            accent = if (isHome) StatusGreen else ElectricCyan,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = if (isHome) StatusGreen else AlertRed, diameter = 4)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isHome) "ACTIVE" else "INACTIVE",
                        style = HudType.label.copy(fontSize = 8.sp),
                        color = if (isHome) StatusGreen else AlertRed,
                    )
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isHome) Icons.Outlined.CheckCircle else Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = if (isHome) StatusGreen else ElectricCyan,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isHome) "JARVIS IS YOUR HOME APP" else "JARVIS IS NOT THE HOME APP",
                        style = HudType.value.copy(fontSize = 12.sp, letterSpacing = 1.2.sp),
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (isHome) {
                            "Pressing Home opens this dashboard."
                        } else {
                            "Pressing Home still opens your current launcher."
                        },
                        style = HudType.terminal.copy(fontSize = 10.sp),
                        color = TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (!isHome) {
                ActionButton(
                    label = "SET JARVIS AS HOME",
                    icon = Icons.Outlined.CheckCircle,
                    accent = ElectricCyan,
                    filled = true,
                    onClick = {
                        val roleIntent = LauncherStatus.homeRoleRequestIntent(context)
                        if (roleIntent != null) {
                            LauncherStatus.openSystemScreen(context, roleIntent)
                        } else {
                            LauncherStatus.openSystemScreen(context, LauncherStatus.homeSettingsIntent())
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            ActionButton(
                label = "OPEN SYSTEM HOME SETTINGS",
                icon = Icons.Outlined.OpenInNew,
                accent = IceBlue,
                filled = false,
                onClick = {
                    LauncherStatus.openSystemScreen(context, LauncherStatus.homeSettingsIntent())
                },
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Android shows its own confirmation dialog. JARVIS never switches the Home " +
                    "app on its own, and the choice can be reverted at any time from the same " +
                    "system settings screen.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted.copy(alpha = 0.9f),
            )
        }

        HudPanel(
            label = "INTELLIGENCE",
            accent = if (aiConfigured) StatusGreen else Amber,
        ) {
            ReadoutRow(
                label = "PROVIDER",
                value = aiLabel?.uppercase() ?: "NOT CONFIGURED",
                valueColor = if (aiConfigured) StatusGreen else Amber,
            )
            ReadoutRow(
                label = "KEY STORAGE",
                value = "ENCRYPTED · KEYSTORE",
                valueColor = StatusGreen,
            )
            ReadoutRow(label = "TRAFFIC", value = "DIRECT TO PROVIDER")
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Bring your own key: any supported provider works. The key is encrypted " +
                    "with the Android Keystore and sent only to that provider.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = if (aiConfigured) "MANAGE API KEY" else "ADD API KEY",
                icon = Icons.Outlined.Key,
                accent = if (aiConfigured) StatusGreen else ElectricCyan,
                filled = false,
                onClick = onOpenIntelligence,
            )
        }

        HudPanel(label = "LAUNCHER STATUS") {
            ReadoutRow(
                label = "HOME ROLE",
                value = if (isHome) "HELD BY JARVIS" else "HELD BY ANOTHER APP",
                valueColor = if (isHome) StatusGreen else TextSecondary,
            )
            ReadoutRow(label = "LAUNCHABLE APPS", value = "$appCount INDEXED")
            ReadoutRow(label = "APP DRAWER", value = "LIVE", valueColor = StatusGreen)
            ReadoutRow(label = "APP LAUNCHING", value = "LIVE", valueColor = StatusGreen)
            ReadoutRow(label = "DOCK", value = "AUTO · EDITABLE")
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "The dock picks recognised system apps (dialer, messaging, browser, " +
                    "camera, settings) and fills the remaining slots from your installed apps. " +
                    "The dock is editable: long-press any slot on the HUD.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
        }

        HudPanel(label = "DEVICE ACCESS", accent = if (notificationAccess) StatusGreen else Amber) {
            ReadoutRow(
                label = "NOTIFICATIONS",
                value = if (notificationAccess) "GRANTED" else "NOT GRANTED",
                valueColor = if (notificationAccess) StatusGreen else Amber,
            )
            ReadoutRow(label = "TORCH", value = "NO PERMISSION NEEDED", valueColor = StatusGreen)
            ReadoutRow(label = "MEDIA VOLUME", value = "NO PERMISSION NEEDED", valueColor = StatusGreen)
            ReadoutRow(label = "MICROPHONE", value = "NOT REQUESTED", valueColor = StatusGreen)
            ReadoutRow(label = "ACCESSIBILITY", value = "OPTIONAL · OFF BY DEFAULT", valueColor = StatusGreen)
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = if (notificationAccess) {
                    "MANAGE NOTIFICATION ACCESS"
                } else {
                    "ENABLE NOTIFICATION ACCESS"
                },
                icon = Icons.Outlined.NotificationsActive,
                accent = if (notificationAccess) IceBlue else Amber,
                filled = !notificationAccess,
                onClick = onOpenNotificationAccess,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Notification access is the only special permission JARVIS can use. It powers " +
                    "the alerts panel and media controls, is granted in system settings, and can be " +
                    "revoked there at any time.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }

        HudPanel(
            label = "ASSISTANT BUTTON",
            accent = if (assistantState == AssistantRole.State.HELD) StatusGreen else Amber,
        ) {
            ReadoutRow(
                label = "ASSISTANT ROLE",
                value = AssistantRole.label(assistantState),
                valueColor = if (assistantState == AssistantRole.State.HELD) StatusGreen else TextSecondary,
            )
            ReadoutRow(label = "WAKE WORD", value = "NONE \u00B7 NOTHING LISTENS IN THE BACKGROUND")
            ReadoutRow(label = "MICROPHONE", value = "ONLY WHILE LISTENING", valueColor = StatusGreen)
            Spacer(Modifier.height(10.dp))
            when (assistantState) {
                AssistantRole.State.HELD -> ActionButton(
                    label = "MANAGE ASSISTANT ROLE",
                    icon = Icons.Outlined.Settings,
                    accent = IceBlue,
                    filled = false,
                    onClick = {
                        AssistantRole.request(context)?.let { assistantLauncher.launch(it) }
                            ?: openDefaultApps(context)
                    },
                )

                AssistantRole.State.AVAILABLE -> ActionButton(
                    label = "MAKE JARVIS YOUR ASSISTANT",
                    icon = Icons.Outlined.Tune,
                    accent = Amber,
                    filled = true,
                    onClick = {
                        AssistantRole.request(context)?.let { assistantLauncher.launch(it) }
                            ?: openDefaultApps(context)
                    },
                )

                AssistantRole.State.UNSUPPORTED -> ActionButton(
                    label = "OPEN DEFAULT APPS",
                    icon = Icons.Outlined.OpenInNew,
                    accent = IceBlue,
                    filled = false,
                    onClick = { openDefaultApps(context) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Hold the assistant key and JARVIS opens already listening \u2014 " +
                    "no word to say, no microphone held open, nothing running while the phone sits " +
                    "idle. " + AssistantRole.gestureNote(android.os.Build.MANUFACTURER),
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }

        HudPanel(label = "QUIET MODE", accent = if (dndAccess) StatusGreen else Amber) {
            ReadoutRow(
                label = "INTERRUPTIONS",
                value = dndLabel,
                valueColor = if (dndAccess) StatusGreen else Amber,
            )
            ReadoutRow(
                label = "DND ACCESS",
                value = if (dndAccess) "GRANTED" else "NOT GRANTED",
                valueColor = if (dndAccess) StatusGreen else Amber,
            )
            ReadoutRow(label = "MODES", value = "OFF · PRIORITY · ALARMS · SILENT")
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = if (dndAccess) "MANAGE DND ACCESS" else "ENABLE DND ACCESS",
                icon = Icons.Outlined.NotificationsActive,
                accent = if (dndAccess) IceBlue else Amber,
                filled = !dndAccess,
                onClick = onOpenDndAccess,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Do Not Disturb access is the one special grant that lets an ordinary app " +
                    "set the interruption filter directly. With it, routines can genuinely silence " +
                    "the phone; without it JARVIS opens the system screen instead.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }

        HudPanel(
            label = "PRIVILEGED SHELL",
            accent = if (shizuku.isReady) StatusGreen else ElectricCyan,
        ) {
            ReadoutRow(
                label = "SHIZUKU",
                value = ShizukuShell.describe(shizuku),
                valueColor = if (shizuku.isReady) StatusGreen else TextSecondary,
            )
            ReadoutRow(
                label = "IDENTITY",
                value = shizuku.privilege.ifBlank { "—" },
            )
            ReadoutRow(label = "ROOT", value = "NOT REQUIRED", valueColor = StatusGreen)
            Spacer(Modifier.height(8.dp))
            Text(
                text = shizuku.detail.ifBlank {
                    "Optional adb-level shell for routine steps. Nothing binds until a routine or " +
                        "the terminal asks for it."
                },
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = when (shizuku.status) {
                    ShizukuShell.Status.READY -> "CONNECTED · MANAGE IN SHIZUKU"
                    ShizukuShell.Status.PERMISSION_NEEDED -> "AUTHORISE JARVIS"
                    ShizukuShell.Status.NOT_RUNNING -> "START SHIZUKU"
                    ShizukuShell.Status.NOT_INSTALLED -> "GET SHIZUKU"
                },
                icon = Icons.Outlined.Tune,
                accent = if (shizuku.isReady) StatusGreen else ElectricCyan,
                filled = shizuku.status == ShizukuShell.Status.PERMISSION_NEEDED,
                onClick = onShizukuAction,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Shizuku runs as the shell user over adb, so nothing here is root. Shell " +
                    "steps still need the operator's confirmation, and revoking Shizuku stops them " +
                    "immediately — everything else in JARVIS keeps working.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }

        HudPanel(label = "HUD LAYOUT", accent = ElectricCyan) {
            ReadoutRow(
                label = "DOCK",
                value = if (isDockCustom) "CUSTOM" else "AUTO-FILLED",
                valueColor = if (isDockCustom) ElectricCyan else StatusGreen,
            )
            ReadoutRow(label = "SLOTS", value = "5")
            ReadoutRow(label = "STORAGE", value = "SHARED PREFERENCES")
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = "EDIT QUICK ACCESS DOCK",
                icon = Icons.Outlined.Tune,
                accent = ElectricCyan,
                filled = false,
                onClick = onEditDock,
            )
        }

        HudPanel(label = "BUILD", accent = IceBlue) {
            ReadoutRow(label = "VERSION", value = BuildConfig.VERSION_NAME.uppercase())
            ReadoutRow(label = "VERSION CODE", value = BuildConfig.VERSION_CODE.toString())
            ReadoutRow(label = "PACKAGE", value = "com.jarvis.assistant")
            ReadoutRow(label = "MIN ANDROID", value = "10 (API 29)")
            ReadoutRow(label = "TARGET", value = "14 (API 34)")
            Spacer(Modifier.height(10.dp))
            ActionButton(
                label = "APP INFO",
                icon = Icons.Outlined.Info,
                accent = IceBlue,
                filled = false,
                onClick = {
                    LauncherStatus.openSystemScreen(context, LauncherStatus.appDetailsIntent(context))
                },
            )
        }

        HudPanel(label = "LAUNCHER SCOPE", accent = TextMuted) {
            Text(
                text = "JARVIS can become the Home app, list launchable apps and open them. It " +
                    "cannot replace the lock screen, permission dialogs, system navigation or " +
                    "protected screens — those stay with Android.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

/** Where to go when this phone has no role dialog: the default-apps list still has the choice. */
private fun openDefaultApps(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        // Some ROMs strip that screen; the gesture note above already tells the operator where the
        // setting lives in that case.
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (filled) accent.copy(alpha = 0.16f) else Color.Transparent,
                ChipShape,
            )
            .border(1.dp, accent.copy(alpha = if (filled) 0.65f else 0.32f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
            color = accent,
        )
        Spacer(Modifier.weight(1f))
        HudChip(text = "SYSTEM", accent = accent.copy(alpha = 0.7f))
    }
}
