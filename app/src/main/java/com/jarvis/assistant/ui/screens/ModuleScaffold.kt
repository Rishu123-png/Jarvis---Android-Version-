package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.BackChevron
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary

/**
 * Shared frame for every non-dashboard screen: back affordance, module title, status chip and
 * a scrollable body with consistent gutters.
 */
@Composable
fun ModuleScaffold(
    title: String,
    subtitle: String,
    accent: Color,
    statusLabel: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .background(accent.copy(alpha = 0.08f), ChipShape)
                    .border(1.dp, accent.copy(alpha = 0.28f), ChipShape)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(color = accent)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "HUD",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                    color = accent,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = HudType.value.copy(fontSize = 15.sp, letterSpacing = 3.sp),
                    color = TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle.uppercase(),
                    style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                    color = TextMuted,
                    maxLines = 1,
                )
            }

            HudChip(text = statusLabel, accent = accent)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Big, dimmed icon watermark used at the top of staged (not-yet-functional) screens. */
@Composable
fun StagedWatermark(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    label: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .background(accent.copy(alpha = 0.06f), ChipShape)
                .border(1.dp, accent.copy(alpha = 0.25f), ChipShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.75f),
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 2.4.sp),
            color = accent.copy(alpha = 0.85f),
        )
    }
}
