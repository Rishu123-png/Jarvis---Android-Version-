package com.jarvis.assistant

import com.jarvis.assistant.voice.Wav
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The WAV container.
 *
 * Providers reject a file whose header disagrees with its payload, and the failure comes back as a
 * vague 400 — so the arithmetic is pinned here instead of being discovered on a phone.
 */
class WavTest {

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    @Test
    fun headerIsPrependedAndPayloadPreserved() {
        val pcm = ByteArray(1_600) { (it % 120).toByte() }
        val wav = Wav.wrapPcm(pcm, sampleRate = 16_000)

        assertEquals(Wav.HEADER_BYTES + pcm.size, wav.size)
        assertEquals(pcm.size, wav.size - Wav.HEADER_BYTES)
    }

    @Test
    fun containerChunksAreCorrect() {
        val wav = Wav.wrapPcm(ByteArray(320), sampleRate = 16_000)

        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
    }

    @Test
    fun sizesAndFormatMatchThePayload() {
        val pcm = ByteArray(320)
        val wav = Wav.wrapPcm(pcm, sampleRate = 16_000)

        assertEquals(36 + pcm.size, le32(wav, 4))
        assertEquals(pcm.size, le32(wav, 40))
        assertEquals(1, le16(wav, 20))            // PCM
        assertEquals(1, le16(wav, 22))            // mono
        assertEquals(16_000, le32(wav, 24))       // sample rate
        assertEquals(32_000, le32(wav, 28))       // byte rate
        assertEquals(2, le16(wav, 32))            // block align
        assertEquals(16, le16(wav, 34))           // bits per sample
    }

    @Test
    fun emptyPayloadStillProducesAValidHeader() {
        val wav = Wav.wrapPcm(ByteArray(0), sampleRate = 8_000)
        assertEquals(Wav.HEADER_BYTES, wav.size)
        assertEquals(0, le32(wav, 40))
    }
}
