package com.jarvis.assistant.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Account linking, the way a normal app does it: **you log in on the provider's own page**.
 *
 * Why JARVIS asks for app credentials instead of shipping them: an OAuth client id/secret belongs to
 * whoever registered the application, and a secret in a public repository is a leaked secret. So the
 * operator registers a free app with Meta or Google once and pastes *their own* id and secret here —
 * the same "bring your own key" rule the AI layer already follows. Nothing secret is in this repo.
 *
 * Why the code is pasted instead of caught by a deep link: Meta only accepts `https` redirect URIs
 * and Google requires a pre-registered one, so a self-hosted app cannot receive the redirect
 * directly. The operator approves on the real login page, then pastes back the short-lived `code`
 * from the redirect URL. That is a real OAuth code exchange — single use, minutes of validity — done
 * on the device with no server in the middle, and it adds no intent-filter surface to the app.
 */
enum class ConnectorId(val title: String, val caption: String) {
    INSTAGRAM("INSTAGRAM", "Creator account, live views and reach"),
    GOOGLE("GOOGLE", "Drive files and Gmail, read-only"),
    WHATSAPP("WHATSAPP", "Chats are not possible — see why"),
    ;
}

/** OAuth client credentials the operator registered with the provider. */
data class AppCredentials(
    val clientId: String = "",
    val secret: String = "",
) {
    val isSet: Boolean get() = clientId.isNotBlank() && secret.isNotBlank()
}

/** Google session: a refresh token plus the identity it belongs to. */
data class GoogleSession(
    val email: String = "",
    val connected: Boolean = false,
    val accessTokenValidUntil: Long = 0L,
)

/**
 * All connector state, with every secret encrypted by the Android Keystore.
 *
 * One preference file, one alias, four secrets (two client secrets, one refresh token, one access
 * token). If the keystore refuses to encrypt, nothing is stored and the caller is told — there is no
 * plaintext fallback anywhere in this file.
 */
object ConnectorStore {

    private const val PREFS = "jarvis_connectors"
    private const val ALIAS = "jarvis-connectors-v1"

    private const val P_IG_ID = "ig_client_id"
    private const val P_IG_SECRET = "ig_client_secret"
    private const val P_IG_REDIRECT = "ig_redirect"
    private const val P_G_ID = "g_client_id"
    private const val P_G_SECRET = "g_client_secret"
    private const val P_G_REDIRECT = "g_redirect"
    private const val P_G_REFRESH = "g_refresh"
    private const val P_G_ACCESS = "g_access"
    private const val P_G_EXPIRES = "g_expires"
    private const val P_G_EMAIL = "g_email"

    const val DEFAULT_IG_REDIRECT = "https://localhost/instagram"
    const val DEFAULT_GOOGLE_REDIRECT = "http://localhost"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun putSecret(context: Context, key: String, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            prefs(context).edit().remove(key).apply()
            return null
        }
        val payload = encrypt(trimmed) ?: return "This device could not encrypt that, so nothing was saved."
        prefs(context).edit().putString(key, payload).apply()
        return null
    }

    private fun readSecret(context: Context, key: String): String? =
        prefs(context).getString(key, null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }

    // --------------------------------------------------------------------------------- Instagram

    fun instagramApp(context: Context): AppCredentials = AppCredentials(
        clientId = readSecret(context, P_IG_ID).orEmpty(),
        secret = readSecret(context, P_IG_SECRET).orEmpty(),
    )

    fun saveInstagramApp(context: Context, clientId: String, secret: String): String? {
        putSecret(context, P_IG_ID, clientId)?.let { return it }
        return putSecret(context, P_IG_SECRET, secret)
    }

    fun instagramRedirect(context: Context): String =
        prefs(context).getString(P_IG_REDIRECT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_IG_REDIRECT

    fun saveInstagramRedirect(context: Context, value: String) {
        prefs(context).edit().putString(P_IG_REDIRECT, value.trim().ifBlank { DEFAULT_IG_REDIRECT }).apply()
    }

    // ------------------------------------------------------------------------------------ Google

    fun googleClient(context: Context): AppCredentials = AppCredentials(
        clientId = readSecret(context, P_G_ID).orEmpty(),
        secret = readSecret(context, P_G_SECRET).orEmpty(),
    )

    fun saveGoogleClient(context: Context, clientId: String, secret: String): String? {
        putSecret(context, P_G_ID, clientId)?.let { return it }
        return putSecret(context, P_G_SECRET, secret)
    }

    fun googleRedirect(context: Context): String =
        prefs(context).getString(P_G_REDIRECT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GOOGLE_REDIRECT

    fun saveGoogleRedirect(context: Context, value: String) {
        prefs(context).edit().putString(P_G_REDIRECT, value.trim().ifBlank { DEFAULT_GOOGLE_REDIRECT }).apply()
    }

    fun googleSession(context: Context): GoogleSession = GoogleSession(
        email = readSecret(context, P_G_EMAIL).orEmpty(),
        connected = readSecret(context, P_G_REFRESH) != null,
        accessTokenValidUntil = prefs(context).getLong(P_G_EXPIRES, 0L),
    )

    fun googleRefreshToken(context: Context): String? = readSecret(context, P_G_REFRESH)

    fun googleAccessToken(context: Context): String? = readSecret(context, P_G_ACCESS)

    fun saveGoogleSession(context: Context, refreshToken: String?, accessToken: String?, email: String?) {
        if (refreshToken != null) putSecret(context, P_G_REFRESH, refreshToken)
        if (accessToken != null) putSecret(context, P_G_ACCESS, accessToken)
        if (email != null) putSecret(context, P_G_EMAIL, email)
    }

    fun markGoogleToken(context: Context, accessToken: String, validForSeconds: Long) {
        putSecret(context, P_G_ACCESS, accessToken)
        prefs(context).edit()
            .putLong(P_G_EXPIRES, System.currentTimeMillis() + validForSeconds * 1000L)
            .apply()
    }

    fun clearGoogle(context: Context) {
        prefs(context).edit()
            .remove(P_G_REFRESH)
            .remove(P_G_ACCESS)
            .remove(P_G_EXPIRES)
            .remove(P_G_EMAIL)
            .apply()
    }

    // ---------------------------------------------------------------------------------- keystore

    private fun secretKey(): SecretKey? = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: createKey()
    } catch (t: Throwable) {
        null
    }

    private fun createKey(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    } catch (t: Throwable) {
        null
    }

    private fun encrypt(value: String): String? = try {
        val key = secretKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }

    private fun decrypt(payload: String): String? = try {
        val parts = payload.split(":")
        if (parts.size != 2) {
            null
        } else {
            val key = secretKey() ?: return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }
}
