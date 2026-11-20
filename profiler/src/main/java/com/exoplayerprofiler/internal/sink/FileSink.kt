package com.exoplayerprofiler.internal.sink

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.exoplayerprofiler.internal.json.EventJsonWriter
import com.exoplayerprofiler.internal.model.Aggregates
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.redact.Redactor
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * File sink for the playback profiler. Buffers events during the session and
 * writes a final redacted session.json at session end.
 *
 * Files per session (in appContext.filesDir/playback_profiler/):
 *   - session_<yyyyMMdd_HHmmss>_<id8>.ndjson  (first line = header)
 *   - session_<yyyyMMdd_HHmmss>_<id8>.json    (final, at session end)
 *   - journal_<millis>_<id8>.journal          (while the session runs; every stored event, in arrival
 *                                              order. Deleted once the session is written. If the app
 *                                              stops before the session ends, [SessionJournal] rebuilds
 *                                              the session from it on the next start.)
 *
 * Both session files are redacted. Raw variants only exist for debug builds.
 */
@UnstableApi
internal class FileSink(
    private val filesDir: File,
    private val redacted: Boolean = true,
    private val maxEvents: Int = 10_000,
) : EventSink {

    /** Receives the same event and session JSON that is written to the files. */
    interface OutputListener {
        fun onEventJson(eventJson: String)
        fun onSessionJson(sessionId: String, sessionJson: String)
    }

    @Volatile var outputListener: OutputListener? = null

    private var sessionHeader: SessionHeader? = null
    // (tMs, line) pairs. Some events are produced off the player thread (for example the device
    // capability probe), so arrival order is not timeline order; the file is sorted by tMs on write.
    private val eventLines = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, String>>()
    // Only the writer thread touches these fields.
    private var storedEvents = 0
    private var capDropped = 0
    private val aggregates = AggregateTracker()
    private var journal: BufferedWriter? = null
    private var journalFile: File? = null

    override fun onEvent(event: SchemaV3Event) {
        try {
            val json = EventJsonWriter.eventToJson(event)
            // Apply token redaction to ensure no raw token leaks into file sinks
            val lineToWrite = if (redacted) Redactor.redactTokens(json) else json
            if (storedEvents < maxEvents) {
                eventLines.add(event.tMs to lineToWrite)
                storedEvents++
                appendJournal(lineToWrite)
            } else {
                // Over the per-session cap: not written to the file, counted in the header.
                capDropped++
            }
            try { outputListener?.onEventJson(lineToWrite) } catch (_: Throwable) {}
            aggregates.track(event.category, event.event, json)
        } catch (e: Exception) {
            Log.w("PlaybackProfiler", "FileSink.onEvent failed: ${e.message}")
        }
    }

    override fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?) {
        sessionHeader = header
        var wroteSession = false
        try {
            val rawHdr = header ?: return
            // The header count must match the events actually written, including SessionEnded.
            // Stable sort keeps arrival order for events with equal timestamps.
            val ordered = eventLines.sortedBy { it.first }.map { it.second }
            // droppedEvents = queue overflow reported by the engine + events over the cap here.
            // cappedEventsCounted: events past the per-session cap. They are not in the file but are in the aggregates.
            val counted = rawHdr.copy(
                eventCount = ordered.size,
                droppedEvents = rawHdr.droppedEvents + capDropped,
                cappedEventsCounted = capDropped,
            )
            val hdr = if (redacted && !counted.redacted) counted.copy(redacted = true) else counted
            val suffix = if (redacted) "" else ".raw"
            val json = SessionFiles.write(filesDir, hdr, ordered, aggregates.snapshot(), suffix)
            wroteSession = true
            try { outputListener?.onSessionJson(hdr.sessionId, json) } catch (_: Throwable) {}
            Log.d("PlaybackProfiler", "FileSink: wrote session ${hdr.sessionId.take(8)} (${ordered.size} events)")
        } catch (e: Exception) {
            Log.w("PlaybackProfiler", "FileSink.onSessionEnded failed: ${e.message}")
        } finally {
            // The journal is only needed until the session is written. If writing failed, keep it so
            // the session can be recovered on the next start.
            closeJournal(keepFile = !wroteSession)
            // One file per playback session: the next session must start with an empty buffer.
            eventLines.clear()
            storedEvents = 0
            capDropped = 0
            aggregates.reset()
        }
    }

    override fun close() {
        sessionHeader = null
        // A session still running keeps its journal, so it can be recovered after a restart.
        closeJournal(keepFile = true)
        eventLines.clear()
        storedEvents = 0
        capDropped = 0
        aggregates.reset()
    }

    private fun appendJournal(line: String) {
        try {
            val writer = journal ?: openJournal().also { journal = it }
            writer.write(line)
            writer.newLine()
            // Flushed per event so a force-stop loses at most the event being written.
            writer.flush()
        } catch (e: Exception) {
            Log.w("PlaybackProfiler", "Journal write failed: ${e.javaClass.simpleName}")
        }
    }

    private fun openJournal(): BufferedWriter {
        filesDir.mkdirs()
        val file = File(filesDir, "${SessionJournal.PREFIX}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}${SessionJournal.SUFFIX}")
        journalFile = file
        return BufferedWriter(FileWriter(file, true))
    }

    private fun closeJournal(keepFile: Boolean) {
        try { journal?.close() } catch (_: Exception) {}
        if (!keepFile) journalFile?.delete()
        journal = null
        journalFile = null
    }
}

/**
 * Writes one session as the `.ndjson` (header first) and final `.json` files. Shared by [FileSink] and
 * [SessionJournal] so a recovered session has exactly the same layout as a normal one.
 */
@UnstableApi
internal object SessionFiles {

    /** Writes both files and returns the final session JSON. */
    fun write(
        filesDir: File,
        hdr: SessionHeader,
        ordered: List<String>,
        aggregates: Aggregates,
        suffix: String,
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(hdr.startedAtEpochMs))
        val baseName = "session_${stamp}_${hdr.sessionId.take(8)}"

        // Write NDJSON file (first line = header)
        val ndjsonFile = File(filesDir, "$baseName$suffix.ndjson")
        ndjsonFile.parentFile?.mkdirs()
        writeAtomically(ndjsonFile) { tmp ->
            tmp.bufferedWriter().use { out ->
                out.write(EventJsonWriter.headerToJson(hdr))
                out.newLine()
                for (line in ordered) {
                    out.write(line)
                    out.newLine()
                }
            }
        }

        // Write final session.json
        val json = StringBuilder()
        json.append("{")
        json.append("\"schemaVersion\":3,")
        json.append("\"session\":")
        json.append(EventJsonWriter.headerToJson(hdr))
        json.append(",\"events\":[")
        json.append(ordered.joinToString(","))
        json.append("],\"aggregates\":")
        json.append(EventJsonWriter.aggregatesToJson(aggregates))
        // Compute hash over everything except the hash field
        val payloadWithoutHash = json.toString()
        val hash = sha256First12(payloadWithoutHash)
        json.append(",\"hash\":\"")
        json.append(hash)
        json.append("\"}")

        val finalFile = File(filesDir, "$baseName$suffix.json")
        writeAtomically(finalFile) { tmp -> tmp.writeText(json.toString()) }
        return json.toString()
    }

    /**
     * Writes [target] through a temporary file and renames it into place, so a crash or a full
     * disk never leaves a truncated session file under the final name. The temporary file is
     * removed on failure and the exception is rethrown to the caller.
     */
    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            write(tmp)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed for ${target.name}")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun sha256First12(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }.substring(0, 12)
        } catch (_: Exception) {
            ""
        }
    }
}
