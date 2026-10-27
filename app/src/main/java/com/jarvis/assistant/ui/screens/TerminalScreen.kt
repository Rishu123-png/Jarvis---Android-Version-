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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.data.CommandRegistry
import com.jarvis.assistant.ui.components.BlinkingCursor
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_TURNS = 8

/**
 * Device console.
 *
 * Two paths:
 *  * a recognised local command runs against a real Android API on this device;
 *  * anything else is offered to the operator's own AI key, if one is configured.
 *
 * Only one AI request can be in flight, and it is cancelled with the screen, so a slow provider
 * cannot leave the panel stuck. Local commands never touch the network.
 */
@Composable
fun TerminalScreen(
    aiConfigured: Boolean,
    aiLabel: String?,
    onOpenAiSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lines = remember(aiConfigured, aiLabel) {
        mutableStateListOf<String>().apply { addAll(CommandRegistry.banner(aiConfigured, aiLabel)) }
    }
    val history = remember { mutableStateListOf<AiMessage>() }
    val suggestions = remember { mutableStateListOf<String>() }

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun append(line: String) {
        lines.add(line)
        while (lines.size > CommandRegistry.MAX_HISTORY_LINES) {
            lines.removeAt(0)
        }
    }

    suspend fun ask(question: String) {
        val prompt = question.trim()
        if (prompt.isBlank()) return
        busy = true
        suggestions.clear()
        append("uplink :: ${aiLabel ?: "ai"} :: thinking")
        history.add(AiMessage.user(prompt))
        if (history.size > MAX_TURNS) {
            while (history.size > MAX_TURNS) history.removeAt(0)
        }

        // The prompt embeds live device facts, so read them off the main thread.
        val system = withContext(Dispatchers.IO) { CommandRegistry.aiSystemPrompt(context) }
        val result = AiClient.chat(
            context = context,
            messages = history.toList(),
            system = system,
        )
        busy = false

        when (result) {
            is AiResult.Ok -> {
                history.add(AiMessage.assistant(result.text))
                result.text.split("\n").forEach { raw ->
                    val line = raw.trimEnd()
                    val trimmed = line.trim()
                    if (trimmed.uppercase().startsWith("RUN:")) {
                        val command = trimmed.substringAfter(":").trim()
                        if (command.isNotEmpty() && suggestions.size < 3 &&
                            !suggestions.contains(command)
                        ) {
                            suggestions.add(command)
                        }
                        append("  " + trimmed)
                    } else {
                        append(line)
                    }
                }
                append(
                    "via ${result.providerLabel} · ${result.model}" +
                        (result.note?.let { " ($it)" } ?: "")
                )
            }

            is AiResult.Error -> {
                append("UPLINK FAILED :: ${result.message}")
                result.hint?.let { append("  $it") }
            }
        }
    }

    fun submit(raw: String) {
        val command = raw.trim()
        if (command.isEmpty() || busy) return

        append("")
        append("jarvis@local:~$ $command")
        input = ""

        if (command.equals("clear", ignoreCase = true)) {
            lines.clear()
            lines.addAll(CommandRegistry.banner(aiConfigured, aiLabel))
            suggestions.clear()
            history.clear()
            return
        }

        val explicitAi = command.startsWith("?") || command.startsWith("/ai ")
        val question = when {
            command.startsWith("/ai ") -> command.removePrefix("/ai ").trim()
            command.startsWith("?") -> command.removePrefix("?").trim()
            else -> command
        }

        scope.launch {
            if (explicitAi) {
                if (aiConfigured) {
                    ask(question)
                } else {
                    append("INTELLIGENCE :: not configured")
                    append("  add an API key in settings > intelligence")
                }
                return@launch
            }

            busy = true
            val response = CommandRegistry.respond(context, command)
            busy = false
            response.output.forEach { append(it) }

            if (response.unknown) {
                if (aiConfigured) {
                    ask(command)
                } else {
                    append("INTELLIGENCE :: not configured — this was not sent anywhere")
                    append("  add an API key in settings > intelligence")
                }
            }
        }
    }

    LaunchedEffect(lines.size, lines.lastOrNull(), suggestions.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    ModuleScaffold(
        title = "TERMINAL",
        subtitle = "device console · local + ai",
        accent = ElectricCyan,
        statusLabel = if (aiConfigured) "LINKED" else "LOCAL",
        onBack = onBack,
    ) {
        HudPanel(
            label = "SESSION",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(
                        color = if (aiConfigured) StatusGreen else TextMuted,
                        diameter = 4,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (busy) "WORKING" else if (aiConfigured) "LINKED" else "LOCAL",
                        style = HudType.label,
                        color = if (aiConfigured) StatusGreen else TextMuted,
                    )
                }
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        style = HudType.terminal,
                        color = when {
                            line.startsWith("jarvis@") -> IceBlue
                            line.startsWith("UNKNOWN") -> Color(0xFFFF8A98)
                            line.startsWith("UPLINK FAILED") -> Color(0xFFFF8A98)
                            line.startsWith("uplink ::") -> TextMuted
                            line.trimStart().startsWith("RUN:") -> StatusGreen
                            line.startsWith("via ") -> TextMuted
                            else -> TextSecondary
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ElectricCyan.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(">", style = HudType.terminal.copy(fontSize = 13.sp), color = ElectricCyan)
                Spacer(Modifier.width(7.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.take(200) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = HudType.terminal.copy(fontSize = 12.sp, color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(input) }),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BlinkingCursor(height = 11)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (aiConfigured) {
                                            "command, or ask anything"
                                        } else {
                                            "type a command"
                                        },
                                        style = HudType.terminal.copy(fontSize = 12.sp),
                                        color = TextMuted,
                                    )
                                }
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(ElectricCyan.copy(alpha = if (busy) 0.05f else 0.14f), ChipShape)
                        .border(
                            1.dp,
                            ElectricCyan.copy(alpha = if (busy) 0.2f else 0.45f),
                            ChipShape,
                        )
                        .clickable(enabled = !busy) { submit(input) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (busy) "···" else "RUN",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                        color = if (busy) TextMuted else ElectricCyan,
                    )
                }
            }

            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { suggestion ->
                        Box(
                            modifier = Modifier
                                .background(StatusGreen.copy(alpha = 0.10f), ChipShape)
                                .border(1.dp, StatusGreen.copy(alpha = 0.35f), ChipShape)
                                .clickable {
                                    suggestions.clear()
                                    submit(suggestion)
                                }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = "▸ ${suggestion.take(22)}",
                                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 0.6.sp),
                                color = StatusGreen,
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("help", "status", "battery", "routines").forEach { quick ->
                Box(
                    modifier = Modifier
                        .background(Color(0xFF0A1A28), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.20f), ChipShape)
                        .clickable { submit(quick) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = quick.uppercase(),
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = TextSecondary,
                    )
                }
            }
        }

        HudPanel(label = "INTELLIGENCE", accent = if (aiConfigured) StatusGreen else TextMuted) {
            Text(
                text = if (aiConfigured) {
                    "Linked to ${aiLabel ?: "your provider"}. Only the text you ask is sent, " +
                        "straight to your provider — there is no JARVIS server in between. " +
                        "Local commands never use the network."
                } else {
                    "No API key yet, so only local commands work. Add any supported key in " +
                        "settings and this panel starts answering free-form questions too."
                },
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElectricCyan.copy(alpha = 0.10f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.35f), ChipShape)
                    .clickable(onClick = onOpenAiSettings)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (aiConfigured) "MANAGE API KEY" else "ADD API KEY",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                    color = ElectricCyan,
                )
            }
        }
    }
}
