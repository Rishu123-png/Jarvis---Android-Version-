package com.jarvis.assistant.ai

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
 * Encrypted-at-rest storage for the operator's API key.
 *
 * The key lives in the Android Keystore (hardware-backed where the device supports it) and only
 * an AES-GCM ciphertext is written to preferences. Nothing is logged, nothing is uploaded, and the
 * plaintext exists only for the duration of a request.
 *
 * Failure is handled conservatively: if the keystore is unusable (rare vendor bugs, or a restored
 * backup whose key did not travel), [save] reports an error and stores nothing rather than
 * silently falling back to plaintext.
 */
object AiKeyStore {

    private const val PREFS = "jarvis_ai"
    private const val ALIAS = "jarvis-ai-secret-v1"
    private const val P_PAYLOAD = "key_payload"
    private const val P_PROVIDER = "provider_id"
    private const val P_MODEL = "model"
    private const val P_BASE = "base_url"

    private const val TAG_BITS = 128
    private const val MIN_KEY_LENGTH = 8

    data class Config(
        val provider: AiProvider,
        val model: String,
        val baseUrl: String,
        val maskedKey: String,
    ) {
        val label: String get() = provider.label
        val resolvedModel: String
            get() = model.ifBlank { provider.defaultModel }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Non-secret view of the configuration, for display. Returns null when no key is stored. */
    fun config(context: Context): Config? {
        val store = prefs(context)
        val payload = store.getString(P_PAYLOAD, null) ?: return null
        val key = decrypt(payload)?.takeIf { it.isNotBlank() } ?: return null
        return Config(
            provider = AiProvider.fromId(store.getString(P_PROVIDER, null)),
            model = store.getString(P_MODEL, "") ?: "",
            baseUrl = store.getString(P_BASE, "") ?: "",
            maskedKey = mask(key),
        )
    }

    /** The plaintext key. Only [AiClient] should call this. */
    fun apiKey(context: Context): String? =
        prefs(context).getString(P_PAYLOAD, null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }

    /**
     * Persists the key. Returns null on success, or a human-readable reason on failure — the
     * caller shows it verbatim, so the message must be safe to display.
     */
    fun save(
        context: Context,
        provider: AiProvider,
        model: String,
        baseUrl: String,
        apiKey: String,
    ): String? {
        val trimmed = apiKey.trim()
        if (trimmed.length < MIN_KEY_LENGTH) return "That key looks too short to be valid."
        if (trimmed.any { it.isWhitespace() }) return "The key contains spaces — check the paste."
        if (provider.needsBaseUrl && baseUrl.isBlank()) return "A base URL is required for a custom endpoint."

        val payload = encrypt(trimmed)
            ?: return "This device could not encrypt the key, so nothing was saved."

        prefs(context).edit()
            .putString(P_PAYLOAD, payload)
            .putString(P_PROVIDER, provider.id)
            .putString(P_MODEL, model.trim())
            .putString(P_BASE, baseUrl.trim())
            .apply()
        return null
    }

    /**
     * Changes provider/model/base URL without touching the stored key.
     *
     * Needed because switching model is the common fix for a 404, and forcing the operator to paste
     * the key again just to change one string would be hostile.
     */
    fun updateConfig(context: Context, provider: AiProvider, model: String, baseUrl: String) {
        val store = prefs(context)
        if (store.getString(P_PAYLOAD, null) == null) return
        store.edit()
            .putString(P_PROVIDER, provider.id)
            .putString(P_MODEL, model.trim())
            .putString(P_BASE, baseUrl.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Keeps the last four characters so the operator can tell which key is loaded. */
    fun mask(key: String): String =
        if (key.length <= 8) "••••" else "••••" + key.takeLast(4)

    // -----------------------------------------------------------------------------------------
    // Keystore plumbing
    // -----------------------------------------------------------------------------------------

    private fun secretKey(): SecretKey? = try {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        existing ?: createKey()
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
                .build()
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
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
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
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }
}
