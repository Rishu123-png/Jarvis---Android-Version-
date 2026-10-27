package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Clock
// ---------------------------------------------------------------------------------------------

/**
 * One clock tick.
 *
 * [time] is the **12-hour** display form without a leading zero ("2:52"), [meridiem] is AM or PM,
 * and [time24] keeps the 24-hour value for anything technical (logs, terminals, file names). The HUD
 * shows the 12-hour form everywhere it faces the operator.
 */
data class ClockSnapshot(
    val time: String = "--:--",
    val seconds: String = "--",
    val meridiem: String = "",
    val date: String = "",
    val time24: String = "--:--",
) {
    /** "2:52 PM" — the form used in speech and in one-line readouts. */
    val label: String get() = "$time $meridiem".trim()

    /** "2:52:04 PM" — used where the seconds matter. */
    val labelWithSeconds: String get() = "$time:$seconds $meridiem".trim()

    companion object {
        private val timeFmt = DateTimeFormatter.ofPattern("h:mm", Locale.US)
        private val time24Fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        private val secondFmt = DateTimeFormatter.ofPattern("ss", Locale.US)
        private val meridiemFmt = DateTimeFormatter.ofPattern("a", Locale.US)
        private val dateFmt = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.US)

        fun from(now: LocalDateTime): ClockSnapshot = ClockSnapshot(
            time = timeFmt.format(now),
            seconds = secondFmt.format(now),
            meridiem = meridiemFmt.format(now).uppercase(Locale.US),
            date = dateFmt.format(now).uppercase(Locale.US),
            time24 = time24Fmt.format(now),
        )
    }
}

/** Ticks once per second, aligned to the wall-clock second boundary. */
@Composable
fun rememberClock(): ClockSnapshot {
    var snapshot by remember { mutableStateOf(ClockSnapshot.from(LocalDateTime.now())) }
    val resumed = rememberIsResumed()
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            snapshot = ClockSnapshot.from(LocalDateTime.now())
            val millisIntoSecond = System.currentTimeMillis() % 1000L
            delay(1000L - millisIntoSecond)
        }
    }
    return snapshot
}

// ---------------------------------------------------------------------------------------------
// Typewriter
// ---------------------------------------------------------------------------------------------

/**
 * Reveals [lines] character by character. Used for the boot log and the status ticker; keeps
 * the HUD feeling alive without needing a real event source.
 */
@Composable
fun Typewriter(
    lines: List<String>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = HudType.terminal,
    color: Color = TextSecondary,
    charsPerSecond: Int = 60,
    startDelayMillis: Long = 120L,
    loopWithPauseMillis: Long? = null,
) {
    val full = remember(lines) { lines.joinToString("\n") }
    val perChar = (1000L / charsPerSecond.coerceAtLeast(1))

    val revealed by produceState(initialValue = 0, key1 = full) {
        delay(startDelayMillis)
        while (true) {
            var i = 0
            while (i < full.length) {
                i += 1
                value = i
                if (i % 4 == 0) delay(perChar * 4L)
            }
            if (loopWithPauseMillis == null) break
            delay(loopWithPauseMillis)
            value = 0
        }
    }
    Text(
        text = full.take(revealed),
        modifier = modifier,
        style = textStyle,
        color = color,
    )
}

/** Small blinking block caret. */
@Composable
fun BlinkingCursor(
    modifier: Modifier = Modifier,
    color: Color = ElectricCyan,
    width: Int = 7,
    height: Int = 12,
) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(620),
            RepeatMode.Reverse,
        ),
        label = "cursor-alpha",
    )
    Box(
        modifier = modifier
            .size(width = width.dp, height = height.dp)
            .background(color.copy(alpha = alpha)),
    )
}

/** A dot that pulses — used for "live" indicators. */
@Composable
fun PulseDot(
    modifier: Modifier = Modifier,
    color: Color = ElectricCyan,
    diameter: Int = 6,
) {
    val transition = rememberInfiniteTransition(label = "pulse-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse-dot-alpha",
    )
    Box(
        modifier = modifier
            .size(diameter.dp)
            .background(color.copy(alpha = alpha), androidx.compose.foundation.shape.CircleShape),
    )
}

/** Small caps label with wide tracking — the HUD's "field name" style. */
@Composable
fun HudLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4E6E85),
) {
    Text(
        text = text,
        modifier = modifier,
        style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.6.sp),
        color = color,
        maxLines = 1,
    )
}
