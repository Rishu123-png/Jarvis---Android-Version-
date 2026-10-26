package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.AppRepository
import com.jarvis.assistant.data.InstalledApp
import com.jarvis.assistant.ui.components.AppGridTile
import com.jarvis.assistant.ui.components.BackChevron
import com.jarvis.assistant.ui.components.BlinkingCursor
import com.jarvis.assistant.ui.components.PulseDot
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.DeepNavy
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import java.util.Locale

/**
 * App drawer: every launchable app on the device, from PackageManager.
 *
 * Search filters on label and package name. Section headers group alphabetically unless a query
 * is active, at which point flat results are easier to scan.
 */
@Composable
fun AppDrawerScreen(
    apps: List<InstalledApp>,
    isLoading: Boolean,
    onLaunch: (InstalledApp) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase(Locale.getDefault()).contains(q) ||
                    it.packageName.lowercase(Locale.getDefault()).contains(q)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .background(ElectricCyan.copy(alpha = 0.08f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.28f), ChipShape)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackChevron(color = ElectricCyan)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "HOME",
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
                    color = ElectricCyan,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "APPLICATIONS",
                    style = HudType.value.copy(fontSize = 15.sp, letterSpacing = 3.sp),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = StatusGreen, diameter = 4)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${apps.size} LAUNCHABLE · PACKAGE MANAGER",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(ElectricCyan.copy(alpha = 0.08f), ChipShape)
                    .border(1.dp, ElectricCyan.copy(alpha = 0.25f), ChipShape)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh app list",
                    tint = ElectricCyan.copy(alpha = 0.9f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // ---- search ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(DeepNavy, ChipShape)
                .border(1.dp, ElectricCyan.copy(alpha = 0.22f), ChipShape)
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = ElectricCyan.copy(alpha = 0.75f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(9.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(40) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = HudType.value.copy(fontSize = 12.sp, color = TextPrimary),
                cursorBrush = SolidColor(ElectricCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BlinkingCursor(height = 11)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "search applications",
                                    style = HudType.value.copy(fontSize = 12.sp),
                                    color = TextMuted,
                                )
                            }
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(ElectricCyan.copy(alpha = 0.12f), ChipShape)
                        .clickable { query = "" }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "CLR",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                        color = ElectricCyan,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- grid ----
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && apps.isEmpty() -> LoadingState()
                filtered.isEmpty() -> EmptyState(query = query)
                else -> {
                    val grouped = query.isBlank()
                    val sections = remember(filtered, grouped) {
                        if (grouped) AppRepository.sections(filtered) else emptyList()
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (grouped) {
                            sections.forEach { (letter, group) ->
                                item(
                                    key = "header-$letter",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    SectionLetter(letter = letter, count = group.size)
                                }
                                items(
                                    items = group,
                                    key = { app -> app.key },
                                ) { app ->
                                    AppGridTile(app = app, onClick = { onLaunch(app) })
                                }
                            }
                        } else {
                            items(
                                items = filtered,
                                key = { app -> app.key },
                            ) { app ->
                                AppGridTile(app = app, onClick = { onLaunch(app) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLetter(letter: Char, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = letter.toString(),
            style = HudType.sectionTitle,
            color = ElectricCyan.copy(alpha = 0.85f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(ElectricCyan.copy(alpha = 0.3f), Color.Transparent)
                    )
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = HudType.label.copy(fontSize = 8.sp),
            color = TextMuted,
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulseDot(color = ElectricCyan, diameter = 8)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "INDEXING APPLICATIONS",
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 2.2.sp),
            color = TextSecondary,
        )
    }
}

@Composable
private fun EmptyState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (query.isBlank()) "NO LAUNCHABLE APPS FOUND" else "NO MATCH FOR \"$query\"",
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.8.sp),
            color = TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (query.isBlank()) {
                "PackageManager returned nothing. Tap refresh, and check that the <queries> " +
                    "block is present in the manifest."
            } else {
                "Try a shorter query."
            },
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted.copy(alpha = 0.85f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (query.isBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "JARVIS is hidden here on purpose: it is the Home app itself.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
