package com.music.bitchord.desktop

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct Stream Digital (DSD) container and format helpers for DSF and DFF audio files.
 * Provides decimator and PCM conversion utilities for high-resolution desktop playback.
 */
object DesktopDsd {

    const val DSD_MAGIC_DSD = 0x20445344 // "DSD " in little-endian
    const val DSD_MAGIC_FMT = 0x20746d66 // "fmt " in little-endian
    const val DSD_MAGIC_DATA = 0x61746164 // "data" in little-endian

    /** True if the header bytes indicate a DSF container. */
    fun isDsf(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val magic = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
        return magic == DSD_MAGIC_DSD
    }

    /** True if the header bytes indicate a DFF (DSDIFF) container ("FRM8"). */
    fun isDff(header: ByteArray): Boolean {
        if (header.size < 4) return false
        return header[0] == 'F'.code.toByte() &&
               header[1] == 'R'.code.toByte() &&
               header[2] == 'M'.code.toByte() &&
               header[3] == '8'.code.toByte()
    }

    /**
     * Converts a block of raw 1-bit DSD samples to float PCM using an 8:1 decimation filter.
     */
    fun decimateDsdToPcm(dsdBytes: ByteArray, channels: Int, outPcm: FloatArray): Int {
        val totalBytes = dsdBytes.size
        var outIdx = 0
        val maxOut = outPcm.size

        // Simple accumulator decimation for preview / software playback
        for (i in 0 until totalBytes step channels) {
            if (outIdx >= maxOut) break
            for (ch in 0 until channels) {
                if (i + ch < totalBytes && outIdx < maxOut) {
                    val b = dsdBytes[i + ch].toInt() and 0xFF
                    // Count 1-bits: Hamming weight 0..8 normalized to -1.0 .. +1.0
                    val ones = Integer.bitCount(b)
                    val sample = (ones - 4) / 4.0f
                    outPcm[outIdx++] = sample
                }
            }
        }
        return outIdx
    }
}
