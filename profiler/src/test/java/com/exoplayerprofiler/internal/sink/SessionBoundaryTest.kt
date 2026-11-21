package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.model.ConfigSnapshot
import com.exoplayerprofiler.internal.model.ContentKind
import com.exoplayerprofiler.internal.model.DeviceInfo
import com.exoplayerprofiler.internal.model.PlaybackStateChangedV3
import com.exoplayerprofiler.internal.model.RenderedFirstFrameV3
import com.exoplayerprofiler.internal.model.SessionEndedV3
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.model.SessionStartedV3
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Events between one session's end and the next session's start belong to no session (C3). */
class SessionBoundaryTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun header(id: String, startedAt: Long) = SessionHeader(
    sessionId = id,
    packageName = "com.example",
    startedAtEpochMs = startedAt,
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

  private fun started(id: String) = SessionStartedV3(
    tMs = 0L, wallMs = 0L, thread = "main", contentKind = ContentKind.UNKNOWN,
    device = "Google Pixel", sessionId = id,
  )

  private fun ended(id: String, t: Long, count: Int) = SessionEndedV3(
    tMs = t, wallMs = 0L, thread = "main", contentKind = ContentKind.UNKNOWN,
    sessionId = id, eventCount = count,
  )

  private fun state(t: Long, state: Int) = PlaybackStateChangedV3(tMs = t, wallMs = 0L, thread = "main", state = state)

  private fun frame(t: Long) = RenderedFirstFrameV3(tMs = t, wallMs = 0L, thread = "main", presentationTimeUs = 0L)

  private fun sessionFiles(): List<JSONObject> =
    tmp.root.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }
      .map { JSONObject(File(tmp.root, it.name).readText()) }

  @Test
  fun `a straggler after the end marker is not written and is reported in the next header`() {
    val a = "aaaaaaaa-0000-0000-0000-000000000000"
    val b = "bbbbbbbb-0000-0000-0000-000000000000"
    val sink = FileSink(tmp.root, redacted = true)

    sink.onEvent(started(a))
    sink.onEvent(frame(300L))
    sink.onEvent(ended(a, 400L, 2))
    sink.onSessionEnded(a, 2, header(a, 1_700_000_000_000L))

    // Late events from session A, after its end marker and before B starts.
    sink.onEvent(state(50L, 2))
    sink.onEvent(state(60L, 3))

    sink.onEvent(started(b))
    sink.onEvent(frame(10L))
    sink.onEvent(ended(b, 20L, 2))
    sink.onSessionEnded(b, 2, header(b, 1_700_000_100_000L))

    val sessions = sessionFiles().associateBy { it.getJSONObject("session").getString("sessionId") }
    assertEquals(setOf(a, b), sessions.keys)

    val first = sessions.getValue(a)
    assertEquals(0, first.getJSONObject("session").getInt("outOfSessionEvents"))

    val second = sessions.getValue(b)
    assertEquals(2, second.getJSONObject("session").getInt("outOfSessionEvents"))
    val events = second.getJSONArray("events")
    val names = (0 until events.length()).map { events.getJSONObject(it).getString("event") }
    assertEquals(listOf("SessionStarted", "RenderedFirstFrame", "SessionEnded"), names)
    for (i in 0 until events.length()) {
      assertTrue("no event from A's stragglers at t<=60", events.getJSONObject(i).getLong("tMs") != 50L)
    }
  }
}
