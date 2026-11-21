package com.exoplayerprofiler

/** Which events are recorded. */
public enum class CaptureLevel {
    /** Every event. */
    FULL,

    /**
     * Stops recording after the first rendered frame. Session boundaries, playback errors, DRM and network
     * events are still recorded.
     */
    STARTUP_ONLY,

    /** Only session boundaries, playback errors, DRM and network events. */
    ERROR,
}

/** Settings for [ExoPlayerProfiler.install]. Defaults are safe for production use. */
public data class ProfilerConfig(
    /** Turns recording on or off. When false every call is a no-op. */
    val enabled: Boolean = true,
    val captureLevel: CaptureLevel = CaptureLevel.FULL,
    /**
     * Most events kept in one session file. Events beyond the limit are not written and are
     * counted in the header's `droppedEvents`. Aggregates still count every event.
     */
    val maxEventsPerSession: Int = 10_000,
    /** How many session files to keep in the profiler directory. */
    val retentionSessions: Int = 20,
    /** Total size budget of the profiler directory, in bytes. */
    val retentionBytes: Long = 50L * 1024 * 1024,
    /** Longest payload mirrored to logcat per event, in characters. Files are not truncated. */
    val maxEventPayloadChars: Int = 4096,
    /**
     * Also mirror events to logcat (tag `PlaybackProfiler`) and keep raw, unredacted
     * chunk URIs. Only effective in debuggable builds.
     */
    val captureRawLocal: Boolean = false,
)
