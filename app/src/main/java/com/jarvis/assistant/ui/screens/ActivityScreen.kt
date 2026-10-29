package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.automation.AutomationStore
import com.jarvis.assistant.automation.Crew
import com.jarvis.assistant.data.CrewSnapshot
import com.jarvis.assistant.data.Knowledge
import com.jarvis.assistant.data.Lane
import com.jarvis.assistant.device.NotifyBrain
import com.jarvis.assistant.device.NotificationRepository
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.PulseDot
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crew board — the honest version of the "agents" screen.
 *
 * Four lanes, each mapped to code that exists: DEVICE (the voice step loop and the screen agent),
 * ROUTINES (the automation runner), RESEARCH (look-ups), ALERTS (what is waiting). A lane is LIVE
 * only while its block is genuinely executing, QUEUED when it is waiting for the phone, and IDLE
 * otherwise.
 *
 * Everything below the lanes is history, read from stores that actually recorded something:
 * `AutomationStore.history()` for finished routines and `Knowledge.cards()` for what he learned.
 * There are no avatars, no invented task counts and no percentage bars, because a number on this
 * screen is a claim, and claims have to be true.
 */
@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by Crew.state.collectAsState()
    val history by Crew.history.collectAsState()

    val runs = remember { AutomationStore.history(context) }
    val cards = remember { Knowledge.cards(context) }
    val waiting = remember {
        if (NotificationRepository.hasAccess(context)) {
            NotifyBrain.countUnreadMessages(context)
        } else {
            -1
        }
    }

    ModuleScaffold(
        title = "ACTIVITY",
        subtitle = "what he is doing, and did",
        accent = IceBlue,
        statusLabel = snapshot.summary(),
        onBack = onBack,
    ) {
        // ---------------------------------------------------------------- the lanes
        HudPanel(
            label = "LANES",
            accent = ElectricCyan,
            trailing = {
                HudChip(
                    text = if (Crew.phoneBusy) "PHONE IN USE" else "PHONE FREE",
                    accent = if (Crew.phoneBusy) Amber else StatusGreen,
                )
            },
        ) {
            Text(
                text = "Looking things up and reading alerts run alongside anything. Anything that " +
                    "touches the screen — a spoken command, a screen task, a routine, the camera — " +
                    "takes one shared lock, so only one of them ever holds the phone.",
                style = HudType.valueSmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(10.dp))

            CrewSnapshot.ORDER.forEachIndexed { index, lane ->
                if (index > 0) {
                    Spacer(Modifier.height(7.dp))
                    HudDivider(accent = ElectricCyan, alpha = 0.16f)
                    Spacer(Modifier.height(7.dp))
                }
                val report = snapshot.of(lane)
                val accent = when {
                    report.queued -> Amber
                    report.busy -> when (lane) {
                        Lane.DEVICE, Lane.AUTOMATE -> ElectricCyan
                        else -> IceBlue
                    }
                    else -> TextMuted
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(26.dp)
                            .background(accent.copy(alpha = 0.85f)),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = lane.label,
                                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
                                color = accent,
                            )
                            Spacer(Modifier.width(7.dp))
                            if (lane.exclusive) {
                                Text(
                                    text = "SOLE",
                                    style = HudType.label.copy(fontSize = 7.sp, letterSpacing = 1.sp),
                                    color = TextMuted,
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = report.line,
                            style = HudType.valueSmall,
                            color = TextSecondary,
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (report.busy && !report.queued) {
                            PulseDot(color = accent, diameter = 4)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = report.status,
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.3.sp),
                            color = accent,
                        )
                    }
                }
            }

            // ALERTS has no execution block of its own; what it can honestly say is the count.
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    waiting < 0 -> "ALERT ACCESS OFF · he cannot see who is waiting"
                    waiting == 0 -> "Nothing waiting in the shade."
                    waiting == 1 -> "1 message waiting for an answer."
                    else -> "$waiting messages waiting for an answer."
                },
                style = HudType.valueSmall,
                color = if (waiting < 0) Amber else TextMuted,
            )
        }

        // ---------------------------------------------------------------- recent runs
        HudPanel(
            label = "RECENT ROUTINES",
            accent = ElectricCyan,
            trailing = { HudChip(text = "${runs.size} RUNS", accent = ElectricCyan) },
        ) {
            if (runs.isEmpty()) {
                Text(
                    text = "No routine has run yet. Build one on the AUTOMATION page and it will " +
                        "show up here with what it actually did.",
                    style = HudType.valueSmall,
                    color = TextMuted,
                )
            } else {
                runs.take(6).forEachIndexed { index, run ->
                    if (index > 0) {
                        Spacer(Modifier.height(6.dp))
                        HudDivider(accent = ElectricCyan, alpha = 0.14f)
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = run.workflowName,
                                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.1.sp),
                                color = TextPrimary,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = buildString {
                                    append(run.triggerLabel.ifBlank { "Manual" })
                                    append(" · ")
                                    append(stamp(run.at))
                                },
                                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                                color = TextMuted,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildString {
                                append("${run.succeeded} ok")
                                if (run.failed > 0) append(" · ${run.failed} failed")
                                if (run.skipped > 0) append(" · ${run.skipped} skipped")
                                if (run.stopped) append(" · stopped")
                            },
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                            color = if (run.ok) StatusGreen else Amber,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- what he learned
        HudPanel(
            label = "LEARNED",
            accent = StatusGreen,
            trailing = { HudChip(text = "${Knowledge.count(context)} CARDS", accent = StatusGreen) },
        ) {
            if (cards.isEmpty()) {
                Text(
                    text = "Nothing on the shelf yet. Ask him something — every answer he looks up " +
                        "is kept here, with its source.",
                    style = HudType.valueSmall,
                    color = TextMuted,
                )
            } else {
                cards.takeLast(4).reversed().forEach { card ->
                    Text(
                        text = "· ${card.topic} · ${card.source.ifBlank { "LOCAL" }}",
                        style = HudType.valueSmall,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- this session
        HudPanel(
            label = "THIS SESSION",
            accent = IceBlue,
            trailing = { HudChip(text = "${history.size} LOGGED", accent = IceBlue) },
        ) {
            if (history.isEmpty()) {
                Text(
                    text = "Nothing since JARVIS started. A spoken command, a look-up or a routine " +
                        "appears here the moment it runs.",
                    style = HudType.valueSmall,
                    color = TextMuted,
                )
            } else {
                history.takeLast(8).reversed().forEach { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(if (record.ok) StatusGreen else Amber, ChipShape),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = record.lane.label,
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                            color = TextMuted,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = record.owner.ifBlank { record.detail },
                            style = HudType.valueSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stamp(record.at),
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                            color = TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .background(TextMuted.copy(alpha = 0.10f), ChipShape)
                            .border(1.dp, TextMuted.copy(alpha = 0.35f), ChipShape)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "CLEAR",
                            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.3.sp),
                            color = TextMuted,
                        )
                    }
                }
            }
        }

        Text(
            text = "Every number here came from a run that happened. Nothing on this page is " +
                "sampled, predicted or styled to look busy.",
            style = HudType.valueSmall,
            color = TextMuted,
        )
    }
}

private fun stamp(at: Long): String = if (at <= 0L) {
    "—"
} else {
    try {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(at))
    } catch (t: Throwable) {
        "—"
    }
}
