package com.jarvis.assistant.voice

import java.io.ByteArrayOutputStream

/**
 * Wraps raw PCM in a RIFF/WAVE container.
 *
 * Transcribe endpoints take a file, not a stream, so the recorder's bytes need a 44-byte header.
 * Pure arithmetic, which is exactly why it is separate from the network call: an off-by-four here
 * produces audio providers quietly reject, and it is cheaper to prove with a test than on a phone.
 */
object Wav {

    const val HEADER_BYTES = 44

    fun wrapPcm(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(HEADER_BYTES + pcm.size)

        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))

        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }

        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        ascii("RIFF")
        le32(36 + pcm.size)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)              // PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(bitsPerSample)
        ascii("data")
        le32(pcm.size)
        out.write(pcm)

        return out.toByteArray()
    }
}
