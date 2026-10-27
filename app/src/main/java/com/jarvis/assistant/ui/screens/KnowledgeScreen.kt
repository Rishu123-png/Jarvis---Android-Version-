package com.jarvis.assistant.ui.screens

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.Knowledge
import com.jarvis.assistant.data.ResearchDesk
import com.jarvis.assistant.device.Research
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
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
import com.jarvis.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The knowledge shelf, and the permission desk in front of the internet.
 *
 * This screen exists for one reason: a thing that learns by itself must be inspectable. Every
 * lookup he has ever done is listed here with its source and its date, each one can be deleted on
 * its own, and the switch that decides whether he asks first is at the top where it cannot be
 * missed.
 *
 * It is also the only place the feature can be used without speaking — type a question, and the
 * same permission rules apply.
 */
@Composable
fun KnowledgeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cards by remember { mutableStateOf(Knowledge.cards(context)) }
    var mode by remember { mutableStateOf(ResearchDesk.mode(context)) }
    var pending by remember { mutableStateOf(ResearchDesk.pending(context)) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val lookUp: (String, Boolean) -> Unit = { topic, approved ->
        val asked = topic.trim()
        if (asked.isBlank()) {
            note = "Give him something to look up."
        } else {
            val known = Knowledge.find(context, asked)
            if (known != null) {
                note = known.summary
            } else if (!approved && ResearchDesk.shouldAsk(context)) {
                ResearchDesk.setPending(context, asked)
                pending = ResearchDesk.pending(context)
                note = null
            } else {
                busy = true
                note = null
                scope.launch {
                    val outcome = try {
                        Research.look(asked)
                    } catch (t: Throwable) {
                        Research.Outcome.Unreachable(t.javaClass.simpleName)
                    }
                    when (outcome) {
                        is Research.Outcome.Found -> {
                            Knowledge.save(
                                context,
                                Knowledge.Card(
                                    topic = outcome.finding.topic,
                                    summary = outcome.finding.summary,
                                    source = outcome.finding.source,
                                    url = outcome.finding.url,
                                ),
                            )
                            cards = Knowledge.cards(context)
                            note = outcome.finding.summary
                        }

                        is Research.Outcome.Missing -> {
                            note = "Nothing solid came back for \"$asked\". Try another wording."
                        }

                        is Research.Outcome.Unreachable -> {
                            note = "No internet right now. What is on this shelf still works offline."
                        }
                    }
                    ResearchDesk.clearPending(context)
                    pending = null
                    busy = false
                }
            }
        }
    }

    ModuleScaffold(
        title = "KNOWLEDGE",
        subtitle = "learned · offline · yours",
        accent = IceBlue,
        statusLabel = "${cards.size} CARDS",
        onBack = onBack,
    ) {
        // ---------------------------------------------------------------- the ask dial
        HudPanel(
            label = "ASK BEFORE HE LOOKS",
            accent = if (mode == ResearchDesk.Mode.ASK_NEVER) Amber else StatusGreen,
            trailing = { HudChip(text = mode.name.replace('_', ' '), accent = IceBlue) },
        ) {
            Text(
                text = "Looking something up is the only thing JARVIS does that leaves this phone: " +
                    "a question goes to a public encyclopedia and the answer comes back. " +
                    "Nothing about you is ever sent, and he can only read — never post, send or " +
                    "sign in to anything.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            ResearchDesk.Mode.entries.forEach { option ->
                val selected = option == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .background(
                            if (selected) IceBlue.copy(alpha = 0.10f) else Color.Transparent,
                            ChipShape,
                        )
                        .border(
                            1.dp,
                            if (selected) IceBlue.copy(alpha = 0.45f) else PanelNavy,
                            ChipShape,
                        )
                        .clickable {
                            ResearchDesk.setMode(context, option)
                            mode = option
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .background(
                                if (selected) IceBlue else TextMuted.copy(alpha = 0.35f),
                                ChipShape,
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = option.label,
                            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                            color = if (selected) IceBlue else TextSecondary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = option.caption,
                            style = HudType.valueSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- the pending question
        pending?.let { waiting ->
            HudPanel(
                label = "WAITING FOR YOUR YES",
                accent = ElectricCyan,
                trailing = { HudChip(text = "PERMISSION", accent = ElectricCyan) },
            ) {
                Text(
                    text = "\"Shall I look up ${waiting.topic} on the internet?\"",
                    style = HudType.valueSmall,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("LOOK IT UP", ElectricCyan) {
                        lookUp(waiting.topic, true)
                    }
                    ActionChip("LEAVE IT", TextMuted) {
                        ResearchDesk.clearPending(context)
                        pending = null
                    }
                }
            }
        }

        // ---------------------------------------------------------------- ask it here
        HudPanel(label = "LOOK SOMETHING UP", accent = ElectricCyan) {
            Text(
                text = "Type a question the way you would say it out loud: “what is a black hole”, " +
                    "“who was Nikola Tesla”, “define serendipity”.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PanelNavy, ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.30f), ChipShape)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "Ask him anything",
                        style = HudType.valueSmall,
                        color = TextMuted.copy(alpha = 0.7f),
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = HudType.valueSmall.copy(color = TextPrimary),
                    cursorBrush = SolidColor(ElectricCyan),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { lookUp(draft, false) }),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(if (busy) "LOOKING…" else "LOOK UP", ElectricCyan) {
                    if (!busy) lookUp(draft, false)
                }
                if (draft.isNotBlank()) {
                    ActionChip("CLEAR", TextMuted) { draft = "" }
                }
            }
            note?.let { line ->
                Spacer(Modifier.height(10.dp))
                Text(text = line, style = HudType.valueSmall, color = TextSecondary)
            }
        }

        // ---------------------------------------------------------------- the shelf
        HudPanel(
            label = "WHAT HE KNOWS",
            accent = StatusGreen,
            trailing = { HudChip(text = "${cards.size}/${Knowledge.MAX_CARDS}", accent = StatusGreen) },
        ) {
            if (cards.isEmpty()) {
                Text(
                    text = "Nothing yet. The first answer he looks up lands here and then works " +
                        "offline, forever, without asking anybody.",
                    style = HudType.valueSmall,
                    color = TextMuted,
                )
            } else {
                cards.reversed().forEachIndexed { index, card ->
                    if (index > 0) {
                        Spacer(Modifier.height(8.dp))
                        HudDivider(accent = StatusGreen, alpha = 0.2f)
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = card.topic,
                                style = HudType.label.copy(fontSize = 10.sp, letterSpacing = 1.2.sp),
                                color = IceBlue,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = card.summary,
                                style = HudType.valueSmall,
                                color = TextSecondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${card.source.ifBlank { "LOCAL" }} · ${stamp(card.at)}",
                                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                                color = TextMuted,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ActionChip("FORGET", AlertRed) {
                            Knowledge.forget(context, card.topic)
                            cards = Knowledge.cards(context)
                        }
                    }
                }
            }

            if (cards.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ActionChip("CLEAR THE SHELF", AlertRed) {
                    Knowledge.clear(context)
                    cards = Knowledge.cards(context)
                }
            }
        }

        Text(
            text = "Sources: Wikipedia and a free dictionary. No account, no key, no bill. " +
                "Every card can be deleted here, and deleting one never touches anything else " +
                "he remembers.",
            style = HudType.valueSmall,
            color = TextMuted,
        )
    }
}

private fun stamp(at: Long): String = if (at <= 0L) {
    "saved"
} else {
    try {
        SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date(at))
    } catch (t: Throwable) {
        "saved"
    }
}

/** A small square key, local to this screen so it does not inherit the settings keypad. */
@Composable
private fun ActionChip(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.08f), ChipShape)
            .border(1.dp, accent.copy(alpha = 0.40f), ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.3.sp),
            color = accent,
            maxLines = 1,
        )
    }
}
