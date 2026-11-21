package com.exoplayerprofiler

import com.exoplayerprofiler.internal.Health

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.exoplayerprofiler.internal.PlaybackProfilerEngine

/**
 * Records playback sessions from an ExoPlayer instance as one JSON file per session
 * in `filesDir/playback_profiler/`. Each file holds a header, a time-ordered list of
 * events (startup, network chunks, ABR switches, rebuffers, drops, seeks, ad markers)
 * and aggregates. Nothing leaves the device.
 *
 * Typical use:
 * ```
 * ExoPlayerProfiler.install(context, ProfilerConfig(enabled = BuildConfig.DEBUG))
 * ExoPlayerProfiler.attach(player)   // when the player is created
 * ExoPlayerProfiler.detach()         // when the player is released
 * ```
 *
 * Ad breaks are not visible to ExoPlayer when the ads run in a separate player (client-side
 * ad insertion). Call [markAdBreakStart] and [markAdBreakEnd] from the ads integration so
 * events inside an ad break are tagged `contentKind = AD`.
 */
@UnstableApi
public object ExoPlayerProfiler {

    // The engine keeps only the application context (see install), never an Activity, so this
    // static reference cannot leak one.
    @SuppressLint("StaticFieldLeak")
    private val engine = PlaybackProfilerEngine()

    /**
     * Every public method is fail-safe: an internal error is swallowed so it never reaches the
     * host app's playback path.
     */
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) { Health.record("facade", e) }
    }

    /** Sets the configuration. Call once, before [attach]. Safe to call again to change config. */
    public fun install(context: Context, config: ProfilerConfig = ProfilerConfig()): Unit = guarded {
        engine.init(context.applicationContext, config)
    }

    /** Starts recording the session for [player]. Replaces any player attached earlier. */
    public fun attach(player: ExoPlayer): Unit = guarded {
        engine.attach(player)
    }

    /** Ends the current session and stops listening to the attached player. */
    public fun detach(): Unit = guarded {
        engine.detach()
    }

    /** Marks the start of an ad break that ExoPlayer does not play itself. */
    public fun markAdBreakStart(): Unit = guarded {
        engine.setAdBreakActive(true)
    }

    /** Marks the end of the ad break started with [markAdBreakStart]. */
    public fun markAdBreakEnd(): Unit = guarded {
        engine.setAdBreakActive(false)
    }

    /** Adds an application event (for example a button press) to the current session. */
    public fun reportCustomEvent(name: String, data: Map<String, Any>? = null): Unit = guarded {
        engine.reportCustomEvent(name, data)
    }

    /**
     * Sets a string attribute written to the session header under `attributes`. A null [value]
     * removes the key. Attributes set before [attach] apply to the next session and are kept
     * across sessions until removed.
     */
    public fun setSessionAttribute(key: String, value: String?) {
        try { engine.setSessionAttribute(key, value) } catch (e: Exception) { Health.record("facade", e) }
    }

    /** Records the time the user pressed play. It is the start of the VST measurement. */
    public fun markPlayClick() {
        try { engine.reportPlayClick() } catch (e: Exception) { Health.record("facade", e) }
    }

    /** Marks the start of authentication, for the VST breakdown. */
    public fun markAuthStart() {
        try { engine.reportAuthStart() } catch (e: Exception) { Health.record("facade", e) }
    }

    /** Marks the end of authentication, for the VST breakdown. [durationMs] is the time it took. */
    public fun markAuthEnd(durationMs: Long) {
        try { engine.reportAuthEnd(durationMs) } catch (e: Exception) { Health.record("facade", e) }
    }

    /** Registers a sink. It is notified on the writer thread, also when added after playback began. */
    public fun addSink(sink: ProfilerSink) {
        try { engine.addSink(sink) } catch (e: Exception) { Health.record("facade", e) }
    }

    /** Stops delivery to a sink added with [addSink]. */
    public fun removeSink(sink: ProfilerSink) {
        try { engine.removeSink(sink) } catch (e: Exception) { Health.record("facade", e) }
    }

    /**
     * Ends the current session, if one is running (its file is written), and starts a new one.
     * Use this only when no [markPlaybackRequest] call starts the session: a playback request
     * already starts a new session, so calling both writes an empty session file.
     * Does nothing when the profiler is disabled.
     */
    public fun beginSession() {
        try { engine.startNewSession() } catch (e: Exception) { Health.record("facade", e) }
    }

    /**
     * Starts a new session for a playback request. Call it before [attach] and before
     * [markPlayClick], so the play click counts toward VST. [contentId] is hashed and sensitive
     * keys in [details] are dropped inside the library; pass raw values.
     */
    public fun markPlaybackRequest(contentId: String?, details: Map<String, Any?>? = null) {
        try {
            engine.reportPlaybackRequest(contentId, details?.filterValues { it != null }?.mapValues { it.value!! })
        } catch (e: Exception) { Health.record("facade", e) }
    }
}
