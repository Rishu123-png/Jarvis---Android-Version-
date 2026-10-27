package com.jarvis.assistant.connect

import android.content.Context
import com.jarvis.assistant.social.IgPlatform
import com.jarvis.assistant.social.InstagramClient
import com.jarvis.assistant.social.InstagramStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * The two real OAuth flows, done on the device.
 *
 * Both are authorization-code exchanges against the provider's own token endpoint, with credentials
 * the operator registered themselves. There is no JARVIS backend and no shared secret: the app id and
 * secret live encrypted on the phone, and the code is exchanged directly with Meta or Google.
 *
 * The login page is the provider's, opened in the phone's browser. When the provider redirects back,
 * the operator copies the short-lived `code` out of the address bar and pastes it in — that keeps the
 * whole flow inside the official endpoints without needing an HTTPS redirect JARVIS would have to
 * host, a domain verification, or a deep-link surface that other apps could poke.
 */
object OAuthFlows {

    sealed interface Outcome {
        data class Ok(val label: String, val detail: String) : Outcome
        data class Error(val message: String, val hint: String? = null) : Outcome
    }

    // --------------------------------------------------------------------------------- Instagram

    private const val IG_AUTH = "https://www.instagram.com/oauth/authorize"
    private const val IG_TOKEN = "https://api.instagram.com/oauth/access_token"
    private const val IG_LONG = "https://graph.instagram.com/access_token"

    const val IG_SCOPES = "instagram_business_basic,instagram_business_manage_insights"

    fun instagramAuthUrl(clientId: String, redirect: String): String =
        IG_AUTH +
            "?client_id=" + Web.enc(clientId.trim()) +
            "&redirect_uri=" + Web.enc(redirect) +
            "&response_type=code" +
            "&scope=" + Web.enc(IG_SCOPES)

    /**
     * Exchanges the pasted code for a short-lived token, upgrades it to a 60-day token, and hands it
     * to the Instagram module so the analytics screen works immediately.
     */
    suspend fun instagramConnect(context: Context, pasted: String): Outcome = withContext(Dispatchers.IO) {
        val credentials = ConnectorStore.instagramApp(context)
        if (!credentials.isSet) {
            return@withContext Outcome.Error(
                "Instagram app credentials are missing.",
                "Register a free Meta app, then paste its app id and secret above.",
            )
        }
        val code = extractCode(pasted)
            ?: return@withContext Outcome.Error(
                "No authorization code found in that paste.",
                "Paste the whole redirect URL or just the code value.",
            )

        try {
            val short = Web.postForm(
                IG_TOKEN,
                mapOf(
                    "client_id" to credentials.clientId.trim(),
                    "client_secret" to credentials.secret.trim(),
                    "grant_type" to "authorization_code",
                    "redirect_uri" to ConnectorStore.instagramRedirect(context),
                    "code" to code,
                ),
            )
            val shortToken = short.optString("access_token")
            if (shortToken.isBlank()) {
                return@withContext Outcome.Error("Meta accepted the login but returned no token.")
            }

            // Upgrade to the 60-day token. If this step fails the short-lived token still works for
            // an hour, so it is stored either way and the module can extend it later.
            val long = try {
                Web.get(
                    IG_LONG + "?grant_type=ig_exchange_token" +
                        "&client_secret=" + Web.enc(credentials.secret.trim()) +
                        "&access_token=" + Web.enc(shortToken),
                )
            } catch (t: Throwable) {
                null
            }
            val token = long?.optString("access_token")?.takeIf { it.isNotBlank() } ?: shortToken
            val days = (long?.optLong("expires_in", 0L) ?: 0L) / 86_400L

            val problem = InstagramStore.save(context, token, IgPlatform.INSTAGRAM_LOGIN)
            if (problem != null) return@withContext Outcome.Error(problem)

            // Warm the cache so the first open of the module is not an empty screen.
            InstagramClient.fetch(context)

            Outcome.Ok(
                "Instagram",
                if (days > 0) "Linked · token valid $days days" else "Linked · token expires within an hour, extend it in the module",
            )
        } catch (failure: Web.WebFailure) {
            Outcome.Error(failure.message, failure.hint)
        } catch (t: Throwable) {
            Outcome.Error("Could not finish the Instagram login.", t.message)
        }
    }

    // ------------------------------------------------------------------------------------ Google

    private const val G_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val G_TOKEN = "https://oauth2.googleapis.com/token"

