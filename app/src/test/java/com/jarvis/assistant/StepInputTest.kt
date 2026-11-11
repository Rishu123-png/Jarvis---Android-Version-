package com.jarvis.assistant

import com.jarvis.assistant.automation.StepInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions a saved step makes before it touches the phone.
 *
 * Both used to be private functions inside `StepExecutor`, which is 1,100 lines of Android calls and
 * could only be exercised on a device. These are the cases where being wrong is quiet: a webhook
 * pointed at the wrong scheme, or a gesture word that silently does nothing.
 */
class StepInputTest {

    // ---------------------------------------------------------------------------------------------
    // Addresses
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aBareHostGetsHttps() {
        assertEquals("https://example.com/hook", StepInput.normaliseUrl("example.com/hook"))
        assertEquals("https://example.com/hook", StepInput.normaliseUrl("  example.com/hook  "))
    }

    @Test
    fun anAddressThatAlreadyHasASchemeIsKept() {
        assertEquals("https://example.com/a?b=1", StepInput.normaliseUrl("https://example.com/a?b=1"))
        assertEquals("http://192.168.1.4:8080/up", StepInput.normaliseUrl("http://192.168.1.4:8080/up"))
    }

    @Test
    fun theSchemeComparisonIgnoresCase() {
        assertEquals("HTTPS://example.com/x", StepInput.normaliseUrl("HTTPS://example.com/x"))
    }

    @Test
    fun onlyWebSchemesSurvive() {
        // These parse perfectly well as URIs and must still never become a saved step: a routine
        // that can open file:// can read the phone's storage through the browser.
        assertNull(StepInput.normaliseUrl("file:///sdcard/secret.pdf"))
        assertNull(StepInput.normaliseUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(StepInput.normaliseUrl("javascript:alert(1)"))
        assertNull(StepInput.normaliseUrl("content://com.android.contacts/data/1"))
    }

    @Test
    fun anEmptyOrHostlessValueIsNotAnAddress() {
        assertNull(StepInput.normaliseUrl(""))
        assertNull(StepInput.normaliseUrl("   "))
        assertNull(StepInput.normaliseUrl("/just/a/path"))
        assertNull(StepInput.normaliseUrl("https://"))
    }

    @Test
    fun aSpaceInTheAddressIsRejectedRatherThanGuessed() {
        assertNull(StepInput.normaliseUrl("https://example.com/a b"))
        assertNull(StepInput.normaliseUrl("https://exa mple.com"))
    }

    // ---------------------------------------------------------------------------------------------
    // Gestures
    // ---------------------------------------------------------------------------------------------

    @Test
    fun everySpokenWordForAGestureResolvesToTheSameName() {
        assertEquals("back", StepInput.gestureKey("back"))
        assertEquals("back", StepInput.gestureKey("Navigate back"))
        assertEquals("home", StepInput.gestureKey("go home"))
        assertEquals("home", StepInput.gestureKey(" HOME "))
        assertEquals("recents", StepInput.gestureKey("recent apps"))
        assertEquals("notifications", StepInput.gestureKey("shade"))
        assertEquals("notifications", StepInput.gestureKey("notification shade"))
        assertEquals("quick", StepInput.gestureKey("quick settings"))
        assertEquals("lock", StepInput.gestureKey("lock screen"))
    }

    @Test
    fun aWordThatIsNotAGestureHasNoGesture() {
        assertNull(StepInput.gestureKey(""))
        assertNull(StepInput.gestureKey("volume up"))
        assertNull(StepInput.gestureKey("open whatsapp"))
        assertNull(StepInput.gestureKey("homescreen"))
    }

    @Test
    fun theRejectionListsEveryGestureItAccepts() {
        for (name in listOf("back", "home", "recents", "notifications", "quick", "lock")) {
            assertEquals(name, StepInput.gestureKey(name))
            assertTrue("the rejection must offer $name back", StepInput.GESTURES.contains(name))
        }
    }
}
