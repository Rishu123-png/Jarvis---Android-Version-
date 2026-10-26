package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.DeepNavy
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TileShape
import com.jarvis.assistant.data.HudModule

/**
 * The hexagonal-ish chamfered shortcut tile used for every HUD module.
 *
 * Also the place where the roadmap becomes visible to the user: tiles whose capability lands
 * that are not built yet are marked SOON instead of pretending to work.
 */
@Composable
fun HudTile(
    module: HudModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(120),
        label = "tile-scale",
    )
    val accent = module.accent

    Column(
        modifier = modifier
            .aspectRatio(1.02f)
            .scale(scale)
            .background(
                Brush.verticalGradient(
                    listOf(
                        if (pressed) accent.copy(alpha = 0.16f) else PanelNavy,
                        DeepNavy,
                    )
                ),
                TileShape,
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = if (pressed) 0.85f else 0.38f),
                        accent.copy(alpha = 0.10f),
                    )
                ),
                shape = TileShape,
            )
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    // Diagonal glow sweep from the top-left corner.
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                            start = Offset(0f, 0f),
                            end = Offset(size.width * 0.75f, 0f),
                        ),
                        start = Offset(0f, 1f),
                        end = Offset(size.width * 0.75f, 1f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(accent.copy(alpha = 0.10f), ChipShape)
                    .border(1.dp, accent.copy(alpha = 0.28f), ChipShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = module.icon,
                    contentDescription = module.title,
                    tint = accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (badge != null && badge != "0") {
                Box(
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.18f), com.jarvis.assistant.ui.theme.ChipShape)
                        .border(1.dp, accent.copy(alpha = 0.5f), com.jarvis.assistant.ui.theme.ChipShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = HudType.label.copy(fontSize = 9.sp),
                        color = accent,
                    )
                }
            } else {
                Text(
                    text = if (module.isLive) "LIVE" else "SOON",
                    style = HudType.label,
                    color = if (module.isLive) accent else TextMuted,
                )
            }
        }

        Column {
            Text(
                text = module.title,
                style = HudType.value,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = module.caption.uppercase(),
                style = HudType.label.copy(letterSpacing = 1.sp),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Compact telemetry readout for the stat strip: label, value, optional micro meter.
 */
@Composable
fun TelemetryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = com.jarvis.assistant.ui.theme.ElectricCyan,
    progress: Float? = null,
    caption: String? = null,
) {
    Column(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(PanelNavy, DeepNavy)), ChipShape)
            .border(1.dp, accent.copy(alpha = 0.18f), ChipShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp),
            color = TextMuted,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = HudType.value,
            color = accent,
            maxLines = 1,
        )
        if (progress != null) {
            Spacer(Modifier.height(5.dp))
            HudMeter(progress = progress, accent = accent, height = 3.dp)
        } else if (caption != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = caption,
                style = HudType.label.copy(fontSize = 8.sp),
                color = TextMuted,
                maxLines = 1,
            )
        }
    }
}

/** Section header used on scrollable screens: small caps title plus a rule. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = com.jarvis.assistant.ui.theme.ElectricCyan,
    trailing: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = HudType.sectionTitle, color = accent.copy(alpha = 0.9f))
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.35f), Color.Transparent)
                    )
                ),
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(text = trailing, style = HudType.label, color = TextMuted)
        }
    }
}
