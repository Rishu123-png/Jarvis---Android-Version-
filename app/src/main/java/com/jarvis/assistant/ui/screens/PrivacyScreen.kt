package com.jarvis.assistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.device.Access
import com.jarvis.assistant.device.Privacy
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.voice.SpeechRoute

/**
 * Advanced settings: privacy lock, floating bubble, speech engine, widget.
 *
 * One screen rather than four, because these all answer the same question — how much of JARVIS is
 * allowed to run, and where. Every control states what it costs and what breaks without it, and
 * nothing here can be switched on without the permission it needs.
 */
@Composable
fun PrivacyScreen(
    pinEnabled: Boolean,
    bubbleEnabled: Boolean,
    bubbleAllowed: Boolean,
    speechRoute: SpeechRoute,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
    onToggleBubble: (Boolean) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onSelectRoute: (SpeechRoute) -> Unit,
    onAddWidget: () -> Unit,
    biometricStatus: String,
    biometricEnabled: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    phoneLockEnabled: Boolean,
    deviceLockAvailable: Boolean,
    onSelectLockMode: (Boolean) -> Unit,
    notifyBrainEnabled: Boolean,
    onToggleNotifyBrain: (Boolean) -> Unit,
    nudgesEnabled: Boolean,
    onToggleNudges: (Boolean) -> Unit,
    roadRoutingEnabled: Boolean,
    onToggleRoadRouting: (Boolean) -> Unit,
    lockNoticeEnabled: Boolean,
    onToggleLockNotice: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var pinDraft by remember { mutableStateOf("") }
    var pinNote by remember { mutableStateOf<String?>(null) }
    var accessRefresh by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val accessRows = remember(accessRefresh) { Access.snapshot(context) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { accessRefresh++ }

    ModuleScaffold(
        title = "ADVANCED",
        subtitle = "privacy · bubble · engine",
        accent = Amber,
        statusLabel = "OPERATOR",
        onBack = onBack,
    ) {
        // ---------------------------------------------------------------- privacy lock
        HudPanel(
            label = "PRIVACY LOCK",
            accent = if (pinEnabled) StatusGreen else Amber,
            trailing = { HudChip(text = if (pinEnabled) "ON" else "OFF", accent = if (pinEnabled) StatusGreen else Amber) },
        ) {
            Text(
                text = "A numeric PIN in front of JARVIS itself: the app, your routines, your connected " +
                    "accounts and the AI history. It is stored as a PBKDF2 digest, never as text.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))

            if (!pinEnabled) {
                Text(
                    text = "SET A PIN (${Privacy.MIN_DIGITS}–${Privacy.MAX_DIGITS} DIGITS)",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                    color = ElectricCyan,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(Privacy.MAX_DIGITS) { index ->
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(14.dp)
                                .background(
                                    if (index < pinDraft.length) ElectricCyan else PanelNavy,
                                    ChipShape,
                                )
                                .border(1.dp, ElectricCyan.copy(alpha = 0.35f), ChipShape),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..9).forEach { digit ->
                        KeypadKey(digit.toString()) {
                            if (pinDraft.length < Privacy.MAX_DIGITS) pinDraft += digit.toString()
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeypadKey("DEL") { pinDraft = pinDraft.dropLast(1) }
                    KeypadKey("0") { if (pinDraft.length < Privacy.MAX_DIGITS) pinDraft += "0" }
                    KeypadKey("SET") {
                        if (Privacy.isValidPin(pinDraft)) {
                            onSetPin(pinDraft)
                            pinDraft = ""
                            pinNote = "Lock enabled. It engages in the background after a minute."
                        } else {
                            pinNote = "Use ${Privacy.MIN_DIGITS}–${Privacy.MAX_DIGITS} digits."
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pinNote ?: "No recovery: clearing JARVIS data is the only reset.",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                    color = if (pinNote == null) TextMuted else Amber,
                )
            } else {
                Text(
                    text = "Lock is on. Unlocking is required after a minute away from the app.",
                    style = HudType.valueSmall,
                    color = ElectricCyan,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeypadKey("TURN OFF") {
                        onClearPin()
                        pinNote = "Lock disabled."
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Turning the lock off clears the stored PIN immediately.",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                    color = TextMuted,
                )
            }
        }

        // ---------------------------------------------------------------- biometrics
        HudPanel(
            label = "FINGERPRINT / FACE",
            accent = if (biometricEnabled && biometricStatus == "READY") StatusGreen else Amber,
            trailing = {
                HudChip(
                    text = if (biometricEnabled && biometricStatus == "READY") "ON" else "OFF",
                    accent = if (biometricEnabled && biometricStatus == "READY") StatusGreen else TextMuted,
                )
            },
        ) {
            Text(
                text = "Unlock JARVIS with the fingerprint or face your phone already knows. Android " +
                    "does the matching in its own secure hardware — JARVIS never sees, stores or sends " +
                    "biometric data, so there is nothing here to leak.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            ReadoutRow(
                label = "SENSOR",
                value = biometricStatus,
                valueColor = if (biometricStatus == "READY") StatusGreen else Amber,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey(
                    if (biometricEnabled) "TURN OFF" else "TURN ON",
                ) { onToggleBiometric(!biometricEnabled) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your PIN is always the fallback. Biometrics can never replace it — a wet " +
                    "finger or a locked-out sensor must not be able to strand you outside your own app.",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = TextMuted,
            )
        }

        // ---------------------------------------------------------------- bubble
        HudPanel(
            label = "FLOATING BUBBLE",
            accent = if (bubbleEnabled && bubbleAllowed) StatusGreen else Amber,
            trailing = { HudChip(text = if (bubbleEnabled) "ON" else "OFF", accent = if (bubbleEnabled) StatusGreen else TextMuted) },
        ) {
            Text(
                text = "A small disc over other apps. A tap opens JARVIS with the voice surface armed; " +
                    "drag to move, long press to remove. It never listens on its own.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            ReadoutRow(
                label = "OVERLAY PERMISSION",
                value = if (bubbleAllowed) "GRANTED" else "NOT GRANTED",
                valueColor = if (bubbleAllowed) StatusGreen else Amber,
            )
            Spacer(Modifier.height(10.dp))
            if (!bubbleAllowed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeypadKey("ALLOW OVERLAY") { onOpenOverlaySettings() }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeypadKey(if (bubbleEnabled) "REMOVE BUBBLE" else "SHOW BUBBLE") {
                        onToggleBubble(!bubbleEnabled)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- speech engine
        HudPanel(label = "SPEECH ENGINE", accent = ElectricCyan) {
            Text(
                text = "Which ear JARVIS uses. Whisper runs on your provider with your key; the device " +
                    "recognizer stays on the phone, and its offline mode asks for an on-device model.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            SpeechRoute.entries.forEach { route ->
                val selected = route == speechRoute
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(
                            if (selected) ElectricCyan.copy(alpha = 0.10f) else PanelNavy.copy(alpha = 0.5f),
                            ChipShape,
                        )
                        .border(
                            1.dp,
                            if (selected) ElectricCyan.copy(alpha = 0.55f) else ElectricCyan.copy(alpha = 0.12f),
                            ChipShape,
                        )
                        .clickable { onSelectRoute(route) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = route.label,
                            style = HudType.valueSmall,
                            color = if (selected) TextPrimary else TextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) HudChip(text = "ACTIVE", accent = StatusGreen)
                    }
                    Text(
                        text = route.blurb,
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.6.sp),
                        color = TextMuted,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- widget
        HudPanel(label = "HOME SCREEN WIDGET", accent = ElectricCyan) {
            Text(
                text = "A 4×2 plate with a live 12-hour clock. Tapping it opens JARVIS ready to listen.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey("ADD WIDGET") { onAddWidget() }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Some launchers place it silently; others ask for confirmation on the home screen.",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = TextMuted,
            )
        }

        HudDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ---------------------------------------------------------------- which lock
        HudPanel(
            label = "WHICH LOCK",
            accent = ElectricCyan,
            trailing = {
                HudChip(
                    text = if (phoneLockEnabled) "PHONE LOCK" else "KEYPAD",
                    accent = ElectricCyan,
                )
            },
        ) {
            Text(
                text = "The phone's own lock is the honest choice: JARVIS asks Android to show your " +
                    "real PIN, pattern or fingerprint screen and learns only pass or fail. The " +
                    "keypad is a second, app-only gate for phones without a secure lock.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey(if (phoneLockEnabled) "USE KEYPAD" else "USE PHONE LOCK") {
                    onSelectLockMode(!phoneLockEnabled)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    phoneLockEnabled && !deviceLockAvailable ->
                        "This phone has no PIN, pattern or fingerprint set, so the keypad stays in charge."

                    phoneLockEnabled ->
                        "Your fingerprint or PIN is asked for each time JARVIS is opened."

                    else -> "JARVIS's own PIN is used. Change it below."
                },
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = if (phoneLockEnabled && !deviceLockAvailable) Amber else TextMuted,
            )
        }

        Spacer(Modifier.height(10.dp))

        HudPanel(
            label = "LOCK SCREEN LINE",
            accent = if (lockNoticeEnabled) StatusGreen else Amber,
            trailing = {
                HudChip(
                    text = if (lockNoticeEnabled) "ON" else "OFF",
                    accent = if (lockNoticeEnabled) StatusGreen else Amber,
                )
            },
        ) {
            Text(
                text = "A short line over your lock screen — steps, water and unread messages. " +
                    "Android does not let any app replace the lock screen, so this is a " +
                    "high-priority notification, which is what is actually possible.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey(if (lockNoticeEnabled) "TURN OFF" else "TURN ON") {
                    onToggleLockNotice(!lockNoticeEnabled)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------------------------------------------------------------- assistant behaviour
        HudPanel(
            label = "SPEAK MY ALERTS",
            accent = if (notifyBrainEnabled) StatusGreen else Amber,
            trailing = {
                HudChip(
                    text = if (notifyBrainEnabled) "ON" else "OFF",
                    accent = if (notifyBrainEnabled) StatusGreen else Amber,
                )
            },
        ) {
            Text(
                text = "JARVIS reads a message out and suggests three replies. Advertisements get " +
                    "one dry line and are then ignored for the day. Off by default, because an " +
                    "assistant that speaks unprompted should be invited first.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey(if (notifyBrainEnabled) "TURN OFF" else "TURN ON") {
                    onToggleNotifyBrain(!notifyBrainEnabled)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        HudPanel(
            label = "MORNING BRIEF",
            accent = if (nudgesEnabled) StatusGreen else Amber,
            trailing = {
                HudChip(
                    text = if (nudgesEnabled) "ON" else "OFF",
                    accent = if (nudgesEnabled) StatusGreen else Amber,
                )
            },
        ) {
            Text(
                text = "When you open JARVIS it says the useful thing once — the next event, a battery " +
                    "that will not last, people waiting for an answer — and stays quiet when there is " +
                    "nothing to say. At most once every three hours.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey(if (nudgesEnabled) "TURN OFF" else "TURN ON") {
                    onToggleNudges(!nudgesEnabled)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        HudPanel(label = "ROAD DISTANCE", accent = ElectricCyan) {
            Text(
                text = "Distance by road needs one request to a public router (OSRM). With this " +
                    "off, JARVIS answers straight-line distance and says so out loud.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeypadKey(if (roadRoutingEnabled) "TURN OFF" else "TURN ON") {
                    onToggleRoadRouting(!roadRoutingEnabled)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---------------------------------------------------------------- permissions
        HudPanel(
            label = "ACCESS",
            accent = if (accessRows.any { !it.granted }) Amber else StatusGreen,
            trailing = {
                val missing = accessRows.count { !it.granted }
                HudChip(
                    text = if (missing == 0) "ALL SET" else "$missing MISSING",
                    accent = if (missing == 0) StatusGreen else Amber,
                )
            },
        ) {
            Text(
                text = "Every capability JARVIS has, and the switch behind it. A feature that " +
                    "silently lacks its permission is the usual reason an assistant feels broken.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            for (item in accessRows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.label,
                            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                            color = if (item.granted) StatusGreen else TextPrimary,
                        )
                        Text(
                            text = item.unlocks + " · " + item.note,
                            style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.8.sp),
                            color = TextMuted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (item.granted) {
                        HudChip(text = "ON", accent = StatusGreen)
                    } else {
                        KeypadKey("GRANT") {
                            when (item.kind) {
                                Access.Kind.RUNTIME ->
                                    permissionLauncher.launch(item.permissions.toTypedArray())

                                else -> Access.settingsIntent(context, item)?.let { intent ->
                                    try {
                                        context.startActivity(intent)
                                    } catch (t: Throwable) {
                                        // The page does not exist on this phone; nothing to open.
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        HudDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
            text = "ADVANCED SETTINGS · NOTHING HERE LEAVES THE DEVICE",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.8.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(PanelNavy.copy(alpha = 0.9f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.25f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
            color = ElectricCyan,
        )
    }
}
