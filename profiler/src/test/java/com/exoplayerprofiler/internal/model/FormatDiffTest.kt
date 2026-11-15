package com.exoplayerprofiler.internal.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDiffTest {

    private fun format(
        codecs: String? = "avc1.4D401F",
        width: Int = 960,
        height: Int = 540,
        bitrate: Int = 2_000_000,
        hdrType: String = "SDR",
    ) = FormatInfo(
        id = "1", sampleMime = "video/avc", containerMime = "video/mp4", codecs = codecs,
        bitrate = bitrate, averageBitrate = bitrate, peakBitrate = bitrate,
        width = width, height = height, frameRate = 30f, pixelRatio = 1f,
        channels = 0, sampleRate = 0, language = null, roleFlags = 0,
        color = ColorDescription(
            space = "BT709", range = "LIMITED", transfer = "SDR", hdrType = hdrType,
            lumaBitDepth = 8, chromaBitDepth = 8, rawSpace = 1, rawRange = 2, rawTransfer = 3,
        ),
    )

    @Test
    fun `identical formats give same format verdict`() {
        val deltas = FormatDiff.diff(format(), format())
        assertEquals("same format", FormatDiff.verdict(deltas))
        assertEquals(false, deltas.any { it.mismatch })
    }

    @Test
    fun `different resolution and codecs are reported as mismatch`() {
        val deltas = FormatDiff.diff(format(), format(codecs = "avc1.64001F", width = 640, height = 360))
        assertEquals("mismatch", FormatDiff.verdict(deltas))
        assertEquals(setOf("codecs", "resolution"), deltas.filter { it.mismatch }.map { it.field }.toSet())
    }

    @Test
    fun `hdr difference is reported`() {
        val deltas = FormatDiff.diff(format(hdrType = "SDR"), format(hdrType = "HDR10"))
        assertEquals(listOf("hdrType"), deltas.filter { it.mismatch }.map { it.field })
    }
}
