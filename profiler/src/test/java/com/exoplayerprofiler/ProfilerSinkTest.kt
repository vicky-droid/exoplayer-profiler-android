package com.exoplayerprofiler

import com.exoplayerprofiler.internal.model.ConfigSnapshot
import com.exoplayerprofiler.internal.model.CustomEventV3
import com.exoplayerprofiler.internal.model.DeviceInfo
import com.exoplayerprofiler.internal.model.PlaybackStateChangedV3
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.sink.FileSink
import com.exoplayerprofiler.internal.sink.ProfilerSinkAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfilerSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun header(attrs: Map<String, String> = emptyMap()) = SessionHeader(
        sessionId = "abcdef12-0000-0000-0000-000000000000",
        packageName = "com.example",
        startedAtEpochMs = 1_700_000_000_000L,
        exportedAtEpochMs = 0L, durationMs = 0L, eventCount = 0, droppedEvents = 0,
        redacted = true,
        device = DeviceInfo("Google", "Pixel", "14", 34),
        media3Version = "1.11.0", sdkVersion = null,
        config = ConfigSnapshot("FULL", 10_000),
        attributes = attrs,
    )

    private fun state(t: Long) = PlaybackStateChangedV3(tMs = t, wallMs = 0L, thread = "main", state = 3)

    private class Recorder : ProfilerSink {
        val events = mutableListOf<String>()
        val sessions = mutableListOf<Pair<String, String>>()
        override fun onEvent(eventJson: String) { events.add(eventJson) }
        override fun onSessionEnded(sessionId: String, sessionJson: String) { sessions.add(sessionId to sessionJson) }
    }

    private fun sessionFile() =
        JSONObject(tmp.root.listFiles()!!.single { it.name.endsWith(".json") }.readText())

    @Test
    fun `attributes appear in the session header`() {
        val sink = FileSink(tmp.root)
        sink.onSessionEnded("s", 0, header(mapOf("user" to "u1", "plan" to "gold")))
        val attrs = sessionFile().getJSONObject("session").getJSONObject("attributes")
        assertEquals("u1", attrs.getString("user"))
        assertEquals("gold", attrs.getString("plan"))
    }

    @Test
    fun `header has empty attributes object when none set`() {
        FileSink(tmp.root).onSessionEnded("s", 0, header())
        assertEquals(0, sessionFile().getJSONObject("session").getJSONObject("attributes").length())
    }

    @Test
    fun `engine attribute null value removes the key`() {
        val engine = com.exoplayerprofiler.internal.PlaybackProfilerEngine()
        engine.setSessionAttribute("a", "1")
        engine.setSessionAttribute("b", "2")
        engine.setSessionAttribute("a", null)
        // Not active (no config), so read back through the public map snapshot via reflection-free path:
        val f = engine.javaClass.getDeclaredField("attributes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        assertEquals(mapOf("b" to "2"), HashMap(f.get(engine) as Map<String, String>))
    }

    @Test
    fun `sink receives events in order and session end, removeSink stops delivery`() {
        val adapter = ProfilerSinkAdapter()
        val file = FileSink(tmp.root).also { it.outputListener = adapter }
        val rec = Recorder()
        adapter.add(rec)
        file.onEvent(state(100)); file.onEvent(state(200)); file.onEvent(state(300))
        assertEquals(listOf(100L, 200L, 300L), rec.events.map { JSONObject(it).getLong("tMs") })
        file.onSessionEnded("abcdef12-0000-0000-0000-000000000000", 3, header(mapOf("k" to "v")))
        assertEquals(1, rec.sessions.size)
        assertEquals("abcdef12-0000-0000-0000-000000000000", rec.sessions[0].first)
        assertEquals(
            sessionFile().toString(), JSONObject(rec.sessions[0].second).toString())
        adapter.remove(rec)
        file.onEvent(state(400))
        assertEquals(3, rec.events.size)
    }

    @Test
    fun `sink added late still receives events and a throwing sink is isolated`() {
        val adapter = ProfilerSinkAdapter()
        val file = FileSink(tmp.root).also { it.outputListener = adapter }
        file.onEvent(state(1))
        val bad = object : ProfilerSink {
            override fun onEvent(eventJson: String) = throw IllegalStateException("x")
            override fun onSessionEnded(sessionId: String, sessionJson: String) = throw IllegalStateException("x")
        }
        val rec = Recorder()
        adapter.add(bad); adapter.add(rec)
        file.onEvent(state(2))
        assertEquals(1, rec.events.size)
    }

    @Test
    fun `custom events reach the file`() {
        val file = FileSink(tmp.root)
        file.onEvent(CustomEventV3(tMs = 5, wallMs = 0L, thread = "main", name = "button_play", details = null))
        file.onSessionEnded("s", 1, header())
        val events = sessionFile().getJSONArray("events")
        assertEquals(1, events.length())
        assertTrue(events.getJSONObject(0).toString().contains("button_play"))
    }
}
