package com.exoplayerprofiler.internal.model

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject

/**
 * Typed profiler event for schema v3. Every event carries session time
 * (tMs), wall time (wallMs), thread and contentKind.
 */
internal sealed interface SchemaV3Event {
    val tMs: Long
    val wallMs: Long
    val thread: String
    val category: String
    val event: String
    val contentKind: String
}

/** Session lifecycle. */
internal data class SessionStartedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val device: String,
    val sessionId: String = "",
) : SchemaV3Event {
    override val category = "SESSION"
    override val event = "SessionStarted"
}

internal data class SessionEndedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val sessionId: String,
    val eventCount: Int,
) : SchemaV3Event {
    override val category = "SESSION"
    override val event = "SessionEnded"
}

/** Startup markers. */
internal data class PlaybackClickV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
) : SchemaV3Event {
    override val category = "STARTUP"
    override val event = "play_click"
}

/** Network/load events. */
internal data class LoadStartedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val uriRedacted: String,
    val uriHash: String,
    val dataType: String,
) : SchemaV3Event {
    override val category = "NETWORK"
    override val event = "LoadStarted"
}

internal data class LoadCompletedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val uriRedacted: String,
    val uriHash: String,
    val bytes: String,
    val dataType: String,
) : SchemaV3Event {
    override val category = "NETWORK"
    override val event = "LoadCompleted"
}

/** Track list. */
@UnstableApi
internal data class TracksChangedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val tracks: List<TrackInfoV3>,
    /** Language of the selected text track, or null when no text track is selected. */
    val selectedTextLanguage: String? = null,
) : SchemaV3Event {
    override val category = "TRACKS"
    override val event = "Changed"
}

@UnstableApi
internal data class TrackInfoV3(
    val trackType: Int,
    val trackId: String?,
    val id: Any?,
    val mimeType: Any?,
    val containerMimeType: Any?,
    val codecs: Any?,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val language: Any?,
    val frameRate: Float,
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncoding: Int,
    val rotationDegrees: Int,
    val stereoMode: Int,
    val roleFlags: Int,
    val selected: Boolean,
    val supported: Boolean,
    val color: ColorDescription?,
)

/** Format snapshots. */
@UnstableApi
internal data class VideoInputFormatV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val format: FormatInfo?,
) : SchemaV3Event {
    override val category = "VIDEO"
    override val event = "InputFormat"
}

@UnstableApi
internal data class AudioInputFormatV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val format: FormatInfo?,
) : SchemaV3Event {
    override val category = "AUDIO"
    override val event = "InputFormat"
}

/** First frame. */
internal data class RenderedFirstFrameV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val presentationTimeUs: Long,
    /** True only for the first frame of the session; false for frames after a seek. */
    val isFirstOfSession: Boolean = true,
) : SchemaV3Event {
    override val category = "VIDEO"
    override val event = "RenderedFirstFrame"
}

/** DRM events. */
internal data class DrmSessionAcquiredV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val state: Int,
) : SchemaV3Event {
    override val category = "DRM"
    override val event = "SessionAcquired"
}

internal data class DrmKeysLoadedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val schemeDataCount: Int,
) : SchemaV3Event {
    override val category = "DRM"
    override val event = "KeysLoaded"
}

/** VST summary. */
internal data class VstTotalV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val vstMs: Long,
    val authMs: Long,
    val manifestMs: Long,
    val drmMs: Long,
    val firstChunkMs: Long,
    val initChunksMs: Long,
    val chunksDownloadedBeforeFirstFrame: Int,
    val chunksToPlay: Int,
) : SchemaV3Event {
    override val category = "VST"
    override val event = "total"
}

/** Custom / startup observation. */
internal data class CustomEventV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val name: String,
    val details: JSONObject?,
) : SchemaV3Event {
    override val category = "CUSTOM"
    override val event = name
}

internal data class StartupObservationV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val observation: String,
) : SchemaV3Event {
    override val category = "STARTUP"
    override val event = "observation"
}

/** Content kind values. */
internal object ContentKind {
    const val CONTENT = "CONTENT"
    const val AD = "AD"
    const val UNKNOWN = "UNKNOWN"
}

/** Session attached marker. */
internal data class SessionAttachedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val url: String,
) : SchemaV3Event {
    override val category = "SESSION"
    override val event = "PlayerAttached"
}

/** Playback state changes. */
internal data class PlaybackStateChangedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val state: Int,
) : SchemaV3Event {
    override val category = "PLAYBACK"
    override val event = "StateChanged"
}

