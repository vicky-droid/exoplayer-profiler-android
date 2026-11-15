package com.exoplayerprofiler.internal.json

import androidx.media3.common.util.UnstableApi
import com.exoplayerprofiler.internal.model.AdSnapshotV3
import com.exoplayerprofiler.internal.model.Aggregates
import com.exoplayerprofiler.internal.model.AudioDecoderInitializedV3
import com.exoplayerprofiler.internal.model.AudioInputFormatV3
import com.exoplayerprofiler.internal.model.AudioUnderrunV3
import com.exoplayerprofiler.internal.model.BandwidthEstimateV3
import com.exoplayerprofiler.internal.model.ChunkLoadedV3
import com.exoplayerprofiler.internal.model.DeviceCapabilitiesV3
import com.exoplayerprofiler.internal.model.ColorDescription
import com.exoplayerprofiler.internal.model.CustomEventV3
import com.exoplayerprofiler.internal.model.DroppedFramesV3
import com.exoplayerprofiler.internal.model.DrmKeysLoadedV3
import com.exoplayerprofiler.internal.model.DrmSessionAcquiredV3
import com.exoplayerprofiler.internal.model.FormatInfo
import com.exoplayerprofiler.internal.model.IsPlayingChangedV3
import com.exoplayerprofiler.internal.model.LoadCompletedV3
import com.exoplayerprofiler.internal.model.LoadStartedV3
import com.exoplayerprofiler.internal.model.LoadingChangedV3
import com.exoplayerprofiler.internal.model.PlaybackClickV3
import com.exoplayerprofiler.internal.model.PlaybackStateChangedV3
import com.exoplayerprofiler.internal.model.PlayWhenReadyChangedV3
import com.exoplayerprofiler.internal.model.PositionDiscontinuityV3
import com.exoplayerprofiler.internal.model.PlayerErrorV3
import com.exoplayerprofiler.internal.model.RenderedFirstFrameV3
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SeekStartedV3
import com.exoplayerprofiler.internal.model.SessionAttachedV3
import com.exoplayerprofiler.internal.model.SessionEndedV3
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.model.SessionStartedV3
import com.exoplayerprofiler.internal.model.StartupObservationV3
import com.exoplayerprofiler.internal.model.TrackInfoV3
import com.exoplayerprofiler.internal.model.TrackSwitchV3
import com.exoplayerprofiler.internal.model.TracksChangedV3
import com.exoplayerprofiler.internal.model.VideoDecoderInitializedV3
import com.exoplayerprofiler.internal.model.VideoInputFormatV3
import com.exoplayerprofiler.internal.model.VstTotalV3

/**
 * Deterministic JSON writer for schema v3 profiler events.
 *
 * Builds JSON strings manually with stable field order and correct escaping.
 * No external dependencies.
 */
@UnstableApi
internal object EventJsonWriter {

    private fun esc(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder()
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Serialises any [SchemaV3Event] to a JSON string.
     */
    fun eventToJson(event: SchemaV3Event, includeRaw: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"tMs\":").append(event.tMs).append(",")
        sb.append("\"wallMs\":").append(event.wallMs).append(",")
        sb.append("\"thread\":").append(esc(event.thread)).append(",")
        sb.append("\"category\":").append(esc(event.category)).append(",")
        sb.append("\"event\":").append(esc(event.event)).append(",")
        sb.append("\"contentKind\":").append(esc(event.contentKind)).append(",")
        sb.append("\"kind\":").append(esc(event.contentKind))
        writeEventDetails(sb, event, includeRaw)
        sb.append("}")
        return sb.toString()
    }

