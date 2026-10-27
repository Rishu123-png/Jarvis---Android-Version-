package com.jarvis.assistant.social

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * On-device storage for the Instagram connection.
 *
 * The access token is the one genuinely sensitive thing this module holds: it can read the
 * operator's own account data until it is revoked. So it gets the same treatment as the AI key —
 * AES-GCM ciphertext in preferences, the key itself in the Android Keystore and never on disk. No
 * token is ever logged or sent anywhere except Meta.
 *
 * The cached snapshot and the growth series are plain JSON: they are the operator's own public
 * numbers, and keeping them lets the screen open instantly and lets growth be measured across days
 * instead of guessed from one reading.
 */
object InstagramStore {

    private const val PREFS = "jarvis_instagram"
    private const val ALIAS = "jarvis-ig-token-v1"
    private const val P_TOKEN = "token_payload"
    private const val P_PLATFORM = "platform"
    private const val P_CACHE = "snapshot"
    private const val P_GROWTH = "growth"

    private const val TAG_BITS = 128
    private const val MIN_TOKEN_LENGTH = 20
    private const val MAX_GROWTH_POINTS = 180
    private const val GROWTH_MIN_GAP_MS = 6 * 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------------------ connection

    fun token(context: Context): String? =
        prefs(context).getString(P_TOKEN, null)?.let { decrypt(it) }?.takeIf { it.isNotBlank() }

    fun platform(context: Context): IgPlatform =
        IgPlatform.fromId(prefs(context).getString(P_PLATFORM, null))

    fun isConnected(context: Context): Boolean = token(context) != null

    fun maskedToken(context: Context): String {
        val value = token(context) ?: return "—"
        return if (value.length <= 10) "••••" else "••••" + value.takeLast(6)
    }

    /**
     * Stores the token. Returns null on success, or a sentence for the operator on failure — a
     * device whose keystore refuses to encrypt gets an explanation, never a plaintext fallback.
     */
    fun save(context: Context, token: String, platform: IgPlatform): String? {
        val trimmed = token.trim()
        if (trimmed.length < MIN_TOKEN_LENGTH) return "That does not look like an access token."
        if (trimmed.any { it.isWhitespace() }) return "The token contains a space — check the paste."

        val payload = encrypt(trimmed) ?: return "This device could not encrypt the token, so nothing was saved."
        prefs(context).edit()
            .putString(P_TOKEN, payload)
            .putString(P_PLATFORM, platform.id)
            .apply()
        return null
    }

    fun clear(context: Context) {
        // Keeps the growth history only if the same account reconnects; simplest correct choice is
        // to drop everything, because a different account would corrupt the series.
        prefs(context).edit()
            .remove(P_TOKEN)
            .remove(P_CACHE)
            .remove(P_GROWTH)
            .apply()
    }

    // -------------------------------------------------------------------------------- snapshot

    fun cachedSnapshot(context: Context): IgSnapshot? =
        IgSnapshot.fromJson(prefs(context).getString(P_CACHE, null))

    fun saveSnapshot(context: Context, snapshot: IgSnapshot) {
        prefs(context).edit().putString(P_CACHE, snapshot.toJson().toString()).apply()
        recordGrowth(context, snapshot)
    }

    fun cacheAgeMs(context: Context): Long {
        val at = cachedSnapshot(context)?.fetchedAt ?: return Long.MAX_VALUE
        return (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------------------------- growth

    fun growth(context: Context): List<GrowthPoint> {
        val raw = prefs(context).getString(P_GROWTH, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        GrowthPoint(
                            at = obj.optLong("at"),
                            followers = obj.optInt("followers"),
                            mediaCount = obj.optInt("media"),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Appends a reading when it says something new: the follower count moved, or the last point is
     * old enough that the gap itself is information. Otherwise it overwrites the newest point so a
     * burst of refreshes cannot flatten the series into noise.
     */
    private fun recordGrowth(context: Context, snapshot: IgSnapshot) {
        val points = growth(context).toMutableList()
        val reading = GrowthPoint(
            at = snapshot.fetchedAt,
            followers = snapshot.profile.followers,
            mediaCount = snapshot.profile.mediaCount,
        )
        val last = points.lastOrNull()
        when {
            last == null -> points.add(reading)
            reading.followers != last.followers -> points.add(reading)
            reading.at - last.at >= GROWTH_MIN_GAP_MS -> points.add(reading)
            else -> points[points.lastIndex] = reading
        }
        val trimmed = points.takeLast(MAX_GROWTH_POINTS)
        val array = JSONArray()
        trimmed.forEach { point ->
            array.put(
                JSONObject().apply {
                    put("at", point.at)
                    put("followers", point.followers)
                    put("media", point.mediaCount)
                },
            )
        }
        prefs(context).edit().putString(P_GROWTH, array.toString()).apply()
    }

    // ------------------------------------------------------------------------------- keystore

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
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }
}
