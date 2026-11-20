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
  fun `headerToJson writes drop counts and health`() {
    val header = SessionHeader(
      sessionId = "11111111-2222-3333-4444-555555555555",
      packageName = "com.example",
      startedAtEpochMs = 1L,
      exportedAtEpochMs = 2L,
      durationMs = 3L,
      eventCount = 4,
      droppedEvents = 7,
      redacted = true,
      device = DeviceInfo("Google", "Pixel", "14", 34),
      media3Version = "1.11.0",
      sdkVersion = null,
      config = ConfigSnapshot("FULL", 10_000),
      droppedByCategory = mapOf("CHUNK" to 5, "NETWORK" to 2),
      outOfSessionEvents = 1,
      cappedEventsCounted = 9,
      health = HealthSnapshot(swallowed = 2, sinkErrors = 1, recoveryErrors = 0, firstError = "boom \"quoted\""),
    )

    val parsed = JSONObject(EventJsonWriter.headerToJson(header))

    assertEquals(5, parsed.getJSONObject("droppedByCategory").getInt("CHUNK"))
    assertEquals(2, parsed.getJSONObject("droppedByCategory").getInt("NETWORK"))
    assertEquals(1, parsed.getInt("outOfSessionEvents"))
    assertEquals(9, parsed.getInt("cappedEventsCounted"))
    val health = parsed.getJSONObject("health")
    assertEquals(2, health.getInt("swallowed"))
    assertEquals(1, health.getInt("sinkErrors"))
    assertEquals("boom \"quoted\"", health.getString("firstError"))
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
