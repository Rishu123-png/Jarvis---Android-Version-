package com.jarvis.assistant.device

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The privacy PIN.
 *
 * JARVIS can open apps, send messages and read what the operator chooses to share, so the app itself
 * is worth locking. The PIN is never stored — only a PBKDF2-HMAC-SHA256 digest with a random salt and
 * a deliberately slow iteration count, in app-private preferences. Verification is constant-time and
 * everything is bounded, so a wrong PIN costs a moment rather than a hang.
 *
 * There is no recovery flow and no "forgot PIN" email: the only reset is clearing app data, which is
 * stated in the screen that sets it.
 */
object Privacy {

    private const val PREFS = "jarvis_privacy"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HASH = "pin_hash"
    private const val KEY_ENABLED = "pin_enabled"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 10
    private const val MIN_LENGTH = MIN_DIGITS
    private const val MAX_LENGTH = MAX_DIGITS

    /** Grace period after leaving the app before the lock engages. */
    const val LOCK_AFTER_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false) &&
            prefs(context).getString(KEY_HASH, null) != null

    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    /** Returns null on success, or a reason the PIN was rejected. */
    fun setPin(context: Context, pin: String): String? {
        if (!isValidPin(pin)) return "Use $MIN_DIGITS to $MAX_DIGITS digits."
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt) ?: return "This device cannot hash a PIN."
        prefs(context).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(KEY_ENABLED, true)
            .apply()
        return null
    }

    fun verify(context: Context, pin: String): Boolean {
        val store = prefs(context)
        val saltText = store.getString(KEY_SALT, null) ?: return false
        val hashText = store.getString(KEY_HASH, null) ?: return false
        if (!isValidPin(pin)) return false

        val salt = try {
            Base64.decode(saltText, Base64.NO_WRAP)
        } catch (t: Throwable) {
            return false
        }
        val expected = try {
            Base64.decode(hashText, Base64.NO_WRAP)
        } catch (t: Throwable) {
            return false
        }
        val actual = derive(pin, salt) ?: return false

        // Constant-time compare: a byte-by-byte early return leaks the prefix.
        var difference = 0
        if (actual.size != expected.size) return false
        for (index in actual.indices) difference = difference or (actual[index].toInt() xor expected[index].toInt())
        return difference == 0
    }

    fun disable(context: Context) {
        prefs(context).edit()
            .remove(KEY_SALT)
            .remove(KEY_HASH)
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray? = try {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        key
    } catch (t: Throwable) {
        try {
            val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            spec.clearPassword()
            key
        } catch (inner: Throwable) {
            null
        }
    }
}
