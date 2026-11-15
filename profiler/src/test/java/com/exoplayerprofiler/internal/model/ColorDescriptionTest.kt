package com.exoplayerprofiler.internal.model

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Uses Media3's own [C] constants. The values are not the android.media.MediaFormat ones:
 * BT709 is 1 and LIMITED range is 2 in Media3, so literals here would test the wrong mapping.
 */
class ColorDescriptionTest {

  @Test
  fun `null colorInfo defaults to SDR`() {
    val desc = ColorDescription.from(null)
    assertNull(desc.space)
    assertNull(desc.range)
    assertNull(desc.transfer)
    assertEquals("SDR", desc.hdrType)
    assertEquals(-1, desc.rawSpace)
  }

  @Test
  fun `BT709 SDR limited range decodes correctly`() {
    val colorInfo = ColorInfo.Builder()
      .setColorSpace(C.COLOR_SPACE_BT709)
      .setColorRange(C.COLOR_RANGE_LIMITED)
      .setColorTransfer(C.COLOR_TRANSFER_SDR)
      .build()

    val desc = ColorDescription.from(colorInfo, "avc1.4d401f")
    assertEquals("BT709", desc.space)
    assertEquals("LIMITED", desc.range)
    assertEquals("SDR", desc.transfer)
    assertEquals("SDR", desc.hdrType)
  }

  @Test
  fun `BT2020 ST2084 PQ with HEVC decodes as HDR10`() {
    val colorInfo = ColorInfo.Builder()
      .setColorSpace(C.COLOR_SPACE_BT2020)
      .setColorRange(C.COLOR_RANGE_LIMITED)
      .setColorTransfer(C.COLOR_TRANSFER_ST2084)
      .build()

    val desc = ColorDescription.from(colorInfo, "hev1.2.4.L153.B0")
    assertEquals("BT2020", desc.space)
    assertEquals("ST2084", desc.transfer)
    assertEquals("HDR10", desc.hdrType)
  }

  @Test
  fun `HLG transfer decodes as HLG`() {
    val colorInfo = ColorInfo.Builder()
      .setColorSpace(C.COLOR_SPACE_BT2020)
      .setColorTransfer(C.COLOR_TRANSFER_HLG)
      .build()

    val desc = ColorDescription.from(colorInfo)
    assertEquals("HLG", desc.transfer)
    assertEquals("HLG", desc.hdrType)
  }

  @Test
  fun `Dolby Vision codec decodes as DOLBY_VISION`() {
    val colorInfo = ColorInfo.Builder()
      .setColorSpace(C.COLOR_SPACE_BT2020)
      .setColorTransfer(C.COLOR_TRANSFER_ST2084)
      .build()

    val desc = ColorDescription.from(colorInfo, "dvh1.05.01")
    assertEquals("DOLBY_VISION", desc.hdrType)
  }
}
