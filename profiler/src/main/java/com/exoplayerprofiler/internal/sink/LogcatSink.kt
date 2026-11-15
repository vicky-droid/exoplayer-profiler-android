package com.exoplayerprofiler.internal.sink

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.exoplayerprofiler.internal.json.EventJsonWriter
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader

/**
 * Writes events to logcat in the legacy line format so the existing
 * logcat-tailing bridge keeps working:
 *
 *   "<ms> ms [Category] Event {json}"
 *
 * Payloads over [maxChunkChars] are split into __profilerChunk* envelopes that
 * the bridge reassembles.
 *
 * Only active when the app is debuggable and captureRawLocal is enabled.
 */
@UnstableApi
internal class LogcatSink(
    private val tag: String = "PlaybackProfiler",
    private val maxChunkChars: Int = 3000,
    private val enabled: Boolean = false
) : EventSink {

    override fun onEvent(event: SchemaV3Event) {
        if (!enabled) return
        val json = EventJsonWriter.eventToJson(event, includeRaw = true)  // only created for debuggable raw capture
        val prefix = "${event.tMs} ms [${event.category}] ${event.event} "
        if (json.length <= maxChunkChars) {
            Log.d(tag, prefix + json)
        } else {
            // Split large payloads so logcat doesn't truncate them.
            val chunks = (json.length + maxChunkChars - 1) / maxChunkChars
            val chunkId = "${event.tMs}-${event.category}-${event.event}"
            for (i in 0 until chunks) {
                val start = i * maxChunkChars
                val end = minOf(start + maxChunkChars, json.length)
                val chunk = org.json.JSONObject()
                    .put("__profilerChunkId", chunkId)
                    .put("__profilerChunk", i)
                    .put("__profilerChunks", chunks)
                    .put("data", json.substring(start, end))
                Log.d(tag, prefix + chunk)
            }
        }
    }

    override fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?) {}

    override fun close() {}
}