    const val G_SCOPES =
        "https://www.googleapis.com/auth/drive.metadata.readonly " +
            "https://www.googleapis.com/auth/gmail.readonly openid email"

    fun googleAuthUrl(clientId: String, redirect: String): String =
        G_AUTH +
            "?client_id=" + Web.enc(clientId.trim()) +
            "&redirect_uri=" + Web.enc(redirect) +
            "&response_type=code" +
            "&scope=" + Web.enc(G_SCOPES) +
            "&access_type=offline" +
            "&prompt=consent" +
            "&include_granted_scopes=true"

    suspend fun googleConnect(context: Context, pasted: String): Outcome = withContext(Dispatchers.IO) {
        val credentials = ConnectorStore.googleClient(context)
        if (!credentials.isSet) {
            return@withContext Outcome.Error(
                "Google client credentials are missing.",
                "Create a free OAuth client in Google Cloud, then paste its id and secret above.",
            )
        }
        val code = extractCode(pasted)
            ?: return@withContext Outcome.Error(
                "No authorization code found in that paste.",
                "Paste the whole redirect URL or just the code value.",
            )

        try {
            val json = Web.postForm(
                G_TOKEN,
                mapOf(
                    "code" to code,
                    "client_id" to credentials.clientId.trim(),
                    "client_secret" to credentials.secret.trim(),
                    "redirect_uri" to ConnectorStore.googleRedirect(context),
                    "grant_type" to "authorization_code",
                ),
            )
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            if (access.isBlank()) {
                return@withContext Outcome.Error("Google returned no access token.")
            }
            if (refresh.isBlank()) {
                return@withContext Outcome.Error(
                    "Google returned no refresh token.",
                    "Remove JARVIS from your Google account's third-party access, then connect again — consent must be granted fresh.",
                )
            }

            val email = try {
                Web.get("https://www.googleapis.com/oauth2/v3/userinfo", access).optString("email")
            } catch (t: Throwable) {
                ""
            }

            ConnectorStore.saveGoogleSession(context, refresh, access, email.ifBlank { "connected" })
            ConnectorStore.markGoogleToken(context, access, json.optLong("expires_in", 3_600L))

            Outcome.Ok("Google", if (email.isBlank()) "Linked" else "Linked as $email")
        } catch (failure: Web.WebFailure) {
            Outcome.Error(failure.message, failure.hint)
        } catch (t: Throwable) {
            Outcome.Error("Could not finish the Google login.", t.message)
        }
    }

    /**
     * A usable Google access token, refreshed when it is within a minute of expiry.
     *
     * Returns null when there is no session or the refresh was rejected — callers then show "connect
     * again" rather than retrying in a loop.
     */
    suspend fun googleAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val session = ConnectorStore.googleSession(context)
        val cached = ConnectorStore.googleAccessToken(context)
        if (cached != null && session.accessTokenValidUntil > System.currentTimeMillis() + 60_000L) {
            return@withContext cached
        }
        val refresh = ConnectorStore.googleRefreshToken(context) ?: return@withContext null
        val credentials = ConnectorStore.googleClient(context)
        if (!credentials.isSet) return@withContext null

        try {
            val json = Web.postForm(
                G_TOKEN,
                mapOf(
                    "client_id" to credentials.clientId.trim(),
                    "client_secret" to credentials.secret.trim(),
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                ),
            )
            val token = json.optString("access_token")
            if (token.isBlank()) {
                null
            } else {
                ConnectorStore.markGoogleToken(context, token, json.optLong("expires_in", 3_600L))
                token
            }
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun googleDisconnect(context: Context) = withContext(Dispatchers.IO) {
        ConnectorStore.clearGoogle(context)
    }

    // ----------------------------------------------------------------------------------- helpers

    /**
     * Pulls `code` out of whatever the operator pasted: a full redirect URL, a query fragment, or
     * the bare value. Percent-encoding is undone, because Meta and Google both encode characters in
     * the code and a raw `%2F` would fail the exchange.
     */
    fun extractCode(pasted: String): String? {
        val trimmed = pasted.trim()
        if (trimmed.isEmpty()) return null

        val marker = listOf("code=", "?code=", "&code=").firstOrNull { trimmed.contains(it) }
        val candidate = if (marker != null) {
            trimmed.substringAfter(marker).substringBefore('&').substringBefore('#').trim()
        } else {
            trimmed
        }
        if (candidate.isBlank()) return null
        return try {
            URLDecoder.decode(candidate, "UTF-8")
        } catch (t: Throwable) {
            candidate
        }
    }
}
