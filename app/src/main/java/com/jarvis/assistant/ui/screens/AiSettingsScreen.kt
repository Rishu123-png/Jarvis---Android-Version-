package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.ai.AiProvider
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Intelligence settings.
 *
 * The operator pastes their own provider key; the provider is inferred from the key's shape and can
 * be overridden. The key is encrypted with the Android Keystore before it touches disk, so a backup
 * of the app's data does not expose it.
 */
@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(AiKeyStore.config(context)) }
    var provider by remember { mutableStateOf(current?.provider ?: AiProvider.OPENAI) }
    var model by remember { mutableStateOf(current?.model ?: "") }
    var baseUrl by remember { mutableStateOf(current?.baseUrl ?: "") }
    var keyDraft by remember { mutableStateOf("") }
    var autoDetect by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteOk by remember { mutableStateOf(false) }

    val effectiveModel = model.ifBlank { provider.defaultModel }

    fun save() {
        if (busy) return
        busy = true
        note = null
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                AiKeyStore.save(context, provider, model, baseUrl, keyDraft)
            }
            if (error == null) {
                keyDraft = ""
                current = withContext(Dispatchers.IO) { AiKeyStore.config(context) }
                note = "Stored. ${provider.label} is linked to this device."
                noteOk = true
            } else {
                note = error
                noteOk = false
            }
            busy = false
        }
    }

    fun test() {
        if (busy) return
        if (current == null) {
            note = "Store a key first, then test the connection."
            noteOk = false
            return
        }
        busy = true
        note = "Contacting provider…"
        noteOk = true
        scope.launch {
            val result = AiClient.selfTest(context)
            note = when (result) {
                is AiResult.Ok ->
                    "Connected · ${result.providerLabel} · ${result.model} · reply '${result.text.take(30)}'"

                is AiResult.Error ->
                    result.message + (result.hint?.let { " — $it" } ?: "")
            }
            noteOk = result is AiResult.Ok
            busy = false
        }
    }

    fun forget() {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { AiKeyStore.clear(context) }
            current = null
            keyDraft = ""
            note = "Key removed from this device."
            noteOk = true
            busy = false
        }
    }

    ModuleScaffold(
        title = "INTELLIGENCE",
        subtitle = "your key · your provider",
        accent = ElectricCyan,
        statusLabel = if (current != null) "LINKED" else "UNLINKED",
        onBack = onBack,
    ) {
        HudPanel(
            label = "STATUS",
            accent = if (current != null) StatusGreen else TextMuted,
        ) {
            if (current != null) {
                ReadoutRow(label = "PROVIDER", value = current!!.label, valueColor = StatusGreen)
                ReadoutRow(label = "MODEL", value = current!!.resolvedModel)
                ReadoutRow(label = "KEY", value = current!!.maskedKey)
            } else {
                ReadoutRow(label = "PROVIDER", value = "—", valueColor = TextMuted)
                ReadoutRow(label = "MODEL", value = "—", valueColor = TextMuted)
                ReadoutRow(label = "KEY", value = "NOT STORED", valueColor = Amber)
            }
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "The key is encrypted with the Android Keystore (AES-256-GCM) before " +
                    "it is written to storage. It is sent only to the provider you chose — " +
                    "JARVIS has no server of its own.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
        }

        HudPanel(label = "API KEY", accent = ElectricCyan) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF08131F), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.28f), ChipShape)
                    .padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                if (keyDraft.isEmpty()) {
                    Text(
                        text = if (current != null) {
                            "paste a new key to replace the stored one"
                        } else {
                            provider.keyHint
                        },
                        style = HudType.value.copy(fontSize = 12.sp),
                        color = TextMuted,
                    )
                }
                BasicTextField(
                    value = keyDraft,
                    onValueChange = { text ->
                        keyDraft = text.take(220)
                        if (autoDetect && keyDraft.trim().length >= MIN_DETECT_LENGTH) {
                            provider = AiProvider.detect(keyDraft)
                        }
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = HudType.value.copy(fontSize = 12.sp, color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = when {
                    keyDraft.isBlank() && current != null -> "LEAVE EMPTY TO KEEP THE STORED KEY"
                    keyDraft.isBlank() -> "PASTE ANY SUPPORTED KEY"
                    autoDetect -> "DETECTED · ${provider.label.uppercase()}"
                    else -> "MANUAL · ${provider.label.uppercase()}"
                },
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = if (keyDraft.isNotBlank() && autoDetect) StatusGreen else TextMuted,
            )
        }

        HudPanel(label = "PROVIDER", accent = TextMuted) {
            AiProvider.entries.forEach { option ->
                val selected = option == provider
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp)
                        .background(
                            if (selected) ElectricCyan.copy(alpha = 0.12f)
                            else Color(0xFF0A1622),
                            ChipShape,
                        )
                        .border(
                            1.dp,
                            ElectricCyan.copy(alpha = if (selected) 0.45f else 0.12f),
                            ChipShape,
                        )
                        .clickable {
                            provider = option
                            autoDetect = false
                            if (model.isBlank()) model = option.defaultModel
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option.label,
                        style = HudType.value.copy(fontSize = 11.sp),
                        color = if (selected) TextPrimary else TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = option.wire.name,
                        style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 0.8.sp),
                        color = TextMuted,
                    )
                }
            }
        }

        HudPanel(label = "MODEL", accent = TextMuted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF08131F), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.20f), ChipShape)
                    .padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                if (model.isEmpty()) {
                    Text(
                        text = provider.defaultModel.ifBlank { "model name" },
                        style = HudType.value.copy(fontSize = 12.sp),
                        color = TextMuted,
                    )
                }
                BasicTextField(
                    value = model,
                    onValueChange = { model = it.take(80) },
                    singleLine = true,
                    textStyle = HudType.value.copy(fontSize = 12.sp, color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "DEFAULT · ${provider.defaultModel.ifBlank { "ENTER ONE" }}  ·  USING ${effectiveModel.ifBlank { "—" }.uppercase()}",
                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                color = TextMuted,
            )

            if (provider.needsBaseUrl) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "BASE URL",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                    color = TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF08131F), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.20f), ChipShape)
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                ) {
                    if (baseUrl.isEmpty()) {
                        Text(
                            text = "https://your-host/v1",
                            style = HudType.value.copy(fontSize = 12.sp),
                            color = TextMuted,
                        )
                    }
                    BasicTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it.take(160) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        textStyle = HudType.value.copy(fontSize = 12.sp, color = TextPrimary),
                        cursorBrush = SolidColor(ElectricCyan),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        note?.let { message ->
            HudPanel(
                label = "MESSAGE",
                accent = if (noteOk) StatusGreen else AlertRed,
            ) {
                Text(
                    text = message,
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = if (noteOk) StatusGreen else Color(0xFFFF8A98),
                )
            }
        }

        ActionButton(
            label = if (busy) "WORKING…" else "SAVE KEY",
            accent = ElectricCyan,
            enabled = !busy && keyDraft.isNotBlank(),
            onClick = { save() },
        )
        ActionButton(
            label = "TEST CONNECTION",
            accent = StatusGreen,
            enabled = !busy && current != null,
            onClick = { test() },
        )
        ActionButton(
            label = "FORGET KEY",
            accent = AlertRed,
            enabled = !busy && current != null,
            onClick = { forget() },
        )

        HudPanel(label = "SUPPORTED KEYS", accent = TextMuted) {
            Text(
                text = "OpenAI · Anthropic · Google Gemini · Groq · xAI · OpenRouter · " +
                    "Mistral · any OpenAI-compatible endpoint. Paste the key and JARVIS " +
                    "identifies the provider from its prefix; override the picker if it " +
                    "guesses wrong.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Uninstalling JARVIS, or clearing its data, destroys the encryption key " +
                    "and the stored key with it.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

private const val MIN_DETECT_LENGTH = 8

@Composable
private fun ActionButton(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = if (enabled) 0.12f else 0.04f), ChipShape)
            .border(1.dp, accent.copy(alpha = if (enabled) 0.45f else 0.12f), ChipShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
            color = if (enabled) accent else TextMuted,
        )
    }
}
