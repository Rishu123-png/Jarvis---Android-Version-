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
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.connect.ConnectorStore
import com.jarvis.assistant.connect.GoogleData
import com.jarvis.assistant.connect.OAuthFlows
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

/**
 * Account linking.
 *
 * Every connector here follows the same honest pattern: the operator registers their own free app
 * with the provider once, approves access on the provider's **own login page** in the browser, and
 * pastes back the short-lived authorization code. JARVIS exchanges that code itself, on the device,
 * for a token it can refresh. No JARVIS server, no shared secret in the repository, and no deep-link
 * surface for another app to abuse.
 *
 * WhatsApp is listed for a reason: it is the connection people ask for first, and it is the one that
 * cannot be done. Saying so plainly, on the same screen, is more useful than hiding it.
 */
@Composable
fun ConnectorsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val storedIg = remember { ConnectorStore.instagramApp(context) }
    val storedGoogle = remember { ConnectorStore.googleClient(context) }

    var igId by remember { mutableStateOf(storedIg.clientId) }
    var igSecret by remember { mutableStateOf("") }
    var igRedirect by remember { mutableStateOf(ConnectorStore.instagramRedirect(context)) }
    var igCode by remember { mutableStateOf("") }

    var gId by remember { mutableStateOf(storedGoogle.clientId) }
    var gSecret by remember { mutableStateOf("") }
    var gRedirect by remember { mutableStateOf(ConnectorStore.googleRedirect(context)) }
    var gCode by remember { mutableStateOf("") }

    var googleEmail by remember { mutableStateOf(ConnectorStore.googleSession(context).email) }
    var googleConnected by remember { mutableStateOf(ConnectorStore.googleSession(context).connected) }
    var drive by remember { mutableStateOf<GoogleData.Fetch<List<GoogleData.DriveFile>>?>(null) }
    var gmail by remember { mutableStateOf<GoogleData.Fetch<GoogleData.GmailSummary>?>(null) }

    var instagramConnected by remember { mutableStateOf(InstagramStore.isConnected(context)) }
    var instagramHandle by remember {
        mutableStateOf(InstagramStore.cachedSnapshot(context)?.profile?.handle.orEmpty())
    }

    var busy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var noteOk by remember { mutableStateOf(true) }

    fun openBrowser(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            note = "No browser available to open the login page."
            noteOk = false
        }
    }

    fun saveIgCredentials() {
        val secret = igSecret.ifBlank { storedIg.secret }
        val problem = ConnectorStore.saveInstagramApp(context, igId, secret)
        ConnectorStore.saveInstagramRedirect(context, igRedirect)
        note = problem ?: "Instagram app credentials saved."
        noteOk = problem == null
        if (problem == null) igSecret = ""
    }

    fun saveGoogleCredentials() {
        val secret = gSecret.ifBlank { storedGoogle.secret }
        val problem = ConnectorStore.saveGoogleClient(context, gId, secret)
        ConnectorStore.saveGoogleRedirect(context, gRedirect)
        note = problem ?: "Google client credentials saved."
        noteOk = problem == null
        if (problem == null) gSecret = ""
    }

    ModuleScaffold(
        title = "CONNECTIONS",
        subtitle = "accounts and services",
        accent = IceBlue,
        statusLabel = when {
            busy.isNotBlank() -> "WORKING"
            else -> "${listOf(instagramConnected, googleConnected).count { it }} LINKED"
        },
        onBack = onBack,
    ) {
        HudPanel(label = "HOW THIS WORKS", accent = IceBlue) {
            Text(
                text = "Each service asks you to log in on its own page, in the browser. JARVIS never sees your password — it receives a one-time code, exchanges it on this device, and stores only the resulting token, encrypted." ,
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The app id and secret are yours: register a free app with each provider, paste the values here, and nothing secret ever lives in the JARVIS repository.",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = StatusGreen,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "No Facebook account needed for Instagram here — you log in with Instagram. Meta asks for a Facebook login only in its developer dashboard, where the app keys are created.",
                    style = HudType.terminal.copy(fontSize = 9.sp),
                    color = TextSecondary,
                )
            }
        }

        val storedIgSecret = storedIg.secret
        val storedGoogleSecret = storedGoogle.secret
        val igWarnings = remember(igId, igSecret, igRedirect, storedIgSecret) {
            OAuthFlows.instagramSetupWarnings(
                clientIdRaw = igId,
                secretRaw = igSecret.ifBlank { storedIgSecret },
                redirect = igRedirect,
            )
        }
        if (igWarnings.isNotEmpty()) {
            HudPanel(label = "INSTAGRAM PREFLIGHT", accent = Amber) {
                igWarnings.forEach { line ->
                    Text(
                        text = "· $line",
                        style = HudType.terminal.copy(fontSize = 9.sp),
                        color = TextSecondary,
                    )
                }
            }
        }

        // ------------------------------------------------------------------ Instagram
        HudPanel(
            label = "INSTAGRAM · LOGIN",
            accent = if (instagramConnected) StatusGreen else ElectricCyan,
            trailing = {
                HudChip(
                    text = if (instagramConnected) "LINKED" else "NOT LINKED",
                    accent = if (instagramConnected) StatusGreen else Amber,
                )
            },
        ) {
            if (instagramConnected) {
                ReadoutRow(
                    label = "ACCOUNT",
                    value = instagramHandle.ifBlank { "LINKED" }.uppercase(),
                    valueColor = StatusGreen,
                )
                ReadoutRow(label = "TOKEN", value = InstagramStore.maskedToken(context))
                ReadoutRow(label = "MODE", value = "OAUTH · 60 DAYS")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Open HUD → INSTAGRAM for the analytics. Re-running this flow replaces the token without touching your cached history.",
                    style = HudType.terminal.copy(fontSize = 9.sp),
                    color = TextMuted,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "STEP 1 · YOUR META APP",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "APP ID",
                value = igId,
                hint = "15 digits",
                masked = false,
                onChange = { igId = it },
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "APP SECRET",
                value = igSecret,
                hint = if (storedIg.isSet) "saved · leave blank to keep" else "app secret",
                masked = true,
                onChange = { igSecret = it },
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "REDIRECT URI",
                value = igRedirect,
                hint = ConnectorStore.DEFAULT_IG_REDIRECT,
                masked = false,
                onChange = { igRedirect = it },
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "SAVE CREDENTIALS",
                icon = Icons.Outlined.Lock,
                accent = IceBlue,
                enabled = igId.isNotBlank(),
                onClick = { saveIgCredentials() },
            )
            Spacer(Modifier.height(7.dp))
            ActionRow(
                label = "OPEN INSTAGRAM LOGIN",
                icon = Icons.Outlined.OpenInNew,
                accent = ElectricCyan,
                enabled = storedIg.isSet || (igId.isNotBlank() && igSecret.isNotBlank()),
                onClick = {
                    val id = igId.ifBlank { storedIg.clientId }
                    openBrowser(OAuthFlows.instagramAuthUrl(id, igRedirect))
                },
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "STEP 2 · PASTE THE CODE",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "CODE OR REDIRECT URL",
                value = igCode,
                hint = "the address you landed on after approving",
                masked = false,
                onChange = { igCode = it },
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = if (busy == "instagram") "AUTHORISING…" else "FINISH CONNECTION",
                icon = Icons.Outlined.Autorenew,
                accent = ElectricCyan,
                enabled = busy.isBlank() && igCode.isNotBlank(),
                onClick = {
                    busy = "instagram"
                    scope.launch {
                        when (val result = OAuthFlows.instagramConnect(context, igCode)) {
                            is OAuthFlows.Outcome.Ok -> {
                                instagramConnected = InstagramStore.isConnected(context)
                                instagramHandle =
                                    InstagramStore.cachedSnapshot(context)?.profile?.handle.orEmpty()
                                igCode = ""
                                note = "${result.label}: ${result.detail}"
                                noteOk = true
                            }

                            is OAuthFlows.Outcome.Error -> {
                                note = result.message + (result.hint?.let { " · $it" } ?: "")
                                noteOk = false
                            }
                        }
                        busy = ""
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scopes requested: $OAuthFlows.IG_SCOPES. Meta only accepts https redirect URIs, which is why the code is pasted rather than caught by a link.",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
            )
        }

        // ------------------------------------------------------------------ Google
        HudPanel(
            label = "GOOGLE · DRIVE + GMAIL",
            accent = if (googleConnected) StatusGreen else IceBlue,
            trailing = {
                HudChip(
                    text = if (googleConnected) "LINKED" else "NOT LINKED",
                    accent = if (googleConnected) StatusGreen else Amber,
                )
            },
        ) {
            if (googleConnected) {
                ReadoutRow(
                    label = "ACCOUNT",
                    value = googleEmail.ifBlank { "LINKED" }.uppercase(),
                    valueColor = StatusGreen,
                )
                ReadoutRow(label = "SCOPES", value = "DRIVE + GMAIL · READ ONLY")
                HudDivider(modifier = Modifier.padding(vertical = 8.dp))
                ActionRow(
                    label = "READ DRIVE",
                    icon = Icons.Outlined.Autorenew,
                    accent = IceBlue,
                    enabled = busy.isBlank(),
                    onClick = {
                        busy = "drive"
                        scope.launch {
                            drive = GoogleData.driveRecent(context)
                            busy = ""
                        }
                    },
                )
                Spacer(Modifier.height(7.dp))
                ActionRow(
                    label = "READ GMAIL",
                    icon = Icons.Outlined.Autorenew,
                    accent = IceBlue,
                    enabled = busy.isBlank(),
                    onClick = {
                        busy = "gmail"
                        scope.launch {
                            gmail = GoogleData.gmail(context)
                            busy = ""
                        }
                    },
                )

                drive?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    when (result) {
                        is GoogleData.Fetch.Ok -> {
                            Text(
                                text = "DRIVE · RECENT",
                                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                                color = TextMuted,
                            )
                            Spacer(Modifier.height(5.dp))
                            if (result.value.isEmpty()) {
                                Text(
                                    text = "No files came back.",
                                    style = HudType.terminal.copy(fontSize = 9.sp),
                                    color = TextMuted,
                                )
                            } else {
                                result.value.forEach { file ->
                                    ReadoutRow(
                                        label = "${file.kindLabel} · ${file.modifiedLabel}",
                                        value = file.name.take(34),
                                    )
                                }
                            }
                        }

                        is GoogleData.Fetch.Error -> Text(
                            text = result.message + (result.hint?.let { " · $it" } ?: ""),
                            style = HudType.terminal.copy(fontSize = 9.sp),
                            color = AlertRed,
                        )
                    }
                }

                gmail?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    when (result) {
                        is GoogleData.Fetch.Ok -> {
                            ReadoutRow(
                                label = "UNREAD (ESTIMATE)",
                                value = result.value.unreadEstimate.toString(),
                                valueColor = TextPrimary,
                            )
                            result.value.recentSubjects.forEach { subject ->
                                Text(
                                    text = "· $subject",
                                    style = HudType.terminal.copy(fontSize = 9.sp),
                                    color = TextSecondary,
                                )
                            }
                        }

                        is GoogleData.Fetch.Error -> Text(
                            text = result.message + (result.hint?.let { " · $it" } ?: ""),
                            style = HudType.terminal.copy(fontSize = 9.sp),
                            color = AlertRed,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                ActionRow(
                    label = "DISCONNECT GOOGLE",
                    icon = Icons.Outlined.Link,
                    accent = AlertRed,
                    enabled = busy.isBlank(),
                    onClick = {
                        scope.launch {
                            OAuthFlows.googleDisconnect(context)
                            googleConnected = false
                            googleEmail = ""
                            drive = null
                            gmail = null
                            note = "Google disconnected; tokens deleted."
                            noteOk = true
                        }
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "STEP 1 · YOUR GOOGLE CLOUD CLIENT",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "CLIENT ID",
                value = gId,
                hint = "….apps.googleusercontent.com",
                masked = false,
                onChange = { gId = it },
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "CLIENT SECRET",
                value = gSecret,
                hint = if (storedGoogle.isSet) "saved · leave blank to keep" else "client secret",
                masked = true,
                onChange = { gSecret = it },
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "REDIRECT URI",
                value = gRedirect,
                hint = ConnectorStore.DEFAULT_GOOGLE_REDIRECT,
                masked = false,
                onChange = { gRedirect = it },
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = "SAVE CREDENTIALS",
                icon = Icons.Outlined.Lock,
                accent = IceBlue,
                enabled = gId.isNotBlank(),
                onClick = { saveGoogleCredentials() },
            )
            Spacer(Modifier.height(7.dp))
            ActionRow(
                label = "OPEN GOOGLE CONSENT",
                icon = Icons.Outlined.OpenInNew,
                accent = IceBlue,
                enabled = storedGoogle.isSet || (gId.isNotBlank() && gSecret.isNotBlank()),
                onClick = {
                    val id = gId.ifBlank { storedGoogle.clientId }
                    openBrowser(OAuthFlows.googleAuthUrl(id, gRedirect))
                },
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "STEP 2 · PASTE THE CODE",
                style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                color = TextMuted,
            )
            Spacer(Modifier.height(6.dp))
            Field(
                label = "CODE OR REDIRECT URL",
                value = gCode,
                hint = "the address you landed on after approving",
                masked = false,
                onChange = { gCode = it },
            )
            Spacer(Modifier.height(8.dp))
            ActionRow(
                label = if (busy == "google") "AUTHORISING…" else "FINISH CONNECTION",
                icon = Icons.Outlined.Autorenew,
                accent = IceBlue,
                enabled = busy.isBlank() && gCode.isNotBlank(),
                onClick = {
                    busy = "google"
                    scope.launch {
                        when (val result = OAuthFlows.googleConnect(context, gCode)) {
                            is OAuthFlows.Outcome.Ok -> {
                                googleConnected = ConnectorStore.googleSession(context).connected
                                googleEmail = ConnectorStore.googleSession(context).email
                                gCode = ""
                                note = "${result.label}: ${result.detail}"
                                noteOk = true
                            }

                            is OAuthFlows.Outcome.Error -> {
                                note = result.message + (result.hint?.let { " · $it" } ?: "")
                                noteOk = false
                            }
                        }
                        busy = ""
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Google also needs no verification for personal use: keep the OAuth consent " +
                    "screen in TESTING and add your own address under Test users. A Desktop-app " +
                    "client with http://localhost as the redirect works as-is. Read-only scopes " +
                    "only: drive.metadata.readonly and gmail.readonly.",
                style = HudType.terminal.copy(fontSize = 9.sp),
                color = TextMuted,
            )
        }

        val gWarnings = remember(gId, gSecret, gRedirect, storedGoogleSecret) {
            OAuthFlows.googleSetupWarnings(
                clientIdRaw = gId,
                secretRaw = gSecret.ifBlank { storedGoogleSecret },
                redirect = gRedirect,
            )
        }
        if (gWarnings.isNotEmpty()) {
            HudPanel(label = "GOOGLE PREFLIGHT", accent = Amber) {
                gWarnings.forEach { line ->
                    Text(
                        text = "· $line",
                        style = HudType.terminal.copy(fontSize = 9.sp),
                        color = TextSecondary,
                    )
                }
            }
        }

        // ------------------------------------------------------------------ WhatsApp
        HudPanel(label = "WHATSAPP", accent = Amber) {
            ReadoutRow(label = "PERSONAL CHATS", value = "NOT AVAILABLE", valueColor = TextMuted)
            ReadoutRow(label = "LINKED DEVICES", value = "PROPRIETARY", valueColor = TextMuted)
            ReadoutRow(label = "BUSINESS CLOUD API", value = "SEPARATE NUMBER", valueColor = TextMuted)
            ReadoutRow(label = "OPEN A CHAT", value = "SUPPORTED", valueColor = StatusGreen)
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "There is no API that reads a personal WhatsApp account. What exists is the " +
                    "WhatsApp Business Cloud API, which belongs to a business phone number you " +
                    "register — not your own chats and not your contacts' messages. Pairing your " +
                    "phone as a \"linked device\" is a private protocol; the libraries that do it " +
                    "break on WhatsApp's schedule and would put this app's stability at risk, so " +
                    "JARVIS does not do it.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            ActionRow(
                label = "OPEN WHATSAPP",
                icon = Icons.Outlined.OpenInNew,
                accent = StatusGreen,
                enabled = true,
                onClick = {
                    val intent = try {
                        context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    } catch (t: Throwable) {
                        null
                    }
                    if (intent != null) {
                        openBrowser("https://wa.me/")
                    } else {
                        note = "WhatsApp is not installed."
                        noteOk = false
                    }
                },
            )
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

        HudPanel(label = "WHAT JARVIS CANNOT LINK", accent = TextMuted) {
            ReadoutRow(label = "ANY ACCOUNT WITHOUT OAUTH", value = "NOT SUPPORTED", valueColor = TextMuted)
            ReadoutRow(label = "SCRAPED SESSIONS", value = "NEVER", valueColor = TextMuted)
            ReadoutRow(label = "OTHER PEOPLE'S DATA", value = "NEVER", valueColor = TextMuted)
            HudDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "A connection either runs through a provider's official OAuth endpoints or it " +
                    "does not exist here. That rule is what keeps the app stable and your accounts " +
                    "unbanned.",
                style = HudType.terminal.copy(fontSize = 10.sp),
                color = TextMuted,
            )
        }
    }
}

// ------------------------------------------------------------------------------------- pieces

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    masked: Boolean,
    onChange: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.2.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF050B12), ChipShape)
                .border(1.dp, ElectricCyan.copy(alpha = 0.24f), ChipShape)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = HudType.terminal.copy(fontSize = 11.sp, color = TextPrimary),
                cursorBrush = SolidColor(ElectricCyan),
                visualTransformation = if (masked) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (masked) KeyboardType.Password else KeyboardType.Text,
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = HudType.terminal.copy(fontSize = 10.sp),
                            color = TextMuted.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) accent else TextMuted
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (enabled) accent.copy(alpha = 0.12f) else Color.Transparent, ChipShape)
            .border(1.dp, tint.copy(alpha = if (enabled) 0.55f else 0.25f), ChipShape)
            .clickable(enabled = enabled, onClick = onClick)
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
            style = HudType.label.copy(fontSize = 9.sp, letterSpacing = 1.4.sp),
            color = tint,
        )
    }
}
