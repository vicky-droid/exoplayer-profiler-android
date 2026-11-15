package com.exoplayerprofiler

/**
 * Receives profiler output as JSON text. Register with [ExoPlayerProfiler.addSink].
 *
 * Both methods are called on the profiler's writer thread. Do not block in them; hand slow work
 * (for example network upload) to your own executor. Exceptions thrown here are caught and ignored.
 */
public interface ProfilerSink {
    /** Called for each event with its JSON object text (the same text written into the file). */
    public fun onEvent(eventJson: String)

    /** Called once when a session ends, with the full session JSON (same content as the .json file). */
    public fun onSessionEnded(sessionId: String, sessionJson: String)
}
