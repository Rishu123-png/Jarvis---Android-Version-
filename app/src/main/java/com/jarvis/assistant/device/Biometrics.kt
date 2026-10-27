package com.jarvis.assistant.device

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * Fingerprint and face unlock for the privacy lock.
 *
 * This is a **shortcut**, not a second password. JARVIS never sees, stores or transmits biometric
 * data — Android matches the finger or the face in its own secure hardware and only tells us "yes" or
 * "no". Nothing here can be extracted, and there is nothing to be stolen if the phone is rooted, which
 * is exactly why it is safe to offer.
 *
 * The PIN stays the real gate: biometrics can be unavailable (wet finger, no enrolment, hardware
 * locked out after too many attempts), and the keypad is always one tap away. Biometrics can never
 * *replace* the PIN, only save typing it.
 *
 * Uses the platform API directly — the app already requires Android 10 (API 29) and
 * `BiometricManager` arrived in API 29, so no extra dependency is needed and there is no
 * AppCompat/AndroidX fragment requirement.
 */
object Biometrics {

    private const val PREFS = "jarvis_biometrics"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Biometrics switched on by the operator. Defaults to on — it can only ever help. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** True when the phone has a working fingerprint/face sensor with at least one enrolled. */
    fun isAvailable(context: Context): Boolean = try {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (t: Throwable) {
        false
    }

    /** Why biometrics cannot be offered, for the settings row. */
    fun availability(context: Context): String = try {
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return "NO SENSOR"
        when (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> "READY"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NO FINGERPRINT SAVED"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO SENSOR"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "SENSOR BUSY"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "UNKNOWN"
            else -> "UNAVAILABLE"
        }
    } catch (t: Throwable) {
        "UNAVAILABLE"
    }

    /** Whether the lock screen should attempt biometrics at all. */
    fun canOffer(context: Context): Boolean = isEnabled(context) && isAvailable(context)

    /**
     * Shows the system prompt. [onResult] receives true only on a real match; a cancel, a lockout or
     * an error is false, and the caller keeps the keypad visible.
     *
     * Returns the signal used to cancel, so leaving the screen does not leave a prompt behind.
     */
    fun prompt(
        activity: Activity,
        onResult: (Boolean) -> Unit,
    ): CancellationSignal? {
        val executor = activity.mainExecutor
        val signal = CancellationSignal()

        val prompt = try {
            BiometricPrompt.Builder(activity)
                .setTitle("JARVIS")
                .setSubtitle("Unlock with your fingerprint or face")
                .setNegativeButtonText("Use PIN", executor) { _: DialogInterface, _: Int ->
                    onResult(false)
                }
                .build()
        } catch (t: Throwable) {
            return null
        }

        return try {
            prompt.authenticate(
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onResult(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        // Includes user cancel, lockout after too many attempts, and hardware errors.
                        onResult(false)
                    }

                    // A single non-matching finger does not end the prompt — the system lets the
                    // operator try again, so this deliberately does nothing.
                    override fun onAuthenticationFailed() = Unit
                },
            )
            signal
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * The rule for when the lock screen offers biometrics — kept pure so it can be tested without a
 * sensor, an activity or a device.
 */
object BiometricPolicy {

    /**
     * @param enabled   the operator's switch
     * @param available hardware present and enrolled
     * @param failCount failed attempts in this session; after [MAX_AUTO_FAILURES] the prompt is no
     *   longer fired automatically, because a sensor that keeps rejecting chips away at the "just
     *   unlock it" moment. The button stays, so it is still one tap.
     */
    fun shouldAutoPrompt(enabled: Boolean, available: Boolean, failCount: Int): Boolean =
        enabled && available && failCount < MAX_AUTO_FAILURES

    /** Whether to show the biometric button at all. */
    fun canOffer(enabled: Boolean, available: Boolean): Boolean = enabled && available

    /** What the fallback key should say. */
    fun fallbackLabel(enabled: Boolean, available: Boolean): String =
        if (canOffer(enabled, available)) "USE PIN INSTEAD" else "UNLOCK"

    const val MAX_AUTO_FAILURES = 2
}
