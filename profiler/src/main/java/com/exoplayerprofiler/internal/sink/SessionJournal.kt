package com.exoplayerprofiler.internal.sink

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.exoplayerprofiler.internal.model.ConfigSnapshot
import com.exoplayerprofiler.internal.model.DeviceInfo
import com.exoplayerprofiler.internal.model.SessionHeader
import java.io.File
import org.json.JSONObject

/** What the app knows at startup that a journal cannot record: used to fill in the recovered header. */
internal data class HeaderTemplate(
    val packageName: String,
    val media3Version: String,
    val androidVersion: String?,
    val apiLevel: Int?,
    val config: ConfigSnapshot,
    val redacted: Boolean,
)

/**
 * Recovers sessions that were never ended, for example because the app was force-stopped or the
 * process was killed. Each leftover `journal_*.journal` file becomes a normal session file, marked
 * `"recovered": true`, with the events that were journaled before the process stopped.
 *
 * Limits of a recovered session: `droppedEvents` is 0 (drops before the crash are not known), and
 * `attributes` are empty (they are not journaled). Its events and totals are otherwise the same as a
 * normal session. The Android version is taken from the current device, which is the device the app
 * ran on in practice.
 */
@UnstableApi
internal object SessionJournal {

    const val PREFIX = "journal_"
    const val SUFFIX = ".journal"

    /**
     * Turns every leftover journal in [profilerDir] into a session file. Returns how many were
     * recovered. Journals with no session start cannot be attributed and are deleted. Must run on the
     * writer thread, before the new session's events are written.
     */
    fun recoverAll(profilerDir: File, template: HeaderTemplate): Int {
        val journals = profilerDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?: return 0
        var recovered = 0
        for (journal in journals) {
            val ok = try {
                recover(journal, profilerDir, template)
            } catch (e: Exception) {
                Log.w("PlaybackProfiler", "Journal recovery failed: ${e.javaClass.simpleName}")
                false
            }
            if (ok) recovered++
            journal.delete()
        }
        if (recovered > 0) Log.i("PlaybackProfiler", "Recovered $recovered unfinished session(s)")
        return recovered
    }

    private fun recover(journal: File, profilerDir: File, template: HeaderTemplate): Boolean {
        // A line cut short by the crash is skipped, not fatal.
        val lines = journal.readLines().mapNotNull { raw ->
            if (raw.isBlank()) null else try { raw to JSONObject(raw) } catch (_: Exception) { null }
        }.sortedBy { it.second.optLong("tMs", 0L) }
        if (lines.isEmpty()) return false

        val started = lines.firstOrNull { (_, obj) ->
            obj.optString("category") == "SESSION" && obj.optString("event") == "SessionStarted"
        } ?: return false
        val startedObj = started.second
        val details = startedObj.optJSONObject("details")
        val sessionId = details?.optString("sessionId").orEmpty()
        if (sessionId.isBlank()) return false

        val tracker = AggregateTracker()
        for ((raw, obj) in lines) tracker.track(obj.optString("category"), obj.optString("event"), raw)

        val deviceParts = details?.optString("device").orEmpty().split(" ", limit = 2)
        val header = SessionHeader(
            sessionId = sessionId,
            packageName = template.packageName,
            startedAtEpochMs = startedObj.optLong("wallMs", 0L),
            exportedAtEpochMs = System.currentTimeMillis(),
            durationMs = lines.last().second.optLong("tMs", 0L),
            eventCount = lines.size,
            droppedEvents = 0,
            redacted = template.redacted,
            device = DeviceInfo(
                manufacturer = deviceParts.getOrElse(0) { "unknown" },
                model = deviceParts.getOrElse(1) { "unknown" },
                android = template.androidVersion,
                apiLevel = template.apiLevel,
            ),
            media3Version = template.media3Version,
            sdkVersion = null,
            config = template.config,
            fileErrors = 0,
            attributes = emptyMap(),
            recovered = true,
        )
        val suffix = if (template.redacted) "" else ".raw"
        SessionFiles.write(profilerDir, header, lines.map { it.first }, tracker.snapshot(), suffix)
        return true
    }
}
