package com.jarvis.assistant.ui.screens

import android.app.Activity
import android.os.CancellationSignal
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.Archive
import com.jarvis.assistant.data.Vault
import com.jarvis.assistant.device.Biometrics
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The locked drawer.
 *
 * Nothing here is a claim: the fingerprint prompt is the system's own, the notes are in the app's
 * private storage, and the archive figures are the same numbers the Privacy screen shows. What the
 * Vault adds is a door — the screen only opens after secure hardware says it is you.
 *
 * If the phone has no fingerprint or face enrolled, it says so and lets the operator in with one tap,
 * because pretending a gate exists when it does not would be the one dishonest thing here.
 */
@Composable
fun VaultScreen(
    stepsToday: Int?,
    waterToday: Int,
    conversationCount: Int,
    conversationBytes: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var unlocked by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<Vault.Note>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    var busy by remember { mutableStateOf(false) }

    val canUseBiometrics = activity != null && Biometrics.isEnabled(context) && Biometrics.isAvailable(context)

    fun unlock() {
        val host = activity ?: return
        if (!Biometrics.isAvailable(context)) return
        notice = ""
        signal = Biometrics.prompt(host) { outcome ->
            signal = null
            when (outcome) {
                Biometrics.Outcome.SUCCESS -> unlocked = true
                Biometrics.Outcome.CANCELLED -> notice = "Locked. Tap unlock whenever you are ready."
                Biometrics.Outcome.FAILED -> notice = "Not recognised. Try again."
            }
        }
        if (signal == null) notice = "This phone would not show the fingerprint prompt."
    }

    // A prompt must not outlive the screen that owns it.
    DisposableEffect(Unit) {
        onDispose {
            signal?.let { Biometrics.cancel(it) }
            signal = null
        }
    }

    LaunchedEffect(Unit) {
        if (canUseBiometrics) unlock()
    }

    LaunchedEffect(unlocked) {
        if (unlocked) notes = withContext(Dispatchers.IO) { Vault.notes(context) }
    }

    ModuleScaffold(
        title = "VAULT",
        subtitle = "locked drawer",
        accent = if (unlocked) StatusGreen else ElectricCyan,
        statusLabel = if (unlocked) "OPEN" else "LOCKED",
        onBack = onBack,
    ) {
        if (!unlocked) {
            HudPanel(
                label = "ACCESS",
                accent = ElectricCyan,
                trailing = { HudChip(text = "LOCKED", accent = Amber) },
            ) {
                Text(
                    text = "Health, the conversation archive and private notes, behind your " +
                        "fingerprint or face. The prompt is the phone's own, so JARVIS never sees " +
                        "the scan itself — only the yes or no.",
                    style = HudType.valueSmall,
                    color = TextMuted,
                )
                if (notice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = notice, style = HudType.valueSmall, color = Amber)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canUseBiometrics) {
                        VaultKey("UNLOCK") { unlock() }
                    } else {
                        VaultKey("OPEN WITHOUT A SCAN") {
                            unlocked = true
                            notice = ""
                        }
                    }
                }
                if (!canUseBiometrics) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No fingerprint or face is set up on this phone, so there is nothing " +
                            "secure to check. Set one up in the phone's settings for the drawer to " +
                            "actually lock; until then this screen is only as private as your " +
                            "screen lock.",
                        style = HudType.valueSmall,
                        color = Amber,
                    )
                }
            }
            return@ModuleScaffold
        }

        // ------------------------------------------------------------------ health
        HudPanel(label = "HEALTH TODAY", accent = StatusGreen) {
            ReadoutRow(label = "STEPS", value = stepsToday?.toString() ?: "NO SENSOR")
            ReadoutRow(label = "WATER", value = "$waterToday ml")
        }

        // ------------------------------------------------------------------ archive
        HudPanel(label = "CONVERSATION ARCHIVE", accent = ElectricCyan) {
            ReadoutRow(
                label = "EXCHANGES",
                value = conversationCount.toString(),
            )
            ReadoutRow(label = "ON DISK", value = formatBytes(conversationBytes))
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Export writes the whole archive, including the conversation log, to " +
                    "Downloads/JARVIS. The AI key is never in it.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultKey(if (busy) "EXPORTING…" else "EXPORT") {
                    if (busy) return@VaultKey
                    busy = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { Archive.saveToDownloads(context) }
                        busy = false
                        notice = saved.second
                    }
                }
            }
        }

        if (notice.isNotBlank()) {
            HudPanel(label = "NOTE", accent = IceBlue) {
                Text(text = notice, style = HudType.valueSmall, color = TextMuted)
            }
        }

        // ------------------------------------------------------------------ notes
        HudPanel(label = "PRIVATE NOTES", accent = IceBlue) {
            if (notes.isEmpty()) {
                Text(
                    text = "Nothing kept yet. A note here stays on this phone and is never sent to " +
                        "the AI unless you ask him about it out loud.",
                    style = HudType.valueSmall,
                    color = TextMuted,
                )
            } else {
                notes.forEach { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(PanelNavy.copy(alpha = 0.55f), ChipShape)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = note.text, style = HudType.valueSmall, color = TextPrimary)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = dateOf(note.at),
                                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.2.sp),
                                color = TextMuted,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clickable {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { Vault.remove(context, note.id) }
                                        notes = withContext(Dispatchers.IO) { Vault.notes(context) }
                                    }
                                }
                                .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                text = "DELETE",
                                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                                color = Amber,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelNavy.copy(alpha = 0.7f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.25f), ChipShape)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "Add a private note…",
                        style = HudType.valueSmall,
                        color = TextMuted,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(Vault.MAX_CHARS) },
                    textStyle = HudType.valueSmall.copy(color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultKey("SAVE NOTE") {
                    val text = draft
                    if (text.isBlank()) {
                        notice = "Nothing to save."
                    } else {
                        scope.launch {
                            val added = withContext(Dispatchers.IO) { Vault.add(context, text) }
                            notice = if (added == null) {
                                "That note was not saved (the drawer is full)."
                            } else {
                                "Saved."
                            }
                            if (added != null) draft = ""
                            notes = withContext(Dispatchers.IO) { Vault.notes(context) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(PanelNavy.copy(alpha = 0.9f), ChipShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.25f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
            color = ElectricCyan,
        )
    }
}

private fun dateOf(at: Long): String = try {
    java.text.SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US).format(java.util.Date(at))
} catch (t: Throwable) {
    ""
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
}
