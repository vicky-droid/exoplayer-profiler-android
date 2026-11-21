package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.Health

import androidx.media3.common.util.UnstableApi
import com.exoplayerprofiler.ProfilerSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bridges public [ProfilerSink]s to the internal pipeline. It owns the user sink list, so sinks
 * added or removed at any time (before or after the writer thread starts) take effect, because the
 * single adapter instance is what the writer thread's sink list refers to.
 * All calls arrive on the writer thread.
 */
@UnstableApi
internal class ProfilerSinkAdapter : FileSink.OutputListener {
    private val sinks = CopyOnWriteArrayList<ProfilerSink>()

    fun add(sink: ProfilerSink) { sinks.addIfAbsent(sink) }
    fun remove(sink: ProfilerSink) { sinks.remove(sink) }

    override fun onEventJson(eventJson: String) {
        for (s in sinks) try { s.onEvent(eventJson) } catch (e: Exception) { Health.record("sink.onEvent", e, Health.Kind.SINK) }
    }

    override fun onSessionJson(sessionId: String, sessionJson: String) {
        for (s in sinks) try { s.onSessionEnded(sessionId, sessionJson) } catch (e: Exception) { Health.record("sink.onSessionEnded", e, Health.Kind.SINK) }
    }
}
