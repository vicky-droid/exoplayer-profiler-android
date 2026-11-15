package com.exoplayerprofiler.internal.json

import com.exoplayerprofiler.internal.model.*
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventJsonWriterGoldenTest {

  @Test
  fun `headerToJson produces deterministic valid JSON with expected fields`() {
    val header = SessionHeader(
      schemaVersion = 3,
      sessionId = "cbc71f7d-f0bb-4d6d-8d13-d243fd1e5639",
      packageName = "com.firstlight.vstb7.mobile",
      startedAtEpochMs = 1789068029503L,
      exportedAtEpochMs = 1789068212191L,
      durationMs = 116747L,
      eventCount = 799,
      droppedEvents = 0,
      redacted = true,
      device = DeviceInfo(
        manufacturer = "samsung",
        model = "SM-E625F",
        android = "13",
        apiLevel = 33
      ),
      media3Version = "1.11.0",
      sdkVersion = null,
      config = ConfigSnapshot(
        captureLevel = "FULL",
        maxEventsPerSession = 10000,
      ),
      fileErrors = 0
    )

    val jsonStr = EventJsonWriter.headerToJson(header)
    val parsed = JSONObject(jsonStr)

    assertEquals(3, parsed.getInt("schemaVersion"))
    assertEquals("cbc71f7d-f0bb-4d6d-8d13-d243fd1e5639", parsed.getString("id"))
    assertEquals("cbc71f7d-f0bb-4d6d-8d13-d243fd1e5639", parsed.getString("sessionId"))
    assertEquals("com.firstlight.vstb7.mobile", parsed.getString("packageName"))
    assertTrue(parsed.getBoolean("redacted"))
    assertEquals("samsung", parsed.getJSONObject("device").getString("manufacturer"))
    assertEquals("SM-E625F", parsed.getJSONObject("device").getString("model"))
    assertEquals("FULL", parsed.getJSONObject("config").getString("captureLevel"))
  }

  @Test
  fun `aggregatesToJson produces deterministic valid JSON`() {
    val aggregates = Aggregates(
      categoryCounts = mapOf("CHUNK" to 253, "DECODER" to 2),
      errorCount = 8,
      chunkCount = 118,
      switchCount = 2,
      drmCount = 12,
      rebufferCount = 1,
      rebufferTotalMs = 1840L,
      droppedFrames = 14L,
      audioUnderruns = 0
    )

    val jsonStr = EventJsonWriter.aggregatesToJson(aggregates)
    val parsed = JSONObject(jsonStr)

    assertEquals(8, parsed.getInt("errorCount"))
    assertEquals(118, parsed.getInt("chunkCount"))
    assertEquals(1840L, parsed.getLong("rebufferTotalMs"))
    assertEquals(14L, parsed.getLong("droppedFrames"))
    assertEquals(253, parsed.getJSONObject("categoryCounts").getInt("CHUNK"))
    assertEquals(2, parsed.getJSONObject("categoryCounts").getInt("DECODER"))
  }

  @Test
  fun `eventToJson formats events with typed envelope and details`() {
    val event = RenderedFirstFrameV3(
      tMs = 5426L,
      wallMs = 1789068034585L,
      thread = "main",
      contentKind = ContentKind.CONTENT,
      presentationTimeUs = 1234567L
    )

    val jsonStr = EventJsonWriter.eventToJson(event)
    val parsed = JSONObject(jsonStr)

    assertEquals(5426L, parsed.getLong("tMs"))
    assertEquals(1789068034585L, parsed.getLong("wallMs"))
    assertEquals("main", parsed.getString("thread"))
    assertEquals("VIDEO", parsed.getString("category"))
    assertEquals("RenderedFirstFrame", parsed.getString("event"))
    assertEquals("CONTENT", parsed.getString("kind"))
    assertEquals(1234567L, parsed.getJSONObject("details").getLong("presentationTimeUs"))
  }
}
