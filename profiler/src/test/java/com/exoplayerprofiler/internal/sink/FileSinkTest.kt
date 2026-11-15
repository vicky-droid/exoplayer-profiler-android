package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.model.ConfigSnapshot
import com.exoplayerprofiler.internal.model.DeviceInfo
import com.exoplayerprofiler.internal.model.DroppedFramesV3
import com.exoplayerprofiler.internal.model.PlaybackStateChangedV3
import com.exoplayerprofiler.internal.model.RenderedFirstFrameV3
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.model.TrackSwitchV3
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val header = SessionHeader(
        sessionId = "abcdef12-0000-0000-0000-000000000000",
        packageName = "com.example",
        startedAtEpochMs = 1_700_000_000_000L,
        exportedAtEpochMs = 0L,
        durationMs = 0L,
        eventCount = 0,
        droppedEvents = 0,
        redacted = true,
        device = DeviceInfo("Google", "Pixel", "14", 34),
        media3Version = "1.11.0",
        sdkVersion = null,
        config = ConfigSnapshot("FULL", 10_000),
    )

    private fun frame(t: Long) = RenderedFirstFrameV3(
        tMs = t, wallMs = 0L, thread = "main", presentationTimeUs = 0L,
    )

    private fun state(t: Long, state: Int) = PlaybackStateChangedV3(
        tMs = t, wallMs = 0L, thread = "main", state = state,
    )

    private fun readSession(): JSONObject {
        val file = tmp.root.listFiles()!!.single { it.name.endsWith(".json") }
        return JSONObject(file.readText())
    }

    @Test
    fun `events are written in timestamp order and header count matches`() {
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(frame(300))
        sink.onEvent(state(100, 2))
        sink.onEvent(state(200, 3))
        sink.onSessionEnded("abcdef12-0000-0000-0000-000000000000", 3, header)

        val json = readSession()
        val events = json.getJSONArray("events")
        assertEquals(3, events.length())
        assertEquals(3, json.getJSONObject("session").getInt("eventCount"))
        val times = (0 until events.length()).map { events.getJSONObject(it).getLong("tMs") }
        assertEquals(listOf(100L, 200L, 300L), times)
    }

    @Test
    fun `aggregates count switches, dropped frames and rebuffers after the first frame`() {
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(state(50, 2))                 // startup buffering: not a rebuffer
        sink.onEvent(frame(1_000))
        sink.onEvent(state(1_500, 2))              // rebuffer starts
        sink.onEvent(state(1_800, 3))              // rebuffer ends after 300 ms
        sink.onEvent(TrackSwitchV3(tMs = 2_000, wallMs = 0L, thread = "main", trackType = 2,
            fromFormatId = "a", toFormatId = "b", reason = 0, bufferMs = 0L))
        sink.onEvent(DroppedFramesV3(tMs = 2_100, wallMs = 0L, thread = "main", droppedFrames = 5, elapsedMs = 1_000L))
        sink.onEvent(DroppedFramesV3(tMs = 2_200, wallMs = 0L, thread = "main", droppedFrames = 3, elapsedMs = 1_000L))
        sink.onSessionEnded("abcdef12-0000-0000-0000-000000000000", 7, header)

        val agg = readSession().getJSONObject("aggregates")
        assertEquals(1, agg.getInt("rebufferCount"))
        assertEquals(300L, agg.getLong("rebufferTotalMs"))
        assertEquals(1, agg.getInt("switchCount"))
        assertEquals(8L, agg.getLong("droppedFrames"))
        assertTrue(agg.getJSONObject("categoryCounts").getInt("PLAYBACK") == 3)
    }

    @Test
    fun `events beyond the per-session cap are not written and are counted as dropped`() {
        val sink = FileSink(tmp.root, redacted = true, maxEvents = 3)
        repeat(5) { sink.onEvent(state(it * 10L, 2)) }
        // Queue overflow of 2 reported by the engine, plus 2 events over the cap here.
        sink.onSessionEnded("abcdef12-0000-0000-0000-000000000000", 5, header.copy(droppedEvents = 2))

        val json = readSession()
        assertEquals(3, json.getJSONArray("events").length())
        val session = json.getJSONObject("session")
        assertEquals(3, session.getInt("eventCount"))
        assertEquals(4, session.getInt("droppedEvents"))
        // Aggregates still describe every event that arrived.
        assertEquals(5, json.getJSONObject("aggregates").getJSONObject("categoryCounts").getInt("PLAYBACK"))
    }

    @Test
    fun `cap counters reset between sessions`() {
        val sink = FileSink(tmp.root, redacted = true, maxEvents = 1)
        repeat(3) { sink.onEvent(state(it.toLong(), 2)) }
        sink.onSessionEnded("abcdef12-0000-0000-0000-000000000000", 3, header)
        tmp.root.listFiles()!!.forEach { it.delete() }

        sink.onEvent(state(0L, 2))
        sink.onSessionEnded("abcdef12-0000-0000-0000-000000000000", 1, header)
        val session = readSession().getJSONObject("session")
        assertEquals(1, session.getInt("eventCount"))
        assertEquals(0, session.getInt("droppedEvents"))
    }
}
