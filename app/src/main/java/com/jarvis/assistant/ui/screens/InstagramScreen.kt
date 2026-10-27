package com.jarvis.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ai.AiClient
import com.jarvis.assistant.ai.AiMessage
import com.jarvis.assistant.ai.AiResult
import com.jarvis.assistant.social.IgPlatform
import com.jarvis.assistant.social.InstagramAnalytics
import com.jarvis.assistant.social.InstagramClient
import com.jarvis.assistant.social.InstagramStore
import com.jarvis.assistant.ui.components.HudChip
import com.jarvis.assistant.ui.components.HudDivider
import com.jarvis.assistant.ui.components.HudPanel
import com.jarvis.assistant.ui.components.ReadoutRow
import com.jarvis.assistant.ui.theme.AlertRed
import com.jarvis.assistant.ui.theme.Amber
import com.jarvis.assistant.ui.theme.ChipShape
import com.jarvis.assistant.ui.theme.ElectricCyan
import com.jarvis.assistant.ui.theme.HudType
import com.jarvis.assistant.ui.theme.IceBlue
import com.jarvis.assistant.ui.theme.StatusGreen
import com.jarvis.assistant.ui.theme.TextMuted
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

/** A cached sync older than this refreshes itself when the module is opened. */
private const val STALE_AFTER_MS = 15 * 60 * 1000L

/**
 * Instagram creator analytics.
 *
 * The design rules are the same as the rest of JARVIS, applied to a network-backed module:
 *
 *  * the operator's token is entered here, encrypted, and never leaves the device except to Meta;
 *  * every number on screen came from Meta in this sync or a previous one — the age of the reading
 *    is always visible, because a stale follower count presented as live is a lie;
 *  * a metric Meta refused is shown as missing with the reason, never as zero;
 *  * opening the screen is cheap: the cache paints first, the network only runs when it is stale or
 *    when REFRESH is tapped.
 */
