package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.ui.theme.DeepNavy
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.PanelNavy
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TileShape

/**
 * Loads an app icon off the main thread, seeded from the cache so scrolling recycles instantly.
 */
@Composable
fun rememberAppIcon(app: InstalledApp, sizePx: Int = 144): ImageBitmap? {
    val context = LocalContext.current
    val state by produceState<ImageBitmap?>(
        initialValue = AppRepository.cachedIcon(app),
        key1 = app.key,
    ) {
        if (value == null) value = AppRepository.iconFor(context, app, sizePx)
    }
    return state
}

/** Icon with a chamfered fallback tile carrying the app's first letter. */
@Composable
fun AppIcon(
    app: InstalledApp,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = rememberAppIcon(app)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = app.label,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(PanelNavy, TileShape)
                .border(1.dp, ElectricCyan.copy(alpha = 0.18f), TileShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.initial,
                style = HudType.value.copy(fontSize = (size.value * 0.42f).sp),
                color = TextMuted,
            )
        }
    }
}

/** Grid cell for the app drawer. */
@Composable
fun AppGridTile(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(110),
        label = "app-tile-scale",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .background(
                if (pressed) ElectricCyan.copy(alpha = 0.10f) else Color.Transparent,
                TileShape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(app = app, size = 44.dp)
        Spacer(Modifier.height(7.dp))
        Text(
            text = app.label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 0.3.sp),
            color = TextPrimary,
            maxLines = 2,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Compact dock slot: icon inside a chamfered bezel. */
@Composable
fun DockSlot(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(110),
        label = "dock-scale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .aspectRatio(1f)
            .background(
                if (pressed) ElectricCyan.copy(alpha = 0.16f) else PanelNavy.copy(alpha = 0.85f),
                TileShape,
            )
            .border(
                1.dp,
                ElectricCyan.copy(alpha = if (pressed) 0.6f else 0.2f),
                TileShape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(app = app, size = 30.dp)
    }
}

/** Empty dock placeholder so the row keeps its rhythm before the app list loads. */
@Composable
fun DockPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(DeepNavy.copy(alpha = 0.6f), TileShape)
            .border(1.dp, ElectricCyan.copy(alpha = 0.08f), TileShape),
    )
}

/** Row of dock slots, padded out with placeholders while the package list loads. */
@Composable
fun AppDock(
    apps: List<InstalledApp>,
    onLaunch: (InstalledApp) -> Unit,
    modifier: Modifier = Modifier,
    slots: Int = 5,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(slots) { index ->
            val app = apps.getOrNull(index)
            if (app != null) {
                DockSlot(
                    app = app,
                    onClick = { onLaunch(app) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                DockPlaceholder(modifier = Modifier.weight(1f))
            }
        }
    }
}
