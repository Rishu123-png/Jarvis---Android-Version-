package com.jarvis.assistant

import com.jarvis.assistant.data.CoreLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core's address rule, which is the whole security model of "the brain on the PC".
 *
 * Two shapes are reachable and nothing else: https anywhere, or plain http on loopback — the
 * `adb reverse` case, where the bytes travel down the cable and never touch a network. A plain
 * http address on the LAN is refused, because on a wifi anyone can claim to be the core, and a
 * phone cannot tell the difference once a reply is being trusted as instructions.
 *
 * These tests exist so that a future convenience change — "just let it use http on the LAN, it is
 * only my house" — fails CI instead of quietly weakening the app.
 */
class CoreLinkTest {

    @Test
    fun `empty address falls back to the default core`() {
        assertEquals(CoreLink.DEFAULT_BASE, CoreLink.normaliseBase(null))
        assertEquals(CoreLink.DEFAULT_BASE, CoreLink.normaliseBase("   "))
    }

    @Test
    fun `a bare host gets a scheme and the v1 path`() {
        assertEquals("http://127.0.0.1:11434/v1", CoreLink.normaliseBase("127.0.0.1:11434"))
        assertEquals("http://127.0.0.1:11434/v1", CoreLink.normaliseBase("http://127.0.0.1:11434/"))
    }

    @Test
    fun `normalising twice changes nothing`() {
        val once = CoreLink.normaliseBase("https://core.local:8443")
        assertEquals(once, CoreLink.normaliseBase(once))
        assertEquals("https://core.local:8443/v1", once)
    }

    @Test
    fun `an address that already has v1 keeps it`() {
        assertEquals("https://core.local/v1", CoreLink.normaliseBase("https://core.local/v1/"))
    }

    @Test
    fun `loopback and the emulator host count as local`() {
        assertTrue(CoreLink.isLoopback("http://127.0.0.1:11434/v1"))
        assertTrue(CoreLink.isLoopback("http://localhost:11434"))
        assertTrue(CoreLink.isLoopback("http://[::1]:11434/v1"))
        assertTrue(CoreLink.isLoopback("http://10.0.2.2:11434"))
    }

    @Test
    fun `a machine on the wifi is not local`() {
        assertFalse(CoreLink.isLoopback("http://192.168.1.20:11434/v1"))
        assertFalse(CoreLink.isLoopback("http://192.168.1.20:11434/v1"))
        assertFalse(CoreLink.isLoopback("http://jarvis-pc.local:11434"))
    }

    @Test
    fun `plain http on the network is refused`() {
        assertFalse(CoreLink.isAllowed("http://192.168.1.20:11434"))
        assertFalse(CoreLink.isAllowed("http://jarvis-pc.local:11434/v1"))
        assertFalse(CoreLink.isAllowed("http://10.0.0.5:11434"))
    }

    @Test
    fun `https is allowed wherever the core lives`() {
        assertTrue(CoreLink.isAllowed("https://192.168.1.20:11434"))
        assertTrue(CoreLink.isAllowed("https://core.example.net"))
        assertTrue(CoreLink.isAllowed("https://core.example.net/v1"))
    }

    @Test
    fun `loopback over the cable is allowed`() {
        assertTrue(CoreLink.isAllowed(CoreLink.DEFAULT_BASE))
        assertTrue(CoreLink.isAllowed("http://localhost:11434"))
    }

    @Test
    fun `the endpoint is the openai chat path on the core`() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            CoreLink.endpoint("http://127.0.0.1:11434"),
        )
        assertEquals(
            "https://core.local/v1/chat/completions",
            CoreLink.endpoint("https://core.local/v1"),
        )
    }

    @Test
    fun `the host is shown without scheme or path`() {
        assertEquals("127.0.0.1", CoreLink.hostOf("http://127.0.0.1:11434/v1"))
        assertEquals("core.example.net", CoreLink.hostOf("HTTPS://CORE.example.net/v1"))
        assertNull(CoreLink.hostOf("nonsense://"))
    }

    @Test
    fun `the refusal names the address and the way forward`() {
        val message = CoreLink.refusalFor("http://192.168.1.20:11434")
        assertTrue(message.contains("192.168.1.20:11434"))
        assertTrue(message.contains("adb reverse"))
    }
}