@Composable
fun InstagramScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(InstagramStore.isConnected(context)) }
    var platform by remember { mutableStateOf(InstagramStore.platform(context)) }
    var tokenDraft by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(InstagramStore.cachedSnapshot(context)) }
    var growth by remember { mutableStateOf(InstagramStore.growth(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteOk by remember { mutableStateOf(true) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var analysing by remember { mutableStateOf(false) }

    fun sync() {
        if (busy) return
        busy = true
        note = "Contacting Meta…"
        noteOk = true
        scope.launch {
            when (val result = InstagramClient.fetch(context)) {
                is InstagramClient.Result.Ok -> {
                    snapshot = result.snapshot
                    growth = InstagramStore.growth(context)
                    note = "Synced ${result.snapshot.media.size} post(s)."
                    noteOk = true
                }

                is InstagramClient.Result.Error -> {
                    note = result.message + (result.hint?.let { " · $it" } ?: "")
                    noteOk = false
                }
            }
            busy = false
        }
    }

    fun connect() {
        if (busy) return
        val problem = InstagramStore.save(context, tokenDraft, platform)
        if (problem != null) {
            note = problem
            noteOk = false
            return
        }
        tokenDraft = ""
        connected = true
        analysis = null
        sync()
    }

    fun disconnect() {
        InstagramStore.clear(context)
        connected = false
        snapshot = null
        growth = emptyList()
        analysis = null
        note = "Disconnected. The token and cached data were deleted."
        noteOk = true
    }

    // Cache first; fetch only when the reading has gone stale.
    LaunchedEffect(Unit) {
        if (connected && InstagramStore.cacheAgeMs(context) > STALE_AFTER_MS) sync()
    }

    val stats = snapshot?.let { InstagramAnalytics.compute(it, growth) }

    ModuleScaffold(
        title = "INSTAGRAM",
        subtitle = "creator analytics",
        accent = ElectricCyan,
        statusLabel = when {
            busy -> "SYNCING"
            !connected -> "OFFLINE"
            else -> "LINKED"
        },
        onBack = onBack,
    ) {
        if (!connected) {
            ConnectPanel(
                platform = platform,
                tokenDraft = tokenDraft,
                busy = busy,
                onPlatformChange = { platform = it },
                onTokenChange = { tokenDraft = it },
                onConnect = { connect() },
            )
        } else {
            snapshot?.let { snap ->
                HudPanel(
                    label = "PROFILE",
                    accent = ElectricCyan,
                    trailing = { HudChip(text = snap.platform.label, accent = ElectricCyan) },
                ) {
                    Text(
                        text = snap.profile.handle,
                        style = HudType.value.copy(fontSize = 18.sp, letterSpacing = 1.6.sp),
                        color = TextPrimary,
                    )
                    if (snap.profile.name.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = snap.profile.name,
                            style = HudType.terminal.copy(fontSize = 11.sp),
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    ReadoutRow(
                        label = "FOLLOWERS",
                        value = snap.profile.followers.toString(),
                        valueColor = TextPrimary,
                    )
                    ReadoutRow(label = "FOLLOWING", value = snap.profile.follows.toString())
                    ReadoutRow(label = "POSTS", value = snap.profile.mediaCount.toString())
                    ReadoutRow(label = "TYPE", value = snap.profile.accountType.uppercase())
                    ReadoutRow(label = "READING", value = ageLabel(snap.fetchedAt))
                    if (snap.profile.biography.isNotBlank()) {
                        HudDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = snap.profile.biography.take(160),
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted,
                        )
                    }
                }

                stats?.let { analysisStats ->
                    val delta = analysisStats.followerDelta
                    val deltaText = when {
                        delta == null -> "FIRST READING"
                        delta > 0 -> "+$delta"
                        else -> delta.toString()
                    }
                    val deltaColor = when {
                        delta == null -> TextMuted
                        delta > 0 -> StatusGreen
                        else -> AlertRed
                    }

                    HudPanel(label = "ENGAGEMENT", accent = StatusGreen) {
                        ReadoutRow(
                            label = "AVG RATE",
                            value = percent(analysisStats.engagementRate),
                            valueColor = rateColor(analysisStats.engagementRate),
                        )
                        ReadoutRow(
                            label = "AVG INTERACTIONS",
                            value = analysisStats.averageEngagement.toInt().toString(),
                        )
                        ReadoutRow(label = "AVG LIKES", value = analysisStats.averageLikes.toInt().toString())
                        ReadoutRow(
                            label = "AVG COMMENTS",
                            value = analysisStats.averageComments.toInt().toString(),
                        )
                        ReadoutRow(
                            label = "OF REACH INTERACTED",
                            value = analysisStats.reachCoverage?.let { percent(it) } ?: "NOT RETURNED",
                            valueColor = if (analysisStats.reachCoverage == null) TextMuted else TextPrimary,
                        )
                        snap.accountReach?.let { reach ->
                            ReadoutRow(label = "REACH · REPORTED WINDOW", value = reach.toString())
                        }
                        snap.profileViews?.let { views ->
                            ReadoutRow(label = "PROFILE VIEWS", value = views.toString())
                        }
                        ReadoutRow(
                            label = "FOLLOWER CHANGE",
                            value = deltaText,
                            valueColor = deltaColor,
                        )
                        ReadoutRow(
                            label = "WINDOW",
                            value = "${analysisStats.postCount} POSTS · ${analysisStats.windowDays} DAYS",
                        )
                    }

                    if (analysisStats.formats.size >= 2) {
                        HudPanel(label = "FORMAT BREAKDOWN", accent = IceBlue) {
                            analysisStats.formats.forEach { format ->
                                ReadoutRow(
                                    label = "${formatLabel(format.format)} · ${format.posts}",
                                    value = percent(format.averageRate),
                                    valueColor = TextPrimary,
                                )
                            }
                            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = "Rate is average interactions over current followers, so formats stay comparable even when reach was not returned.",
                                style = HudType.terminal.copy(fontSize = 10.sp),
                                color = TextMuted,
                            )
                        }
                    }

                    HudPanel(label = "TIMING", accent = Amber) {
                        ReadoutRow(
                            label = "BEST DAY",
                            value = analysisStats.bestDay?.uppercase() ?: "NOT ENOUGH POSTS",
                            valueColor = if (analysisStats.bestDay == null) TextMuted else TextPrimary,
                        )
                        ReadoutRow(
                            label = "BEST HOUR",
                            value = analysisStats.bestHour?.let { hour -> String.format(Locale.US, "%02d:00", hour) }
                                ?: "NOT ENOUGH POSTS",
                            valueColor = if (analysisStats.bestHour == null) TextMuted else TextPrimary,
                        )
                        ReadoutRow(
                            label = "CADENCE",
                            value = analysisStats.postsPerWeek?.let { "${oneDecimal(it)} / WEEK" } ?: "—",
                        )
                        ReadoutRow(
                            label = "LAST POST",
                            value = analysisStats.daysSinceLastPost?.let { days ->
                                if (days == 0L) "TODAY" else "$days DAYS AGO"
                            } ?: "—",
                        )
                        HudDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Timing is only claimed when at least two posts share the day or hour bucket. One lucky post is not a pattern.",
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted,
                        )
                    }

                    if (analysisStats.top.isNotEmpty()) {
                        HudPanel(label = "TOP POSTS", accent = StatusGreen) {
                            analysisStats.top.forEach { stat ->
                                PostRow(stat = stat, onOpen = {
                                    openUrl(context, stat.media.permalink)
                                })
                            }
                        }
                    }

                    HudPanel(label = "WHAT THE DATA SAYS", accent = ElectricCyan) {
                        analysisStats.findings.forEach { finding ->
                            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    text = "▸ ",
                                    style = HudType.terminal.copy(fontSize = 10.sp),
                                    color = ElectricCyan,
                                )
                                Text(
                                    text = finding,
                                    style = HudType.terminal.copy(fontSize = 10.sp),
                                    color = TextSecondary,
                                )
                            }
                        }
                    }

                    if (snap.notes.isNotEmpty()) {
                        HudPanel(label = "MISSING METRICS", accent = Amber) {
                            snap.notes.forEach { noteLine ->
                                Text(
                                    text = "· $noteLine",
                                    style = HudType.terminal.copy(fontSize = 9.sp),
                                    color = TextMuted,
                                )
                            }
                        }
                    }

                    HudPanel(label = "AI BRIEFING", accent = ElectricCyan) {
                        Text(
                            text = "Sends the numbers above to your own AI key and comes back with a short, concrete plan. Nothing is sent to Meta or to JARVIS — only to the provider you configured.",
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                        ActionRow(
                            label = if (analysing) "THINKING…" else "ANALYSE WITH AI",
                            icon = Icons.Outlined.AutoAwesome,
                            accent = ElectricCyan,
                            enabled = !analysing,
                            onClick = {
                                if (!analysing) {
                                    analysing = true
                                    analysis = null
                                    val input = analysisStats.briefingInput
                                    scope.launch {
                                        when (
                                            val result = AiClient.chat(
                                                context,
                                                listOf(AiMessage.user(input)),
                                                InstagramAnalytics.BRIEFING_SYSTEM_PROMPT,
                                            )
                                        ) {
                                            is AiResult.Ok -> analysis = result.text
                                            is AiResult.Error -> {
                                                analysis = null
                                                note = result.message +
                                                    (result.hint?.let { " · $it" } ?: "")
                                                noteOk = false
                                            }
                                        }
                                        analysing = false
                                    }
                                }
                            },
                        )
                        analysis?.let { text ->
                            HudDivider(modifier = Modifier.padding(vertical = 10.dp))
                            Text(
                                text = text,
                                style = HudType.terminal.copy(fontSize = 10.sp),
                                color = TextPrimary,
                            )
                        }
                    }

                    HudPanel(label = "CONNECTION", accent = TextMuted) {
                        ReadoutRow(label = "PLATFORM", value = snap.platform.label)
                        ReadoutRow(label = "TOKEN", value = InstagramStore.maskedToken(context))
                        ReadoutRow(label = "SYNCED", value = ageLabel(snap.fetchedAt))
                        ReadoutRow(label = "POSTS READ", value = snap.media.size.toString())
                        Spacer(Modifier.height(10.dp))
                        ActionRow(
                            label = if (busy) "SYNCING…" else "REFRESH DATA",
                            icon = Icons.Outlined.Autorenew,
                            accent = ElectricCyan,
                            enabled = !busy,
                            onClick = { sync() },
                        )
                        Spacer(Modifier.height(7.dp))
                        ActionRow(
                            label = "EXTEND TOKEN (60 DAYS)",
                            icon = Icons.Outlined.Lock,
                            accent = IceBlue,
                            enabled = !busy && snap.platform == IgPlatform.INSTAGRAM_LOGIN,
                            onClick = {
                                if (!busy) {
                                    busy = true
                                    scope.launch {
                                        when (val result = InstagramClient.refreshToken(context)) {
                                            is InstagramClient.TokenResult.Ok -> {
                                                note = "Token extended for ${result.days} more day(s)."
                                                noteOk = true
                                            }

                                            is InstagramClient.TokenResult.Error -> {
                                                note = result.message +
                                                    (result.hint?.let { " · $it" } ?: "")
                                                noteOk = false
                                            }
                                        }
                                        busy = false
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(7.dp))
                        ActionRow(
                            label = "DISCONNECT",
                            icon = Icons.Outlined.Link,
                            accent = AlertRed,
                            enabled = !busy,
                            onClick = { disconnect() },
                        )
                    }
                }
            } ?: run {
                HudPanel(label = "NO READING YET", accent = Amber) {
                    Text(
                        text = "The account is linked but no successful sync has completed. Tap REFRESH, or re-check that the account is a Creator or Business account.",
                        style = HudType.terminal.copy(fontSize = 10.sp),
                        color = TextMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    ActionRow(
                        label = if (busy) "SYNCING…" else "SYNC NOW",
                        icon = Icons.Outlined.Autorenew,
                        accent = ElectricCyan,
                        enabled = !busy,
                        onClick = { sync() },
                    )
                }
            }
        }

        note?.let { message ->
            HudPanel(label = "STATUS", accent = if (noteOk) StatusGreen else AlertRed) {
                Text(
                    text = message,
                    style = HudType.terminal.copy(fontSize = 10.sp),
                    color = if (noteOk) TextSecondary else AlertRed,
                )
            }
        }

        LimitsPanel()
    }
}

// ------------------------------------------------------------------------------------ pieces

@Composable
private fun ConnectPanel(
    platform: IgPlatform,
    tokenDraft: String,
    busy: Boolean,
    onPlatformChange: (IgPlatform) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    HudPanel(label = "CONNECT", accent = ElectricCyan) {
        Text(
            text = "JARVIS reads your own account through Meta's official API. It needs an access token for a professional (Creator or Business) account — personal accounts have no API at all.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = "TOKEN SOURCE",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        IgPlatform.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(
                        if (platform == option) ElectricCyan.copy(alpha = 0.12f) else Color.Transparent,
                        ChipShape,
                    )
                    .border(
                        1.dp,
                        if (platform == option) ElectricCyan.copy(alpha = 0.6f) else TextMuted.copy(alpha = 0.3f),
                        ChipShape,
                    )
                    .clickable { onPlatformChange(option) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = option.label,
                    style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
                    color = if (platform == option) ElectricCyan else TextSecondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = option.note,
                    style = HudType.terminal.copy(fontSize = 9.sp),
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "ACCESS TOKEN",
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF050B12), ChipShape)
                .border(1.dp, ElectricCyan.copy(alpha = 0.28f), ChipShape)
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = tokenDraft,
                onValueChange = onTokenChange,
                singleLine = true,
                textStyle = HudType.terminal.copy(fontSize = 11.sp, color = TextPrimary),
                cursorBrush = SolidColor(ElectricCyan),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (tokenDraft.isEmpty()) {
                        Text(
                            text = "IGAA… or EAAG…",
                            style = HudType.terminal.copy(fontSize = 11.sp),
                            color = TextMuted.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                },
            )
            HudChip(text = "SECURE", accent = StatusGreen)
        }

        Spacer(Modifier.height(10.dp))
        ActionRow(
            label = if (busy) "CONNECTING…" else "CONNECT",
            icon = Icons.Outlined.Link,
            accent = ElectricCyan,
            enabled = !busy && tokenDraft.isNotBlank(),
            onClick = onConnect,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The token is encrypted with the Android Keystore before it is written, and is sent only to Meta. Revoke it any time in Instagram → Settings → Apps and websites.",
            style = HudType.terminal.copy(fontSize = 9.sp),
            color = TextMuted,
        )
    }

    HudPanel(label = "HOW TO GET A TOKEN", accent = IceBlue) {
        Text(
            text = "1 · Switch the account to Creator or Business in the Instagram app.\n" +
                "2 · Create a free app at developers.facebook.com and add the Instagram product.\n" +
                "3 · Request instagram_business_basic and instagram_business_manage_insights.\n" +
                "4 · Generate a long-lived token for your own account and paste it above.\n\n" +
                "A quick personal alternative: Graph API Explorer can mint a short-lived token in seconds, which JARVIS can then extend to 60 days.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        ActionRow(
            label = "OPEN META FOR DEVELOPERS",
            icon = Icons.Outlined.OpenInNew,
            accent = IceBlue,
            enabled = true,
            onClick = {},
            url = "https://developers.facebook.com/apps/",
        )
    }
}

@Composable
private fun PostRow(
    stat: com.jarvis.assistant.social.MediaStat,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stat.media.format,
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                color = IceBlue,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stat.media.whenLabel,
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = percent(stat.ratePercent),
                style = HudType.value.copy(fontSize = 11.sp),
                color = rateColor(stat.ratePercent),
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "♥ ${stat.media.likes}   ✎ ${stat.media.comments}" +
                    (stat.media.saved?.let { "   ⤓ $it" } ?: ""),
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            if (stat.media.permalink.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "OPEN",
                        style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
                        color = ElectricCyan,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "“${stat.media.captionPreview}”",
            style = HudType.terminal.copy(fontSize = 9.sp),
            color = TextMuted,
        )
    }
}

@Composable
private fun LimitsPanel() {
    HudPanel(label = "WHAT THIS CANNOT SEE", accent = TextMuted) {
        ReadoutRow(label = "DIRECT MESSAGES", value = "NOT REQUESTED", valueColor = TextMuted)
        ReadoutRow(label = "OTHER ACCOUNTS", value = "NOT AVAILABLE", valueColor = TextMuted)
        ReadoutRow(label = "AUDIENCE DEMOGRAPHICS", value = "FACEBOOK LOGIN ONLY", valueColor = TextMuted)
        ReadoutRow(label = "HASHTAG TRENDS", value = "NOT AVAILABLE", valueColor = TextMuted)
        ReadoutRow(label = "ADS AND SPEND", value = "SEPARATE API", valueColor = TextMuted)
        HudDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "There is no API that grants “all access” to Instagram, and anything claiming to would be scraping. JARVIS reads your own profile, your own posts and the insights Meta exposes for them — nothing else, and nothing about anyone else.",
            style = HudType.terminal.copy(fontSize = 10.sp),
            color = TextMuted,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    url: String? = null,
) {
    val context = LocalContext.current
    val tint = if (enabled) accent else TextMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) accent.copy(alpha = 0.12f) else Color.Transparent, ChipShape)
            .border(1.dp, tint.copy(alpha = if (enabled) 0.55f else 0.25f), ChipShape)
            .clickable(enabled = enabled) {
                if (url != null) openUrl(context, url) else onClick()
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
            color = tint,
        )
    }
}

// ----------------------------------------------------------------------------------- helpers

private fun percent(value: Double): String = String.format(Locale.US, "%.2f%%", value)

private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun rateColor(rate: Double): Color = when {
    rate >= 3.0 -> StatusGreen
    rate >= 1.0 -> ElectricCyan
    else -> Amber
}

private fun formatLabel(format: String): String = when (format) {
    "REELS" -> "REELS"
    "CAROUSEL" -> "CAROUSEL"
    "IMAGE" -> "IMAGE"
    "VIDEO" -> "VIDEO"
    else -> format
}

private fun ageLabel(at: Long): String {
    if (at <= 0L) return "NEVER"
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1 -> "JUST NOW"
        minutes < 60 -> "$minutes MIN AGO"
        minutes < 1_440 -> "${minutes / 60} H AGO"
        else -> "${minutes / 1_440} D AGO"
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (t: Throwable) {
        // No browser, or no such activity: nothing to do, and nothing worth crashing over.
    }
}
