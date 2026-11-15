package com.exoplayerprofiler.internal

import androidx.test.platform.app.InstrumentationRegistry
import com.exoplayerprofiler.ProfilerConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Engine behaviour that needs a real Looper and file system, so it runs on a device or emulator.
 * Each test starts from an empty profiler directory and reads the written file right after
 * [PlaybackProfilerEngine.reset]: reset must flush the session before it returns.
 */
class EngineSessionInstrumentedTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val profilerDir = File(context.filesDir, "playback_profiler")

  @Before
  fun emptyProfilerDir() {
    profilerDir.deleteRecursively()
  }

  private fun sessionFiles(): List<File> =
    profilerDir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

  private fun readOnlySession(): JSONObject {
    val files = sessionFiles()
    assertEquals("expected exactly one session file", 1, files.size)
    return JSONObject(files.single().readText())
  }

  @Test
  fun sessionFileIsRedactedAndHasNoRawContentIdOrToken() {
    val engine = PlaybackProfilerEngine()
    engine.init(context, ProfilerConfig(enabled = true))

    engine.reportPlaybackRequest("content-123-secret", mapOf("title" to "Movie", "authToken" to "tok-SECRET"))
    engine.reportPlayClick()
    engine.reportAuthStart()
    engine.reportAuthEnd(12L)
    engine.reportCustomEvent("button", mapOf("label" to "ok"))
    engine.detach()
    engine.reset()

    val text = sessionFiles().single().readText()
    assertFalse("raw content id leaked", text.contains("content-123-secret"))
    assertFalse("token value leaked", text.contains("tok-SECRET"))

    val json = readOnlySession()
    assertEquals(3, json.getInt("schemaVersion"))
    assertTrue(json.getJSONObject("session").getBoolean("redacted"))
    val names = (0 until json.getJSONArray("events").length())
      .map { json.getJSONArray("events").getJSONObject(it).getString("event") }
    assertTrue("play_click missing: $names", names.contains("play_click"))
    assertTrue("SessionStarted missing: $names", names.contains("SessionStarted"))
  }

  @Test
  fun eventsBeyondTheSessionCapAreDroppedAndCounted() {
    val engine = PlaybackProfilerEngine()
    // The engine raises values below 100 to 100; 300 events exceed that by a wide margin.
    engine.init(context, ProfilerConfig(enabled = true, maxEventsPerSession = 100))

    engine.reportPlaybackRequest("content-cap", null)
    repeat(300) { engine.reportCustomEvent("tick", mapOf("i" to it)) }
    engine.detach()
    engine.reset()

    val json = readOnlySession()
    val session = json.getJSONObject("session")
    val stored = json.getJSONArray("events").length()
    val dropped = session.getInt("droppedEvents")
    assertEquals(100, stored)
    assertEquals(stored, session.getInt("eventCount"))
    assertTrue("expected drops, got $dropped", dropped > 0)

    // Every event that reached the sink is either stored or counted as dropped.
    val categories = json.getJSONObject("aggregates").getJSONObject("categoryCounts")
    val arrived = categories.keys().asSequence().sumOf { categories.getInt(it) }
    assertEquals(arrived, stored + dropped)
  }

  @Test
  fun startingAPlaybackRequestEndsThePreviousSessionFile() {
    val engine = PlaybackProfilerEngine()
    engine.init(context, ProfilerConfig(enabled = true))

    engine.reportPlaybackRequest("first", null)
    engine.reportPlaybackRequest("second", null)
    engine.detach()
    engine.reset()

    assertEquals("one file per playback request", 2, sessionFiles().size)
    sessionFiles().forEach { file ->
      val events = JSONObject(file.readText()).getJSONArray("events")
      assertTrue("empty session file ${file.name}", events.length() > 0)
    }
  }
}
