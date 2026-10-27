package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiKeyStore
import com.jarvis.assistant.ai.AiProvider
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.ai.ModelsResult
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

private const val MIN_DETECT_LENGTH = 8
private const val MODEL_PAGE = 12

/**
 * Intelligence settings.
 *
 * The operator pastes their own provider key; the provider is inferred from the key's shape and can
 * be overridden. The key is encrypted with the Android Keystore before it touches disk, so a backup
 * of the app's data does not expose it.
 *
 * Model names are the fragile part of any provider integration — they are retired without warning.
 * So this screen can ask the provider what the key can actually reach ([AiClient.listModels]) and
 * offers that list as taps, and a failed test fetches the list automatically.
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

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelPage by remember { mutableStateOf(1) }
    var fetchingModels by remember { mutableStateOf(false) }

    val effectiveModel = model.ifBlank { provider.defaultModel }

    // A model or provider change is a save in its own right — the key must not have to be re-pasted.
    val configChanged = current != null &&
        (provider != current!!.provider ||
            model.trim() != current!!.model ||
            baseUrl.trim() != current!!.baseUrl)

    fun fetchModels(auto: Boolean) {
        if (fetchingModels) return
        if (current == null && keyDraft.isBlank()) {
            note = "Store a key first, then JARVIS can list its models."
            noteOk = false
            return
        }
        fetchingModels = true
        models = emptyList()
        modelPage = 1
        if (auto) {
            note = "Looking up the models your key can reach…"
            noteOk = true
        }
        scope.launch {
            // Use the draft key if it has not been saved yet, otherwise the stored one.
            if (keyDraft.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    AiKeyStore.save(context, provider, model, baseUrl, keyDraft)
                }
                current = withContext(Dispatchers.IO) { AiKeyStore.config(context) }
            }
            when (val result = AiClient.listModels(context)) {
                is ModelsResult.Ok -> {
                    models = AiClient.rankModels(result.models)
                    note = "${result.models.size} models available on ${result.providerLabel} — tap one."
                    noteOk = true
                }

                is ModelsResult.Error -> {
                    note = result.message + (result.hint?.let { " — $it" } ?: "")
                    noteOk = false
                }
            }
            fetchingModels = false
        }
    }

    fun save() {
        if (busy) return
        busy = true
        note = null
        scope.launch {
            if (keyDraft.isNotBlank()) {
                val error = withContext(Dispatchers.IO) {
                    AiKeyStore.save(context, provider, model, baseUrl, keyDraft)
                }
                if (error == null) {
                    keyDraft = ""
                    note = "Stored. ${provider.label} is linked to this device."
                    noteOk = true
                } else {
                    note = error
                    noteOk = false
                }
            } else {
                withContext(Dispatchers.IO) {
                    AiKeyStore.updateConfig(context, provider, model, baseUrl)
                }
                note = "Saved. ${provider.label} · ${model.ifBlank { provider.defaultModel }}"
                noteOk = true
            }
            current = withContext(Dispatchers.IO) { AiKeyStore.config(context) }
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
            when (val result = AiClient.selfTest(context)) {
                is AiResult.Ok -> {
                    note = "Connected · ${result.providerLabel} · ${result.model}" +
                        (result.note?.let { " ($it)" } ?: "") +
                        " · reply '${result.text.take(30)}'"
                    noteOk = true
                    // The client may have switched model; pick the change up in the form.
                    current = withContext(Dispatchers.IO) { AiKeyStore.config(context) }
                    current?.let {
                        model = it.model
                        provider = it.provider
                    }
                }

                is AiResult.Error -> {
                    note = result.message + (result.hint?.let { " — $it" } ?: "")
                    noteOk = false
                    // A model problem is self-fixing: list what this key can actually use.
                    if (result.message.contains("model", ignoreCase = true)) {
                        fetchModels(auto = true)
                    }
                }
            }
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
            models = emptyList()
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
            val stored = current
            if (stored != null) {
                ReadoutRow(label = "PROVIDER", value = stored.label, valueColor = StatusGreen)
                ReadoutRow(label = "MODEL", value = stored.resolvedModel)
                ReadoutRow(label = "KEY", value = stored.maskedKey)
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
                            if (selected) ElectricCyan.copy(alpha = 0.12f) else Color(0xFF0A1622),
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
                            models = emptyList()
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
                text = "USING ${effectiveModel.ifBlank { "—" }.uppercase()}",
                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                color = TextMuted,
            )

            Spacer(Modifier.height(10.dp))

            // Model ids get retired without notice, so the list is fetched, never assumed.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        (if (fetchingModels) TextMuted else ElectricCyan).copy(alpha = 0.10f),
                        ChipShape,
                    )
                    .border(
                        1.dp,
                        (if (fetchingModels) TextMuted else ElectricCyan).copy(alpha = 0.40f),
                        ChipShape,
                    )
                    .clickable(enabled = !fetchingModels) { fetchModels(auto = false) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (fetchingModels) "FETCHING MODELS…" else "FETCH MODELS FROM PROVIDER",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                    color = if (fetchingModels) TextMuted else ElectricCyan,
                )
            }

            if (models.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "AVAILABLE · ${models.size}",
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                    color = TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                models.take(modelPage * MODEL_PAGE).forEach { candidate ->
                    val selected = candidate.equals(effectiveModel, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .background(
                                if (selected) StatusGreen.copy(alpha = 0.10f)
                                else Color(0xFF0A1622),
                                ChipShape,
                            )
                            .border(
                                1.dp,
                                (if (selected) StatusGreen else ElectricCyan)
                                    .copy(alpha = if (selected) 0.40f else 0.12f),
                                ChipShape,
                            )
                            .clickable {
                                model = candidate
                                autoDetect = false
                                note = "Selected $candidate — tap SAVE KEY to keep it."
                                noteOk = true
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = candidate,
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = if (selected) StatusGreen else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "IN USE",
                                style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                                color = StatusGreen,
                            )
                        }
                    }
                }
                if (models.size > modelPage * MODEL_PAGE) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A1622), ChipShape)
                            .border(1.dp, ElectricCyan.copy(alpha = 0.16f), ChipShape)
                            .clickable { modelPage += 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "SHOW MORE (${models.size - modelPage * MODEL_PAGE} LEFT)",
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                            color = TextSecondary,
                        )
                    }
                }
            }

            if (provider.needsBaseUrl) {
                Spacer(Modifier.height(12.dp))
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
                if (!noteOk && (message.contains("model", ignoreCase = true) ||
                        message.contains("404"))
                ) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallPill(
                            label = "FETCH MODELS",
                            accent = ElectricCyan,
                            onClick = { fetchModels(auto = false) },
                        )
                        SmallPill(
                            label = "USE ${provider.defaultModel.uppercase()}",
                            accent = StatusGreen,
                            onClick = {
                                model = provider.defaultModel
                                note = "Selected ${provider.defaultModel} — tap SAVE KEY."
                                noteOk = true
                            },
                        )
                    }
                }
            }
        }

        ActionButton(
            label = when {
                busy -> "WORKING…"
                keyDraft.isNotBlank() -> "SAVE KEY"
                configChanged -> "SAVE CHANGES"
                else -> "SAVE KEY"
            },
            accent = ElectricCyan,
            enabled = !busy && (keyDraft.isNotBlank() || configChanged),
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
                text = "If a model is rejected, JARVIS tries the provider's known alternatives and " +
                    "remembers the one that answered, so a retired model id cannot break a routine " +
                    "that used to work.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
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

@Composable
private fun SmallPill(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.10f), ChipShape)
            .border(1.dp, accent.copy(alpha = 0.38f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