    /**
     * Serialises a [SessionHeader] to a JSON string.
     */
    fun headerToJson(header: SessionHeader): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"schemaVersion\":").append(header.schemaVersion).append(",")
        sb.append("\"id\":").append(esc(header.sessionId)).append(",")
        sb.append("\"sessionId\":").append(esc(header.sessionId)).append(",")
        sb.append("\"packageName\":").append(esc(header.packageName)).append(",")
        sb.append("\"startedAtEpochMs\":").append(header.startedAtEpochMs).append(",")
        sb.append("\"exportedAtEpochMs\":").append(header.exportedAtEpochMs).append(",")
        sb.append("\"durationMs\":").append(header.durationMs).append(",")
        sb.append("\"eventCount\":").append(header.eventCount).append(",")
        sb.append("\"droppedEvents\":").append(header.droppedEvents).append(",")
        sb.append("\"redacted\":").append(header.redacted).append(",")
        sb.append("\"device\":{")
        sb.append("\"manufacturer\":").append(esc(header.device.manufacturer)).append(",")
        sb.append("\"model\":").append(esc(header.device.model)).append(",")
        header.device.android?.let { sb.append("\"android\":").append(esc(it)).append(",") }
        header.device.apiLevel?.let { sb.append("\"apiLevel\":").append(it).append(",") }
        // Remove trailing comma if any fields were added
        if (sb.last() == ',') sb.setLength(sb.length - 1)
        sb.append("},")
        sb.append("\"media3Version\":").append(esc(header.media3Version)).append(",")
        header.sdkVersion?.let { sb.append("\"sdkVersion\":").append(esc(it)).append(",") }
        sb.append("\"config\":{")
        sb.append("\"captureLevel\":").append(esc(header.config.captureLevel)).append(",")
        sb.append("\"maxEventsPerSession\":").append(header.config.maxEventsPerSession).append("},")
        sb.append("\"fileErrors\":").append(header.fileErrors).append(",")
        sb.append("\"attributes\":{")
        header.attributes.entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) sb.append(",")
            sb.append(esc(k)).append(":").append(esc(v))
        }
        sb.append("}}")
        return sb.toString()
    }

    /**
     * Serialises [Aggregates] to a JSON string.
     */
    fun aggregatesToJson(aggregates: Aggregates): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"categoryCounts\":{")
        val entries = aggregates.categoryCounts.entries.sortedBy { it.key }
        entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) sb.append(",")
            sb.append(esc(k)).append(":").append(v)
        }
        sb.append("},")
        sb.append("\"errorCount\":").append(aggregates.errorCount).append(",")
        sb.append("\"chunkCount\":").append(aggregates.chunkCount).append(",")
        sb.append("\"switchCount\":").append(aggregates.switchCount).append(",")
        sb.append("\"drmCount\":").append(aggregates.drmCount).append(",")
        sb.append("\"rebufferCount\":").append(aggregates.rebufferCount).append(",")
        sb.append("\"rebufferTotalMs\":").append(aggregates.rebufferTotalMs).append(",")
        sb.append("\"droppedFrames\":").append(aggregates.droppedFrames).append(",")
        sb.append("\"audioUnderruns\":").append(aggregates.audioUnderruns)
        sb.append("}")
        return sb.toString()
    }

    private fun writeEventDetails(sb: StringBuilder, event: SchemaV3Event, includeRaw: Boolean) {
        sb.append(",\"details\":")
        when (event) {
            is SessionStartedV3 -> {
                sb.append("{")
                sb.append("\"device\":").append(esc(event.device))
                if (event.sessionId.isNotEmpty()) {
                    sb.append(",\"sessionId\":").append(esc(event.sessionId))
                }
                sb.append("}")
            }
            is SessionEndedV3 -> {
                sb.append("{")
                sb.append("\"sessionId\":").append(esc(event.sessionId)).append(",")
                sb.append("\"eventCount\":").append(event.eventCount)
                sb.append("}")
            }
            is PlaybackClickV3 -> sb.append("{}")
            is LoadStartedV3 -> {
                sb.append("{")
                sb.append("\"uriRedacted\":").append(esc(event.uriRedacted)).append(",")
                sb.append("\"uriHash\":").append(esc(event.uriHash)).append(",")
                sb.append("\"dataType\":").append(esc(event.dataType))
                sb.append("}")
            }
            is LoadCompletedV3 -> {
                sb.append("{")
                sb.append("\"uriRedacted\":").append(esc(event.uriRedacted)).append(",")
                sb.append("\"uriHash\":").append(esc(event.uriHash)).append(",")
                sb.append("\"bytes\":").append(esc(event.bytes)).append(",")
                sb.append("\"dataType\":").append(esc(event.dataType))
                sb.append("}")
            }
            is TracksChangedV3 -> {
                sb.append("{")
                sb.append("\"tracks\":[")
                event.tracks.forEachIndexed { idx, track ->
                    if (idx > 0) sb.append(",")
                    sb.append("{")
                    sb.append("\"trackType\":").append(track.trackType).append(",")
                    sb.append("\"trackId\":").append(esc(track.trackId)).append(",")
                    sb.append("\"id\":").append(esc(track.id?.toString())).append(",")
                    sb.append("\"mimeType\":").append(esc(track.mimeType?.toString())).append(",")
                    sb.append("\"containerMimeType\":").append(esc(track.containerMimeType?.toString())).append(",")
                    sb.append("\"codecs\":").append(esc(track.codecs?.toString())).append(",")
                    sb.append("\"bitrate\":").append(track.bitrate).append(",")
                    sb.append("\"width\":").append(track.width).append(",")
                    sb.append("\"height\":").append(track.height).append(",")
                    sb.append("\"language\":").append(esc(track.language?.toString())).append(",")
                    sb.append("\"frameRate\":").append(track.frameRate).append(",")
                    sb.append("\"sampleRate\":").append(track.sampleRate).append(",")
                    sb.append("\"channelCount\":").append(track.channelCount).append(",")
                    sb.append("\"pcmEncoding\":").append(track.pcmEncoding).append(",")
                    sb.append("\"rotationDegrees\":").append(track.rotationDegrees).append(",")
                    sb.append("\"stereoMode\":").append(track.stereoMode).append(",")
                    sb.append("\"roleFlags\":").append(track.roleFlags).append(",")
                    sb.append("\"selected\":").append(track.selected).append(",")
                    sb.append("\"supported\":").append(track.supported)
                    track.color?.let { color ->
                        sb.append(",\"colorInfo\":{")
                        sb.append("\"colorSpace\":").append(esc(color.space)).append(",")
                        sb.append("\"colorRange\":").append(esc(color.range)).append(",")
                        sb.append("\"colorTransfer\":").append(esc(color.transfer)).append(",")
                        sb.append("\"hdrType\":").append(esc(color.hdrType)).append(",")
                        sb.append("\"lumaBitdepth\":").append(color.lumaBitDepth).append(",")
                        sb.append("\"chromaBitdepth\":").append(color.chromaBitDepth).append(",")
                        sb.append("\"rawSpace\":").append(color.rawSpace).append(",")
                        sb.append("\"rawRange\":").append(color.rawRange).append(",")
                        sb.append("\"rawTransfer\":").append(color.rawTransfer)
                        sb.append("}")
                    }
                    sb.append("}")
                }
                sb.append("],\"selectedTextLanguage\":").append(esc(event.selectedTextLanguage))
                sb.append("}")
            }
            is VideoInputFormatV3 -> {
                sb.append("{")
                event.format?.let { format ->
                    writeFormatInfo(sb, format)
                }
                sb.append("}")
            }
            is AudioInputFormatV3 -> {
                sb.append("{")
                event.format?.let { format ->
                    writeFormatInfo(sb, format)
                }
                sb.append("}")
            }
            is RenderedFirstFrameV3 -> {
                sb.append("{")
                sb.append("\"presentationTimeUs\":").append(event.presentationTimeUs)
                sb.append(",\"isFirstOfSession\":").append(event.isFirstOfSession)
                sb.append("}")
            }
            is DrmSessionAcquiredV3 -> {
                sb.append("{")
                sb.append("\"state\":").append(event.state)
                sb.append("}")
            }
            is DrmKeysLoadedV3 -> {
                sb.append("{")
                sb.append("\"schemeDataCount\":").append(event.schemeDataCount)
                sb.append("}")
            }
            is VstTotalV3 -> {
                sb.append("{")
                sb.append("\"vstMs\":").append(event.vstMs).append(",")
                sb.append("\"authMs\":").append(event.authMs).append(",")
                sb.append("\"manifestMs\":").append(event.manifestMs).append(",")
                sb.append("\"drmMs\":").append(event.drmMs).append(",")
                sb.append("\"firstChunkMs\":").append(event.firstChunkMs).append(",")
                sb.append("\"initChunksMs\":").append(event.initChunksMs).append(",")
                sb.append("\"chunksDownloadedBeforeFirstFrame\":").append(event.chunksDownloadedBeforeFirstFrame).append(",")
                sb.append("\"chunksToPlay\":").append(event.chunksToPlay)
                sb.append("}")
            }
            is CustomEventV3 -> {
                sb.append("{")
                sb.append("\"name\":").append(esc(event.name))
                event.details?.let { details ->
                    val keys = details.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        sb.append(",").append(esc(key)).append(":").append(esc(details.opt(key)?.toString()))
                    }
                }
                sb.append("}")
            }
            is StartupObservationV3 -> {
                sb.append("{")
                sb.append("\"observation\":").append(esc(event.observation))
                sb.append("}")
            }
            is SessionAttachedV3 -> {
                sb.append("{")
                sb.append("\"url\":").append(esc(event.url))
                sb.append("}")
            }
            is PlaybackStateChangedV3 -> {
                sb.append("{")
                sb.append("\"state\":").append(event.state)
                sb.append("}")
            }
            is IsPlayingChangedV3 -> {
                sb.append("{")
                sb.append("\"isPlaying\":").append(event.isPlaying)
                sb.append("}")
            }
            is PlayWhenReadyChangedV3 -> {
                sb.append("{")
                sb.append("\"playWhenReady\":").append(event.playWhenReady)
                sb.append("}")
            }
            is LoadingChangedV3 -> {
                sb.append("{")
                sb.append("\"isLoading\":").append(event.isLoading)
                sb.append("}")
            }
            is TrackSwitchV3 -> {
                sb.append("{")
                sb.append("\"trackType\":").append(event.trackType).append(",")
                sb.append("\"fromFormatId\":").append(esc(event.fromFormatId)).append(",")
                sb.append("\"toFormatId\":").append(esc(event.toFormatId)).append(",")
                sb.append("\"reason\":").append(event.reason).append(",")
                sb.append("\"bufferMs\":").append(event.bufferMs)
                sb.append("}")
            }
            is PlayerErrorV3 -> {
                sb.append("{")
                sb.append("\"errorCode\":").append(event.errorCode).append(",")
                sb.append("\"errorCodeName\":").append(esc(event.errorCodeName)).append(",")
                sb.append("\"errorMessage\":").append(esc(event.errorMessage))
                sb.append("}")
            }
            is SeekStartedV3 -> {
                sb.append("{")
                sb.append("\"positionMs\":").append(event.positionMs)
                sb.append("}")
            }
            is PositionDiscontinuityV3 -> {
                sb.append("{")
                sb.append("\"reason\":").append(event.reason)
                sb.append("}")
            }
            is VideoDecoderInitializedV3 -> {
                sb.append("{")
                sb.append("\"decoderName\":").append(esc(event.decoderName)).append(",")
                sb.append("\"initMs\":").append(event.initMs).append(",")
                sb.append("\"hardware\":").append(event.hardware).append(",")
                sb.append("\"secure\":").append(event.secure)
                sb.append("}")
            }
            is AudioDecoderInitializedV3 -> {
                sb.append("{")
                sb.append("\"decoderName\":").append(esc(event.decoderName)).append(",")
                sb.append("\"initMs\":").append(event.initMs).append(",")
                sb.append("\"hardware\":").append(event.hardware).append(",")
                sb.append("\"secure\":").append(event.secure)
                sb.append("}")
            }
            is DroppedFramesV3 -> {
                sb.append("{")
                sb.append("\"droppedFrames\":").append(event.droppedFrames).append(",")
                sb.append("\"elapsedMs\":").append(event.elapsedMs)
                sb.append("}")
            }
            is AudioUnderrunV3 -> {
                sb.append("{")
                sb.append("\"bufferSizeMs\":").append(event.bufferSizeMs)
                sb.append("}")
            }
            is BandwidthEstimateV3 -> {
                sb.append("{")
                sb.append("\"bitrateBps\":").append(event.bitrateBps)
                sb.append("}")
            }
            is ChunkLoadedV3 -> {
                sb.append("{")
                sb.append("\"chunkId\":").append(event.chunkId).append(",")
                sb.append("\"dataType\":").append(esc(event.dataType)).append(",")
                sb.append("\"trackType\":").append(esc(event.trackType)).append(",")
                sb.append("\"trackFormatId\":").append(esc(event.trackFormatId)).append(",")
                sb.append("\"uriRedacted\":").append(esc(event.uriRedacted)).append(",")
                sb.append("\"uriHash\":").append(esc(event.uriHash)).append(",")
                sb.append("\"byteStart\":").append(event.byteStart).append(",")
                sb.append("\"byteLength\":").append(event.byteLength).append(",")
                sb.append("\"mediaStartMs\":").append(event.mediaStartMs).append(",")
                sb.append("\"mediaEndMs\":").append(event.mediaEndMs).append(",")
                sb.append("\"durationMs\":").append(event.durationMs).append(",")
                sb.append("\"requestStartMs\":").append(event.requestStartMs).append(",")
                sb.append("\"loadDurationMs\":").append(event.loadDurationMs).append(",")
                sb.append("\"bytesLoaded\":").append(event.bytesLoaded).append(",")
                sb.append("\"throughputKbps\":").append(event.throughputKbps).append(",")
                sb.append("\"httpStatus\":").append(event.httpStatus).append(",")
                sb.append("\"cacheHint\":").append(esc(event.cacheHint)).append(",")
                sb.append("\"outcome\":").append(esc(event.outcome)).append(",")
                sb.append("\"errorMessage\":").append(esc(event.errorMessage)).append(",")
                sb.append("\"gapBeforeMs\":").append(event.gapBeforeMs)
                if (includeRaw && event.uriRaw != null) {
                    sb.append(",\"uriRaw\":").append(esc(event.uriRaw))
                }
                sb.append("}")
            }
            is DeviceCapabilitiesV3 -> {
                sb.append("{")
                sb.append("\"capabilities\":{")
                val entries = event.deviceCapabilities.entries.sortedBy { it.key }
                entries.forEachIndexed { idx, (k, v) ->
                    if (idx > 0) sb.append(",")
                    sb.append(esc(k)).append(":").append(esc(v.toString()))
                }
                sb.append("}")
                sb.append("}")
            }
            is AdSnapshotV3 -> {
                sb.append("{")
                sb.append("\"adGroupCount\":").append(event.adGroupCount).append(",")
                sb.append("\"adCount\":").append(event.adCount).append(",")
                sb.append("\"adPositions\":[")
                event.adPositions.forEachIndexed { idx, pos ->
                    if (idx > 0) sb.append(",")
                    sb.append(pos)
                }
                sb.append("],")
                sb.append("\"adStates\":[")
                event.adStates.forEachIndexed { idx, state ->
                    if (idx > 0) sb.append(",")
                    sb.append(esc(state))
                }
                sb.append("]}")
            }
        }
    }

    private fun writeFormatInfo(sb: StringBuilder, format: FormatInfo) {
        sb.append("\"id\":").append(esc(format.id)).append(",")
        sb.append("\"sampleMime\":").append(esc(format.sampleMime)).append(",")
        sb.append("\"containerMime\":").append(esc(format.containerMime)).append(",")
        sb.append("\"codecs\":").append(esc(format.codecs)).append(",")
        sb.append("\"bitrate\":").append(format.bitrate).append(",")
        sb.append("\"averageBitrate\":").append(format.averageBitrate).append(",")
        sb.append("\"peakBitrate\":").append(format.peakBitrate).append(",")
        sb.append("\"width\":").append(format.width).append(",")
        sb.append("\"height\":").append(format.height).append(",")
        sb.append("\"frameRate\":").append(format.frameRate).append(",")
        sb.append("\"pixelRatio\":").append(format.pixelRatio).append(",")
        sb.append("\"channels\":").append(format.channels).append(",")
        sb.append("\"sampleRate\":").append(format.sampleRate).append(",")
        sb.append("\"language\":").append(esc(format.language)).append(",")
        sb.append("\"roleFlags\":").append(format.roleFlags)
        format.color?.let { color ->
            sb.append(",\"color\":{")
            sb.append("\"space\":").append(esc(color.space)).append(",")
            sb.append("\"range\":").append(esc(color.range)).append(",")
            sb.append("\"transfer\":").append(esc(color.transfer)).append(",")
            sb.append("\"hdrType\":").append(esc(color.hdrType)).append(",")
            sb.append("\"lumaBitDepth\":").append(color.lumaBitDepth).append(",")
            sb.append("\"chromaBitDepth\":").append(color.chromaBitDepth).append(",")
            sb.append("\"rawSpace\":").append(color.rawSpace).append(",")
            sb.append("\"rawRange\":").append(color.rawRange).append(",")
            sb.append("\"rawTransfer\":").append(color.rawTransfer)
            sb.append("}")
        }
    }
}
