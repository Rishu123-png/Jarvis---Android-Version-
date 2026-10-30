package com.jarvis.assistant

import com.jarvis.assistant.device.SystemToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switches: how a spoken word becomes a command, and how a percentage becomes the level the
 * system stores. None of this touches a phone, which is why it can be pinned down here.
 */
class SystemTogglesTest {

    @Test
    fun onAndOffAreUnderstoodInBothLanguages() {
        assertEquals(SystemToggles.Desired.ON, SystemToggles.parseDesired("on"))
        assertEquals(SystemToggles.Desired.ON, SystemToggles.parseDesired("Enable"))
        assertEquals(SystemToggles.Desired.ON, SystemToggles.parseDesired("chalu"))
        assertEquals(SystemToggles.Desired.OFF, SystemToggles.parseDesired("OFF"))
        assertEquals(SystemToggles.Desired.OFF, SystemToggles.parseDesired("band karo"))
        assertEquals(SystemToggles.Desired.TOGGLE, SystemToggles.parseDesired(""))
        assertEquals(SystemToggles.Desired.TOGGLE, SystemToggles.parseDesired("toggle"))
        assertNull(SystemToggles.parseDesired("maybe"))
    }

    @Test
    fun eachSwitchHasARealCommand() {
        assertEquals("svc wifi enable", SystemToggles.commandsFor(SystemToggles.Switch.WIFI, true))
        assertEquals("svc wifi disable", SystemToggles.commandsFor(SystemToggles.Switch.WIFI, false))
        assertEquals("svc data disable", SystemToggles.commandsFor(SystemToggles.Switch.MOBILE_DATA, false))
        assertEquals(
            "cmd bluetooth_manager enable",
            SystemToggles.commandsFor(SystemToggles.Switch.BLUETOOTH, true),
        )
    }

    @Test
    fun airplaneModeSetsTheFlagAndTellsTheSystem() {
        val on = SystemToggles.commandsFor(SystemToggles.Switch.AIRPLANE, true)!!
        assertTrue(on.contains("airplane_mode_on 1"))
        assertTrue(on.contains("--ez state true"))
        val off = SystemToggles.commandsFor(SystemToggles.Switch.AIRPLANE, false)!!
        assertTrue(off.contains("airplane_mode_on 0"))
        assertTrue(off.contains("--ez state false"))
    }

    @Test
    fun onlySwitchesAndroidCanQueryHaveAStateKey() {
        assertTrue(SystemToggles.stateKeyFor(SystemToggles.Switch.WIFI)!!.contains("wifi_on"))
        assertNull(SystemToggles.stateKeyFor(SystemToggles.Switch.HOTSPOT))
        assertEquals(true, SystemToggles.readState("1"))
        assertEquals(false, SystemToggles.readState("0\n"))
        assertNull(SystemToggles.readState("null"))
    }

    @Test
    fun brightnessIsSpokenInPercentAndStoredInTheSystemScale() {
        assertEquals(-1, SystemToggles.brightnessValue("auto"))
        assertEquals(-1, SystemToggles.brightnessValue("Automatic"))
        assertEquals(255, SystemToggles.brightnessValue("100"))
        assertEquals(0, SystemToggles.brightnessValue("0"))
        assertEquals(128, SystemToggles.brightnessValue("50"))
        assertEquals(128, SystemToggles.brightnessValue("50%"))
        assertNull(SystemToggles.brightnessValue("101"))
        assertNull(SystemToggles.brightnessValue("bright"))
    }

    @Test
    fun rotationLockIsOnOrOff() {
        assertEquals(true, SystemToggles.rotationLocked("on"))
        assertEquals(false, SystemToggles.rotationLocked("off"))
        assertNull(SystemToggles.rotationLocked("toggle"))
        assertFalse(SystemToggles.rotationLocked("off")!!)
    }
}
