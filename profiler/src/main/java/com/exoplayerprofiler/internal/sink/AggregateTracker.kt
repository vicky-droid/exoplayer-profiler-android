package com.exoplayerprofiler.internal.sink

import androidx.media3.common.Player
import com.exoplayerprofiler.internal.model.Aggregates
import org.json.JSONObject

/**
 * Totals for one session. Used live by [FileSink] as events arrive, and by [SessionJournal] when it
 * rebuilds a session from its journal, so both produce the same aggregates.
 *
 * The totals do not depend on arrival order. Some events are produced off the player thread, so they can
 * arrive after later ones. Rebuffers are therefore derived from timestamps, not from the order of calls:
 * a rebuffer is buffering that starts after the first frame, and it runs until the next non-buffering
 * state (or the last event, when the session ends while buffering).
 */
internal class AggregateTracker {

    private val categoryCounts = HashMap<String, Int>()
    private var chunkCount = 0
    private var errorCount = 0
    private var switchCount = 0
    private var drmCount = 0
    private var droppedFrames = 0L
    private var audioUnderruns = 0
    /** Earliest first frame, or -1 when none has been seen. */
    private var firstFrameMs = -1L
    /** Latest timestamp of any event. */
    private var lastTMs = 0L
    /** Every buffering state change, as (tMs, buffering). Sorted by time when the totals are computed. */
    private val bufferingChanges = ArrayList<Pair<Long, Boolean>>()

    /** Adds one event. [json] is the event's JSON, used for the fields the totals need. */
    fun track(category: String, eventName: String, json: String) {
        categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val tMs = obj.optLong("tMs", lastTMs)
        if (tMs > lastTMs) lastTMs = tMs
        val details = obj.optJSONObject("details")
        when (category) {
            "CHUNK" -> chunkCount++
            "ERROR" -> errorCount++
            "DRM" -> drmCount++
            "TRACKS" -> if (eventName == "Switch") switchCount++
            "AUDIO" -> if (eventName == "Underrun") audioUnderruns++
            "VIDEO" -> when (eventName) {
                "RenderedFirstFrame" -> if (firstFrameMs < 0L || tMs < firstFrameMs) firstFrameMs = tMs
                "DroppedFrames" -> droppedFrames += (details?.optInt("droppedFrames", 0) ?: 0).toLong()
            }
            "PLAYBACK" -> if (eventName == "StateChanged") {
                bufferingChanges += tMs to (details?.optInt("state", -1) == STATE_BUFFERING)
            }
        }
    }

    /** Totals for the session. Pure: calling it again gives the same result. */
    fun snapshot(): Aggregates {
        var rebufferCount = 0
        var rebufferTotalMs = 0L
        var openSince = -1L
        // Stable sort: changes at the same time keep arrival order.
        for ((tMs, buffering) in bufferingChanges.sortedBy { it.first }) {
            if (buffering) {
                // Buffering after the first frame is a rebuffer. Startup buffering is VST, not a rebuffer.
                if (openSince < 0L && firstFrameMs >= 0L && tMs >= firstFrameMs) {
                    openSince = tMs
                    rebufferCount++
                }
            } else if (openSince >= 0L) {
                rebufferTotalMs += tMs - openSince
                openSince = -1L
            }
        }
        if (openSince >= 0L) rebufferTotalMs += lastTMs - openSince
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
        droppedFrames = 0L
        audioUnderruns = 0
        firstFrameMs = -1L
        lastTMs = 0L
        bufferingChanges.clear()
    }

    private companion object {
        /** Media3 Player.STATE_BUFFERING, from the library itself so it cannot drift. */
        const val STATE_BUFFERING = Player.STATE_BUFFERING
    }
}
