package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.model.ConfigSnapshot
import com.exoplayerprofiler.internal.model.DeviceInfo
import com.exoplayerprofiler.internal.model.PlaybackStateChangedV3
import com.exoplayerprofiler.internal.model.SessionHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `session end writes both files and leaves no temporary files`() {
        val header = SessionHeader(
            sessionId = "abcdef12-0000", packageName = "p", startedAtEpochMs = 1_700_000_000_000L,
            exportedAtEpochMs = 0L, durationMs = 0L, eventCount = 0, droppedEvents = 0, redacted = true,
            device = DeviceInfo("m", "d", "14", 34), media3Version = "1.11.0", sdkVersion = null,
            config = ConfigSnapshot("FULL", 10_000),
        )
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(PlaybackStateChangedV3(tMs = 1, wallMs = 0L, thread = "main", state = 3))
        sink.onSessionEnded("abcdef12-0000", 1, header)

        val names = tmp.root.list()!!.toList()
        assertEquals(2, names.count { it.endsWith(".json") || it.endsWith(".ndjson") })
        assertTrue(names.none { it.endsWith(".tmp") })
    }
}
