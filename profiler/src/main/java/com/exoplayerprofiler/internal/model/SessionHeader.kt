package com.exoplayerprofiler.internal.model

/**
 * Header written at the start of every schema v3 session.json.
 */
internal data class SessionHeader(
    val schemaVersion: Int = 3,
    val sessionId: String,
    val packageName: String,
    val startedAtEpochMs: Long,
    val exportedAtEpochMs: Long,
    val durationMs: Long,
    val eventCount: Int,
    val droppedEvents: Int,
    val redacted: Boolean,
    val device: DeviceInfo,
    val media3Version: String,
    val sdkVersion: String?,
    val config: ConfigSnapshot,
    val fileErrors: Int = 0,
    val attributes: Map<String, String> = emptyMap(),
    /** True when the session was rebuilt from its journal after the app stopped without ending it. */
    val recovered: Boolean = false,
)

/** Device information captured at session start. */
internal data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val android: String?,
    val apiLevel: Int?,
)

/** Configuration snapshot (never URLs or headers). */
internal data class ConfigSnapshot(
    val captureLevel: String,
    val maxEventsPerSession: Int,
)

/**
 * Aggregates computed over the session. Written at session end.
 */
internal data class Aggregates(
    val categoryCounts: Map<String, Int>,
    val errorCount: Int,
    val chunkCount: Int,
    val switchCount: Int,
    val drmCount: Int,
    val rebufferCount: Int,
    val rebufferTotalMs: Long,
    val droppedFrames: Long,
    val audioUnderruns: Int,
)
