package com.jarvis.assistant.device

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fingerprint and face unlock for the privacy lock.
 *
 * This is a **shortcut**, not a second password. JARVIS never sees, stores or transmits biometric
 * data — Android matches the finger or the face in its own secure hardware and returns only pass or
 * fail. There is nothing here to extract, which is exactly why it is safe to offer.
 *
 * The PIN stays the real gate: biometrics can be unavailable (wet finger, no enrolment, sensor locked
 * out after too many attempts), and the keypad is always one tap away. Biometrics can never *replace*
 * the PIN, only save typing it.
 *
 * Uses the platform API directly. The app already requires Android 10 (API 29) and
 * `android.hardware.biometrics.BiometricManager` arrived in API 29, so this costs no dependency and
 * no fragment machinery.
 */
object Biometrics {

    /** What the system prompt came back with. */
    enum class Outcome {
        /** A real match in secure hardware. */
        SUCCESS,

        /** The operator chose the PIN, or backed out. Not a sensor failure. */
        CANCELLED,

        /** Lockout after too many attempts, hardware error, or no match within the system's limit. */
        FAILED,
    }

    // The system reports these codes through AuthenticationCallback, but the platform class does not
    // publish them as constants the way the AndroidX facade does — they are only documented in the
    // framework sources. The values are part of the native fingerprint/hardware API and stable.
    private const val ERROR_CANCELED = 5
    private const val ERROR_USER_CANCELED = 10
    private const val ERROR_NEGATIVE_BUTTON = 13

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
    fun isAvailable(context: Context): Boolean = codeFor(context) == BiometricManager.BIOMETRIC_SUCCESS

    /** Why biometrics cannot be offered, for the settings row. */
    fun availability(context: Context): String = when (codeFor(context)) {
        BiometricManager.BIOMETRIC_SUCCESS -> "READY"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "NO FINGERPRINT SAVED"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "NO SENSOR"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "SENSOR BUSY"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "SECURITY UPDATE NEEDED"
        else -> "UNAVAILABLE"
    }

    private fun codeFor(context: Context): Int = try {
        val manager = context.getSystemService(BiometricManager::class.java)
        manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    } catch (t: Throwable) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

    /**
     * Shows the system prompt from [activity].
     *
     * [onResult] receives [Outcome.SUCCESS] only on a real match. [Outcome.CANCELLED] means the
     * operator asked for the keypad instead — that must not count against the sensor, or pressing
     * "Use PIN" twice would quietly disable the automatic attempt. Everything else is
     * [Outcome.FAILED]: a lockout, a missing sensor, or the system giving up.
     *
     * A single non-matching finger does not end the prompt — the system lets the operator try again.
     * [onResult] is called exactly once per prompt.
     *
     * Returns the signal needed to [cancel] a prompt that outlived its screen; null if the prompt
     * could not be shown at all.
     */
    fun prompt(
        activity: Activity,
        onResult: (Outcome) -> Unit,
    ): CancellationSignal? {
        val executor = activity.mainExecutor
        val signal = CancellationSignal()
        val answered = AtomicBoolean(false)

        fun answer(outcome: Outcome) {
            if (answered.compareAndSet(false, true)) onResult(outcome)
        }

        val prompt = try {
            BiometricPrompt.Builder(activity)
                .setTitle("JARVIS")
                .setSubtitle("Unlock with your fingerprint or face")
                .setConfirmationRequired(false)
                .setNegativeButton("Use PIN", executor) { _: DialogInterface, _: Int ->
                    answer(Outcome.CANCELLED)
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
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult?,
                    ) {
                        answer(Outcome.SUCCESS)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        answer(
                            when (errorCode) {
                                ERROR_USER_CANCELED,
                                ERROR_NEGATIVE_BUTTON,
                                ERROR_CANCELED,
                                -> Outcome.CANCELLED
                                // Lockout after too many attempts, hardware gone, timeout: all
                                // leave the keypad as the only way in.
                                else -> Outcome.FAILED
                            },
                        )
                    }

                    // One wrong finger does not end the prompt; the system offers another try.
                    override fun onAuthenticationFailed() = Unit
                },
            )
            signal
        } catch (t: Throwable) {
            null
        }
    }

    /** Dismisses a prompt that is still on screen without counting it as a failure. */
    fun cancel(signal: CancellationSignal?) {
        try {
            signal?.cancel()
        } catch (t: Throwable) {
            Unit
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
     *   longer fired automatically, because a sensor that keeps rejecting turns unlocking into a
     *   fight. The button stays, so it is still one tap.
     */
    fun shouldAutoPrompt(enabled: Boolean, available: Boolean, failCount: Int): Boolean =
        enabled && available && failCount < MAX_AUTO_FAILURES

    /** Whether to show the biometric button at all. */
    fun canOffer(enabled: Boolean, available: Boolean): Boolean = enabled && available

    /** What the keypad's submit key should say: it dismisses the sheet when one is offered. */
    fun fallbackLabel(offered: Boolean): String =
        if (offered) "USE PIN INSTEAD" else "UNLOCK"

    const val MAX_AUTO_FAILURES = 2
}
