package com.exoplayerprofiler.internal.sink

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.exoplayerprofiler.internal.json.EventJsonWriter
import com.exoplayerprofiler.internal.model.Aggregates
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.redact.Redactor
import java.io.File
import java.security.MessageDigest

/**
 * File sink for the playback profiler. Buffers events during the session and
 * writes a final redacted session.json at session end.
 *
 * Files per session (in appContext.filesDir/playback_profiler/):
 *   - session_<yyyyMMdd_HHmmss>_<id8>.ndjson  (append-only, first line = header)
 *   - session_<yyyyMMdd_HHmmss>_<id8>.json    (final, at session end)
 *
 * Both files are redacted. Raw variants only exist for debug builds.
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
    // Only the writer thread touches these two counters.
    private var storedEvents = 0
    private var capDropped = 0

    // Aggregates, computed from the events as they arrive.
    private val categoryCounts = HashMap<String, Int>()
    private var chunkCount = 0
    private var errorCount = 0
    private var switchCount = 0
    private var drmCount = 0
    private var rebufferCount = 0
    private var rebufferTotalMs = 0L
    private var droppedFrames = 0L
    private var audioUnderruns = 0
    private var firstFrameSeen = false
    private var bufferingSinceMs = -1L
    private var lastTMs = 0L

    override fun onEvent(event: SchemaV3Event) {
        try {
            val json = EventJsonWriter.eventToJson(event)
            // Apply token redaction to ensure no raw token leaks into file sinks
            val lineToWrite = if (redacted) Redactor.redactTokens(json) else json
            if (storedEvents < maxEvents) {
                eventLines.add(event.tMs to lineToWrite)
                storedEvents++
            } else {
                // Over the per-session cap: not written to the file, counted in the header.
                capDropped++
            }
            try { outputListener?.onEventJson(lineToWrite) } catch (_: Throwable) {}
            trackAggregates(event, json)
        } catch (e: Exception) {
            Log.w("PlaybackProfiler", "FileSink.onEvent failed: ${e.message}")
        }
    }

    private fun trackAggregates(event: SchemaV3Event, json: String) {
        categoryCounts[event.category] = (categoryCounts[event.category] ?: 0) + 1
        val obj = try { org.json.JSONObject(json) } catch (_: Exception) { return }
        val tMs = obj.optLong("tMs", lastTMs)
        lastTMs = tMs
        val details = obj.optJSONObject("details")
        when (event.category) {
            "CHUNK" -> chunkCount++
            "ERROR" -> errorCount++
            "DRM" -> drmCount++
            "TRACKS" -> if (event.event == "Switch") switchCount++
            "AUDIO" -> if (event.event == "Underrun") audioUnderruns++
            "VIDEO" -> when (event.event) {
                "RenderedFirstFrame" -> firstFrameSeen = true
                "DroppedFrames" -> droppedFrames += (details?.optInt("droppedFrames", 0) ?: 0).toLong()
            }
            "PLAYBACK" -> if (event.event == "StateChanged") {
                val buffering = details?.optInt("state", -1) == STATE_BUFFERING
                if (buffering && firstFrameSeen && bufferingSinceMs < 0L) {
                    // Buffering after the first frame is a rebuffer. Startup buffering is VST, not a rebuffer.
                    bufferingSinceMs = tMs
                    rebufferCount++
                } else if (!buffering && bufferingSinceMs >= 0L) {
                    rebufferTotalMs += tMs - bufferingSinceMs
                    bufferingSinceMs = -1L
                }
            }
        }
    }

    override fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?) {
        sessionHeader = header
        try {
            val rawHdr = header ?: return
            // The header count must match the events actually written, including SessionEnded.
            // Stable sort keeps arrival order for events with equal timestamps.
            val ordered = eventLines.sortedBy { it.first }.map { it.second }
            // droppedEvents = queue overflow reported by the engine + events over the cap here.
            val counted = rawHdr.copy(eventCount = ordered.size, droppedEvents = rawHdr.droppedEvents + capDropped)
            val hdr = if (redacted && !counted.redacted) counted.copy(redacted = true) else counted

            // A rebuffer still open at session end lasts until the last event.
            if (bufferingSinceMs >= 0L) {
                rebufferTotalMs += lastTMs - bufferingSinceMs
                bufferingSinceMs = -1L
            }

            val aggregates = Aggregates(
                categoryCounts = categoryCounts.toMap(),
                errorCount = errorCount,
                chunkCount = chunkCount,
                switchCount = switchCount,
                drmCount = drmCount,
                rebufferCount = rebufferCount,
                rebufferTotalMs = rebufferTotalMs,
                droppedFrames = droppedFrames,
                audioUnderruns = audioUnderruns,
            )

            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date(hdr.startedAtEpochMs))
            val baseName = "session_${stamp}_${hdr.sessionId.take(8)}"
            val suffix = if (redacted) "" else ".raw"

            // Write NDJSON file (append-only, first line = header)
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
            try { outputListener?.onSessionJson(hdr.sessionId, json.toString()) } catch (_: Throwable) {}
            Log.d("PlaybackProfiler", "FileSink: wrote ${finalFile.name} (${eventLines.size} events)")
        } catch (e: Exception) {
            Log.w("PlaybackProfiler", "FileSink.onSessionEnded failed: ${e.message}")
        } finally {
            // One file per playback session: the next session must start with an empty buffer.
            eventLines.clear()
            resetAggregates()
        }
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

    override fun close() {
        sessionHeader = null
        eventLines.clear()
        resetAggregates()
    }

    private fun resetAggregates() {
        storedEvents = 0
        capDropped = 0
        categoryCounts.clear()
        chunkCount = 0
        errorCount = 0
        switchCount = 0
        drmCount = 0
        rebufferCount = 0
        rebufferTotalMs = 0L
        droppedFrames = 0L
        audioUnderruns = 0
        firstFrameSeen = false
        bufferingSinceMs = -1L
        lastTMs = 0L
    }

    private companion object {
        /** Media3 Player.STATE_BUFFERING, from the library itself so it cannot drift. */
        const val STATE_BUFFERING = androidx.media3.common.Player.STATE_BUFFERING
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
