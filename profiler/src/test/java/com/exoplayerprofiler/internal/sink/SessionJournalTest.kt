package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.model.ConfigSnapshot
import com.exoplayerprofiler.internal.model.ContentKind
import com.exoplayerprofiler.internal.model.DeviceInfo
import com.exoplayerprofiler.internal.model.PlaybackStateChangedV3
import com.exoplayerprofiler.internal.model.RenderedFirstFrameV3
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.model.SessionStartedV3
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sessionId = "9f8e7d6c-1111-2222-3333-444455556666"

    private val template = HeaderTemplate(
        packageName = "com.example",
        media3Version = "1.11.0",
        androidVersion = "14",
        apiLevel = 34,
        config = ConfigSnapshot("FULL", 10_000),
        redacted = true,
    )

    private val endHeader = SessionHeader(
        sessionId = sessionId,
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

    private fun started() = SessionStartedV3(
        tMs = 0L, wallMs = 1_700_000_000_000L, thread = "main",
        contentKind = ContentKind.UNKNOWN, device = "Google Pixel", sessionId = sessionId,
    )

    private fun sessionFiles(): List<File> =
        tmp.root.listFiles()!!.filter { it.name.startsWith("session_") && it.name.endsWith(".json") }

    private fun journals(): List<File> =
        tmp.root.listFiles()!!.filter { it.name.startsWith(SessionJournal.PREFIX) }

    @Test
    fun `a session that never ended is rebuilt from its journal and marked recovered`() {
        // The process stops mid-session: events are journaled, the session is never ended.
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(started())
        sink.onEvent(PlaybackStateChangedV3(tMs = 100L, wallMs = 0L, thread = "main", state = 2))
        sink.onEvent(RenderedFirstFrameV3(tMs = 300L, wallMs = 0L, thread = "main", presentationTimeUs = 0L))
        sink.close()
        assertEquals(1, journals().size)
        assertTrue(sessionFiles().isEmpty())

        assertEquals(1, SessionJournal.recoverAll(tmp.root, template))

        assertTrue(journals().isEmpty())
        val file = sessionFiles().single()
        val json = JSONObject(file.readText())
        val session = json.getJSONObject("session")
        assertEquals(sessionId, session.getString("sessionId"))
        assertTrue(session.getBoolean("recovered"))
        assertEquals(3, json.getJSONArray("events").length())
        assertEquals(3, session.getInt("eventCount"))
        assertEquals(300L, session.getLong("durationMs"))
        assertEquals("Google", session.getJSONObject("device").getString("manufacturer"))
        assertEquals("Pixel", session.getJSONObject("device").getString("model"))
        val aggregates = json.getJSONObject("aggregates")
        assertEquals(1, aggregates.getJSONObject("categoryCounts").getInt("VIDEO"))
        assertNotNull(json.optString("hash").takeIf { it.isNotEmpty() })
    }

    @Test
    fun `a normally ended session leaves no journal and is not marked recovered`() {
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(started())
        sink.onEvent(RenderedFirstFrameV3(tMs = 300L, wallMs = 0L, thread = "main", presentationTimeUs = 0L))
        sink.onSessionEnded(sessionId, 2, endHeader)

        assertTrue(journals().isEmpty())
        val json = JSONObject(sessionFiles().single().readText())
        assertFalse(json.getJSONObject("session").has("recovered"))
        // Nothing left to recover, so a later start changes nothing.
        assertEquals(0, SessionJournal.recoverAll(tmp.root, template))
        assertEquals(1, sessionFiles().size)
    }

    @Test
    fun `a line cut short by a crash is skipped and the rest is recovered`() {
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(started())
        sink.onEvent(RenderedFirstFrameV3(tMs = 300L, wallMs = 0L, thread = "main", presentationTimeUs = 0L))
        sink.close()
        val journal = journals().single()
        journal.appendText("{\"tMs\":400,\"category\":\"PLAY") // torn write

        assertEquals(1, SessionJournal.recoverAll(tmp.root, template))
        val json = JSONObject(sessionFiles().single().readText())
        assertEquals(2, json.getJSONArray("events").length())
    }

    @Test
    fun `a journal with no session start cannot be attributed and is removed`() {
        val orphan = File(tmp.root, "${SessionJournal.PREFIX}1_orphan${SessionJournal.SUFFIX}")
        orphan.writeText("{\"tMs\":5,\"category\":\"VIDEO\",\"event\":\"RenderedFirstFrame\",\"details\":{}}\n")

        assertEquals(0, SessionJournal.recoverAll(tmp.root, template))
        assertFalse(orphan.exists())
        assertNull(sessionFiles().firstOrNull())
    }

    @Test
    fun `recovery is idempotent across restarts`() {
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(started())
        sink.close()
        assertEquals(1, SessionJournal.recoverAll(tmp.root, template))
        // Second start: the journal was removed, so nothing is recovered twice.
        assertEquals(0, SessionJournal.recoverAll(tmp.root, template))
        assertEquals(1, sessionFiles().size)
    }

    /** Writes a journal that holds only a SessionStarted, and returns the journal's session file base name. */
    private fun leftoverJournal(): String {
        val sink = FileSink(tmp.root, redacted = true)
        sink.onEvent(started())
        sink.onEvent(RenderedFirstFrameV3(tMs = 300L, wallMs = 0L, thread = "main", presentationTimeUs = 0L))
        sink.close()
        return SessionFiles.baseName(1_700_000_000_000L, sessionId)
    }

    @Test
    fun `a journal whose session file already exists is removed and the existing file is kept`() {
        val base = leftoverJournal()
        val existing = File(tmp.root, "$base.json")
        existing.writeText("{\"complete\":true}")

        assertEquals(0, SessionJournal.recoverAll(tmp.root, template))

        assertTrue(journals().isEmpty())
        assertEquals("{\"complete\":true}", existing.readText())
    }

    @Test
    fun `a failed write keeps the journal so the next start can recover it`() {
        val base = leftoverJournal()
        // A directory where the temporary NDJSON file must be written makes the session write fail.
        File(tmp.root, "$base.ndjson.tmp").mkdir()

        assertEquals(0, SessionJournal.recoverAll(tmp.root, template))

        assertEquals(1, journals().size)
        assertFalse(File(tmp.root, "$base.json").exists())
    }

    @Test
    fun `a recovered session is handed to the listener`() {
        leftoverJournal()
        val delivered = mutableListOf<String>()
        val listener = object : FileSink.OutputListener {
            override fun onEventJson(eventJson: String) {}
            override fun onSessionJson(sessionId: String, sessionJson: String) { delivered += sessionId }
        }

        assertEquals(1, SessionJournal.recoverAll(tmp.root, template, listener))

        assertEquals(listOf(sessionId), delivered)
    }

    @Test
    fun `a journal that another holder still has locked is left alone`() {
        leftoverJournal()
        val journal = journals().single()
        RandomAccessFile(journal, "rw").use { raf ->
            raf.channel.lock().use {
                assertEquals(0, SessionJournal.recoverAll(tmp.root, template))
            }
        }

        assertTrue(journal.exists())
        assertTrue(sessionFiles().isEmpty())
        // Once the live holder is gone, the same journal is recovered.
        assertEquals(1, SessionJournal.recoverAll(tmp.root, template))
    }
}
