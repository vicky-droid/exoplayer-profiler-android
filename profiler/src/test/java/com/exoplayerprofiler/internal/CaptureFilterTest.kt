package com.exoplayerprofiler.internal

import com.exoplayerprofiler.CaptureLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFilterTest {

  private val everyCategory = listOf(
    "SESSION", "ERROR", "DRM", "NETWORK", "PLAYBACK", "VIDEO", "AUDIO", "CHUNK",
    "TRACKS", "BANDWIDTH", "DEVICE", "SEEK", "STARTUP", "BUFFER",
  )

  @Test
  fun `full records every category before and after the first frame`() {
    for (category in everyCategory) {
      assertTrue(category, CaptureFilter.allows(CaptureLevel.FULL, category, afterFirstFrame = false))
      assertTrue(category, CaptureFilter.allows(CaptureLevel.FULL, category, afterFirstFrame = true))
    }
  }

  @Test
  fun `startup only records everything up to the first frame`() {
    for (category in everyCategory) {
      assertTrue(category, CaptureFilter.allows(CaptureLevel.STARTUP_ONLY, category, afterFirstFrame = false))
    }
  }

  @Test
  fun `startup only keeps session, error, drm and network after the first frame`() {
    val kept = setOf("SESSION", "ERROR", "DRM", "NETWORK")
    for (category in everyCategory) {
      assertTrue(category, CaptureFilter.allows(CaptureLevel.STARTUP_ONLY, category, afterFirstFrame = true) == (category in kept))
    }
  }

  @Test
  fun `error level keeps only session, error, drm and network`() {
    val kept = setOf("SESSION", "ERROR", "DRM", "NETWORK")
    for (category in everyCategory) {
      assertTrue(category, CaptureFilter.allows(CaptureLevel.ERROR, category, afterFirstFrame = false) == (category in kept))
      assertTrue(category, CaptureFilter.allows(CaptureLevel.ERROR, category, afterFirstFrame = true) == (category in kept))
    }
  }

  @Test
  fun `error level drops playback and device events`() {
    assertFalse(CaptureFilter.allows(CaptureLevel.ERROR, "PLAYBACK", afterFirstFrame = true))
    assertFalse(CaptureFilter.allows(CaptureLevel.ERROR, "DEVICE", afterFirstFrame = false))
  }
}
