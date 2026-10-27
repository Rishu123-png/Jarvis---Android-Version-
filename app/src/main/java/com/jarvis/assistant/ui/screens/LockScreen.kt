package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.device.Privacy
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary

/**
 * The privacy gate.
 *
 * A numeric keypad and nothing else: no app names, no AI history, no last command, no clock large
 * enough to guess what time the operator was last active. Wrong attempts are counted and throttled by
 * the caller; the PIN itself is compared against a PBKDF2 digest, never stored.
 */
@Composable
fun LockScreen(
    onUnlock: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var entry by remember { mutableStateOf("") }
    var failures by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    fun submit(pin: String) {
        if (pin.length < Privacy.MIN_DIGITS) {
            note = "PIN is at least ${Privacy.MIN_DIGITS} digits"
            return
        }
        if (onUnlock(pin)) {
            entry = ""
            note = null
        } else {
            failures += 1
            entry = ""
            note = if (failures >= 3) "Wrong PIN · $failures attempts" else "Wrong PIN"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "J.A.R.V.I.S.",
            style = HudType.value.copy(fontSize = 15.sp, letterSpacing = 5.sp),
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(color = if (failures > 0) AlertRed else ElectricCyan, diameter = 5)
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (failures > 0) "ACCESS DENIED" else "LOCKED",
                style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 2.4.sp),
                color = if (failures > 0) AlertRed else ElectricCyan,
            )
        }

        Spacer(Modifier.height(30.dp))

        // Entry dots.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(Privacy.MAX_DIGITS) { index ->
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(12.dp)
                        .background(
                            if (index < entry.length) ElectricCyan else PanelNavy,
                            ChipShape,
                        )
                        .border(1.dp, ElectricCyan.copy(alpha = 0.5f), ChipShape),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = note ?: "ENTER PIN",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.8.sp),
            color = if (note != null) AlertRed else TextMuted,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "<"),
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.35f)
                            .padding(vertical = 4.dp)
                            .background(PanelNavy.copy(alpha = 0.9f), ChipShape)
                            .border(1.dp, ElectricCyan.copy(alpha = 0.22f), ChipShape)
                            .clickable {
                                when (key) {
                                    "C" -> {
                                        entry = ""
                                        note = null
                                    }
                                    "<" -> entry = entry.dropLast(1)
                                    else -> {
                                        if (entry.length < Privacy.MAX_DIGITS) entry += key
                                        if (entry.length == Privacy.MAX_DIGITS) submit(entry)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "<") {
                            Text(
                                text = "DEL",
                                style = HudType.label.copy(fontSize = 10.sp, letterSpacing = 1.4.sp),
                                color = ElectricCyan,
                            )
                        } else {
                            Text(
                                text = key,
                                style = HudType.clock.copy(fontSize = 22.sp),
                                color = if (key == "C") TextMuted else TextPrimary,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HudChip(text = "PRIVACY LOCK", accent = StatusGreen)
    }
}
