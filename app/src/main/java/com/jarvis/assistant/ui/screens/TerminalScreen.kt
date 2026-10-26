package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Local, offline command terminal.
 *
 * Deliberately dumb: commands are answered by [CommandRegistry] on the device, so nothing is
 * sent anywhere. It exists to prove the input -> response -> scroll loop before the AI core
 * (phase 5) owns it.
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val lines = remember { mutableStateListOf<String>().apply { addAll(CommandRegistry.banner) } }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    fun submit(raw: String) {
        val command = raw.trim()
        if (command.isEmpty()) return
        lines.add("")
        lines.add("jarvis@local:~$ $command")
        if (command.equals("clear", ignoreCase = true)) {
            lines.clear()
            lines.addAll(CommandRegistry.banner)
            input = ""
            return
        }
        CommandRegistry.respond(command).output.forEach { lines.add(it) }
        input = ""
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    ModuleScaffold(
        title = "TERMINAL",
        subtitle = "local command shell · no network",
        accent = ElectricCyan,
        phaseLabel = "P1",
        onBack = onBack,
    ) {
        HudPanel(
            label = "SESSION",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = StatusGreen, diameter = 4)
                    Spacer(Modifier.width(5.dp))
                    Text("OFFLINE", style = HudType.label, color = StatusGreen)
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
                            line.startsWith("  [ACTIVE") -> StatusGreen
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
                    onValueChange = { input = it.take(60) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = HudType.terminal.copy(
                        fontSize = 12.sp,
                        color = TextPrimary,
                    ),
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
                                        text = "type a command",
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
                        .background(ElectricCyan.copy(alpha = 0.14f), ChipShape)
                        .border(1.dp, ElectricCyan.copy(alpha = 0.45f), ChipShape)
                        .clickable { submit(input) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "RUN",
                        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                        color = ElectricCyan,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("help", "status", "modules", "phase").forEach { quick ->
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

        HudPanel(label = "NOTE", accent = TextMuted) {
            Text(
                text = "Commands are resolved locally against a fixed table. The AI core that " +
                    "interprets free-form requests is phase 5 and will be opt-in.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}
