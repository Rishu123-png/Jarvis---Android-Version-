package com.jarvis.assistant

import com.jarvis.assistant.device.BiometricPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the lock screen is allowed to offer biometrics.
 *
 * The rule matters more than it looks: a sensor that keeps rejecting must stop firing on its own,
 * because an assistant that demands a fingerprint every time you glance at it is worse than a PIN.
 * But biometrics must never be able to *replace* the PIN, so a disabled or absent sensor always
 * leaves the keypad in charge.
 */
class BiometricPolicyTest {

    @Test
    fun autoPromptNeedsEnabledAndAvailable() {
        assertTrue(BiometricPolicy.shouldAutoPrompt(enabled = true, available = true, failCount = 0))
        assertFalse(BiometricPolicy.shouldAutoPrompt(enabled = false, available = true, failCount = 0))
        assertFalse(BiometricPolicy.shouldAutoPrompt(enabled = true, available = false, failCount = 0))
        assertFalse(BiometricPolicy.shouldAutoPrompt(enabled = false, available = false, failCount = 0))
    }

    @Test
    fun autoPromptStopsAfterRepeatedFailures() {
        assertTrue(BiometricPolicy.shouldAutoPrompt(true, true, 1))
        assertFalse(BiometricPolicy.shouldAutoPrompt(true, true, BiometricPolicy.MAX_AUTO_FAILURES))
        assertFalse(BiometricPolicy.shouldAutoPrompt(true, true, BiometricPolicy.MAX_AUTO_FAILURES + 3))
    }

    @Test
    fun buttonIsHiddenWhenNotUsable() {
        assertTrue(BiometricPolicy.canOffer(enabled = true, available = true))
        assertFalse(BiometricPolicy.canOffer(enabled = true, available = false))
        assertFalse(BiometricPolicy.canOffer(enabled = false, available = true))
    }

    @Test
    fun unlockKeySaysWhatItWillDo() {
        assertEquals("USE PIN INSTEAD", BiometricPolicy.fallbackLabel(enabled = true, available = true))
        assertEquals("UNLOCK", BiometricPolicy.fallbackLabel(enabled = false, available = true))
        assertEquals("UNLOCK", BiometricPolicy.fallbackLabel(enabled = true, available = false))
    }
}