/** Is playing changes. */
internal data class IsPlayingChangedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val isPlaying: Boolean,
) : SchemaV3Event {
    override val category = "PLAYBACK"
    override val event = "IsPlayingChanged"
}

/** Play when ready changes. */
internal data class PlayWhenReadyChangedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val playWhenReady: Boolean,
) : SchemaV3Event {
    override val category = "PLAYBACK"
    override val event = "PlayWhenReadyChanged"
}

/** Loading / buffering state changes. */
internal data class LoadingChangedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val isLoading: Boolean,
) : SchemaV3Event {
    override val category = "BUFFER"
    override val event = "LoadingChanged"
}

/** Track switch events. */
internal data class TrackSwitchV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val trackType: Int,
    val fromFormatId: String?,
    val toFormatId: String?,
    val reason: Int,
    val bufferMs: Long,
) : SchemaV3Event {
    override val category = "TRACKS"
    override val event = "Switch"
}

/** Player errors. */
internal data class PlayerErrorV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val errorCode: Int,
    val errorCodeName: String?,
    val errorMessage: String?,
) : SchemaV3Event {
    override val category = "ERROR"
    override val event = "PlayerError"
}

/** Seek started. */
internal data class SeekStartedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val positionMs: Long,
) : SchemaV3Event {
    override val category = "SEEK"
    override val event = "SeekStarted"
}

/** Position discontinuity. */
internal data class PositionDiscontinuityV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val reason: Int,
) : SchemaV3Event {
    override val category = "SEEK"
    override val event = "PositionDiscontinuity"
}

/** Video decoder initialized. */
internal data class VideoDecoderInitializedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val decoderName: String?,
    val initMs: Long,
    val hardware: Boolean,
    val secure: Boolean,
) : SchemaV3Event {
    override val category = "DECODER"
    override val event = "Initialized"
}

/** Audio decoder initialized. */
internal data class AudioDecoderInitializedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val decoderName: String?,
    val initMs: Long,
    val hardware: Boolean,
    val secure: Boolean,
) : SchemaV3Event {
    override val category = "DECODER"
    override val event = "Initialized"
}

/** Dropped video frames. */
internal data class DroppedFramesV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val droppedFrames: Int,
    val elapsedMs: Long,
) : SchemaV3Event {
    override val category = "VIDEO"
    override val event = "DroppedFrames"
}

/** Audio underrun. */
internal data class AudioUnderrunV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val bufferSizeMs: Long,
) : SchemaV3Event {
    override val category = "AUDIO"
    override val event = "Underrun"
}

/** Bandwidth estimate. */
internal data class BandwidthEstimateV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val bitrateBps: Long,
) : SchemaV3Event {
    override val category = "BANDWIDTH"
    override val event = "Estimate"
}

/** Device capabilities, captured once per session. */
internal data class DeviceCapabilitiesV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val deviceCapabilities: Map<String, Any>,
) : SchemaV3Event {
    override val category = "DEVICE"
    override val event = "Capabilities"
}

/** Ad snapshot captured from Timeline.Period.adPlaybackState. */
internal data class AdSnapshotV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val adGroupCount: Int,
    val adCount: Int,
    val adPositions: List<Int>,
    val adStates: List<String>,
) : SchemaV3Event {
    override val category = "ADS"
    override val event = "Snapshot"
}

/** Chunk load record, emitted when a chunk load completes. */
internal data class ChunkLoadedV3(
    override val tMs: Long,
    override val wallMs: Long,
    override val thread: String,
    override val contentKind: String = ContentKind.UNKNOWN,
    val chunkId: Int,
    val dataType: String,
    val trackType: String,
    val trackFormatId: String?,
    val uriRedacted: String,
    val uriHash: String,
    val byteStart: Long,
    val byteLength: Long,
    val mediaStartMs: Long,
    val mediaEndMs: Long,
    val durationMs: Long,
    val requestStartMs: Long,
    val loadDurationMs: Long,
    val bytesLoaded: Long,
    val throughputKbps: Long,
    val httpStatus: Int,
    val cacheHint: String?,
    val outcome: String,
    val errorMessage: String?,
    val gapBeforeMs: Long?,
    /** Full URI. Set only when raw capture is allowed (debuggable build); never written to redacted sinks. */
    val uriRaw: String? = null,
) : SchemaV3Event {
    override val category = "CHUNK"
    override val event = "Loaded"
}
