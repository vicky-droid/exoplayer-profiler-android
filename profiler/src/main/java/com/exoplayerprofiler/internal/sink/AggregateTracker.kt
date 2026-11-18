package com.exoplayerprofiler.internal.sink

import androidx.media3.common.Player
import com.exoplayerprofiler.internal.model.Aggregates
import org.json.JSONObject

/**
 * Running totals for one session. Used live by [FileSink] as events arrive, and by [SessionJournal]
 * when it rebuilds a session from its journal, so both produce the same aggregates.
 */
internal class AggregateTracker {

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

    /** Adds one event. [json] is the event's JSON, used for the fields the totals need. */
    fun track(category: String, eventName: String, json: String) {
        categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val tMs = obj.optLong("tMs", lastTMs)
        lastTMs = tMs
        val details = obj.optJSONObject("details")
        when (category) {
            "CHUNK" -> chunkCount++
            "ERROR" -> errorCount++
            "DRM" -> drmCount++
            "TRACKS" -> if (eventName == "Switch") switchCount++
            "AUDIO" -> if (eventName == "Underrun") audioUnderruns++
            "VIDEO" -> when (eventName) {
                "RenderedFirstFrame" -> firstFrameSeen = true
                "DroppedFrames" -> droppedFrames += (details?.optInt("droppedFrames", 0) ?: 0).toLong()
            }
            "PLAYBACK" -> if (eventName == "StateChanged") {
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

    /** Totals for the session. A rebuffer still open at the end lasts until the last event. */
    fun snapshot(): Aggregates {
        if (bufferingSinceMs >= 0L) {
            rebufferTotalMs += lastTMs - bufferingSinceMs
            bufferingSinceMs = -1L
        }
        return Aggregates(
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
    }

    fun reset() {
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
        const val STATE_BUFFERING = Player.STATE_BUFFERING
    }
}
