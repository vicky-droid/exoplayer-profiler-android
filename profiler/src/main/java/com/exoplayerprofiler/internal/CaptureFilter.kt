package com.exoplayerprofiler.internal

import com.exoplayerprofiler.CaptureLevel

/**
 * Which events each [CaptureLevel] records (plan 1.6). Each level is an allow-list.
 *
 * Every level keeps the session boundaries (SESSION), playback errors (ERROR), DRM and network events.
 * Without SESSION the file would have no end marker, and without ERROR the errors would be lost. The
 * other categories are kept by FULL, and by STARTUP_ONLY only up to the first frame.
 */
internal object CaptureFilter {

    /** Categories every level records. */
    private val ALWAYS = setOf("SESSION", "ERROR", "DRM", "NETWORK")

    /**
     * True when an event in [category] is recorded. [afterFirstFrame] is true once the first frame has been
     * rendered in the session.
     */
    fun allows(level: CaptureLevel, category: String, afterFirstFrame: Boolean): Boolean = when (level) {
        CaptureLevel.FULL -> true
        CaptureLevel.STARTUP_ONLY -> !afterFirstFrame || category in ALWAYS
        CaptureLevel.ERROR -> category in ALWAYS
    }
}
