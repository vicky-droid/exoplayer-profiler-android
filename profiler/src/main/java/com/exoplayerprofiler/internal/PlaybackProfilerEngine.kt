package com.exoplayerprofiler.internal

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.exoplayerprofiler.CaptureLevel
import com.exoplayerprofiler.ProfilerConfig
import com.exoplayerprofiler.internal.collect.CapabilityProbe
import com.exoplayerprofiler.internal.json.EventJsonWriter
import com.exoplayerprofiler.internal.model.*
import com.exoplayerprofiler.internal.redact.Redactor
import com.exoplayerprofiler.internal.sink.EventSink
import com.exoplayerprofiler.internal.sink.FileSink
import com.exoplayerprofiler.internal.sink.LogcatSink
import com.exoplayerprofiler.internal.sink.HeaderTemplate
import com.exoplayerprofiler.internal.sink.RetentionManager
import com.exoplayerprofiler.internal.sink.SessionJournal
import com.exoplayerprofiler.internal.writer.WriterThread
import com.exoplayerprofiler.ProfilerSink
import com.exoplayerprofiler.internal.sink.ProfilerSinkAdapter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
@Suppress("unused", "Deprecation", "UnnecessaryParentheses")
internal class PlaybackProfilerEngine {

  private var config: ProfilerConfig? = null
  private var context: Context? = null

  /** Writer thread + logcat sink; only created when the profiler is enabled. */
  private var writerThread: WriterThread? = null
  private var logcatSink: LogcatSink? = null
  private var fileSink: FileSink? = null

  /** Public sinks; the adapter outlives the writer thread so sinks can be added at any time. */
  private val sinkAdapter = ProfilerSinkAdapter()
  private val attributes = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
  private var sessionAttributes: Map<String, String> = emptyMap()
  @Volatile private var pendingPlayClickMs = 0L
  @Volatile private var pendingAuthStartMs = 0L
  @Volatile private var pendingAuthEndMs = 0L

  @Synchronized
  fun addSink(sink: ProfilerSink) = sinkAdapter.add(sink)
  @Synchronized
  fun removeSink(sink: ProfilerSink) = sinkAdapter.remove(sink)

  @Synchronized
  fun setSessionAttribute(key: String, value: String?) {
    if (value == null) attributes.remove(key) else attributes[key] = value
  }
  private var retentionManager: RetentionManager? = null
  private var capabilityProbe: CapabilityProbe? = null

  /** Session state – only touched on the writer thread. */
  private var sessionId = ""
  private var sessionStartRealtimeMs = 0L
  /** Writer queue drop count when the current session began. */
  private var queueDropBaseline = 0L
  /** Writer queue drops per category when the current session began. */
  private var queueDropCategoryBaseline: Map<String, Int> = emptyMap()
  private var sessionStartEpochMs = 0L
  private var playClickTimeMs = 0L
  private var authStartMs = 0L
  private var authEndMs = 0L
  private var firstFrameTimeMs = 0L
  private var manifestLoadStartMs = 0L
  private var manifestLoadEndMs = 0L
  private var initSegmentStartMs = 0L
  private var initSegmentEndMs = 0L
  private var firstSegmentStartMs = 0L
  private var firstSegmentEndMs = 0L
  private var drmLicenseStartMs = 0L
  private var drmLicenseEndMs = 0L
  private var chunkCount = 0
  private var eventCounter = 0

  /** Track switch tracking: last format id per track type (2=video, 1=audio, 3=text). */
  private var lastFormatIdByTrackType = mutableMapOf<Int, String?>()
  private var lastDroppedFramesEventTimeMs = 0L

  /** Chunk tracking: keyed by loadTaskId. */
  private val chunkRecords = mutableMapOf<Long, ChunkRecordBuilder>()
  private var chunkCounter = 0
  private var lastMediaEndMsByTrackType = mutableMapOf<Int, Long>()

  private data class ChunkRecordBuilder(
      val dataType: String,
      val trackType: String,
      val intTrackType: Int,
      val trackFormatId: String?,
      val uriRedacted: String,
      val uriHash: String,
      val byteStart: Long,
      val byteLength: Long,
      val mediaStartMs: Long,
      val mediaEndMs: Long,
      val durationMs: Long,
      val requestStartMs: Long,
      var loadDurationMs: Long = 0L,
      var bytesLoaded: Long = 0L,
      var httpStatus: Int = -1,
      var cacheHint: String? = null,
      var outcome: String = ChunkRecord.OUTCOME_COMPLETED,
      var errorMessage: String? = null,
      var gapBeforeMs: Long? = null,
      val uriRaw: String? = null,
  )

  private var attachedPlayer: ExoPlayer? = null

  /**
   * Set by the ads layer between an ad break (or ad) start and the break end. IMA CSAI plays
   * ads outside the ExoPlayer, so ExoPlayer.isPlayingAd stays false during them; the flag covers that.
   */
  @Volatile private var adBreakActive = false

  @Synchronized
  fun setAdBreakActive(active: Boolean) {
    adBreakActive = active
  }

  /**
   * Ad tagging (P7): the content kind at the moment an event is produced. Ad state comes from
   * the ads layer callbacks (adBreakActive) or from ExoPlayer.isPlayingAd for server-side ads.
   */
  private fun currentKind(): String =
      if (adBreakActive || attachedPlayer?.isPlayingAd == true) ContentKind.AD else ContentKind.CONTENT

  /** Set by a seek; the next rendered frame is recorded as a post-seek first frame (E11). */
  private var awaitingFrameAfterSeek = false

  private val listener = object : AnalyticsListener {
    override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, presentationTimeUs: Long) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      val firstOfSession = firstFrameTimeMs == 0L
      if (!firstOfSession && !awaitingFrameAfterSeek) return
      if (firstOfSession) firstFrameTimeMs = now
      awaitingFrameAfterSeek = false
      emit(RenderedFirstFrameV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        presentationTimeUs = presentationTimeUs,
        isFirstOfSession = firstOfSession,
      ), now)
      if (firstOfSession) calculateVST(now)
      }
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    override fun onLoadStarted(eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      if (mediaLoadData.dataType == 4) { // manifest
        if (manifestLoadStartMs == 0L) manifestLoadStartMs = now
      }
      if (mediaLoadData.dataType == 1) { // media segment
        if (firstSegmentStartMs == 0L) firstSegmentStartMs = now
      }
      if (mediaLoadData.dataType == 2) { // media initialization segment
        if (initSegmentStartMs == 0L) initSegmentStartMs = now
      }
      val isDrm = mediaLoadData.dataType == 3 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_DRM
      val dataTypeStr = when {
        mediaLoadData.dataType == 4 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MANIFEST -> ChunkRecord.DATA_TYPE_MANIFEST
        mediaLoadData.dataType == 1 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA -> ChunkRecord.DATA_TYPE_MEDIA
        mediaLoadData.dataType == 2 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA_INITIALIZATION -> ChunkRecord.DATA_TYPE_INIT
        isDrm -> ChunkRecord.DATA_TYPE_DRM
        else -> ChunkRecord.DATA_TYPE_OTHER
      }
      val rawUri = loadEventInfo.uri.toString()
      val redactedUri = if (isDrm) Redactor.redactDrmUrl(rawUri) else Redactor.redactUri(rawUri)
      emit(LoadStartedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        uriRedacted = redactedUri,
        uriHash = Redactor.hashUri(rawUri),
        dataType = dataTypeStr,
      ), now)

      // Track chunk for gap analysis
      val loadTaskId = loadEventInfo.loadTaskId
      val mimeType = mediaLoadData.trackFormat?.sampleMimeType ?: ""
      val trackType = when {
        (mediaLoadData.dataType == 4) -> 0 // manifest
        mimeType.contains("video") -> 2
        mimeType.contains("audio") -> 1
        else -> 0
      }
      val trackFormatId = mediaLoadData.trackFormat?.id
      // Byte range and media time not available from MediaLoadData in this version
      val byteStart = -1L
      val byteLength = -1L
      val mediaStartMs = mediaLoadData.mediaStartTimeMs
      val mediaEndMs = mediaLoadData.mediaEndTimeMs
      val durationMs = -1L

      // Compute gapBeforeMs for media chunks
      var gapBeforeMs: Long? = null
      if (trackType == 1 || trackType == 2) { // media or audio
        val prevEnd = lastMediaEndMsByTrackType[trackType]
        if (prevEnd != null && mediaStartMs > 0) {
          gapBeforeMs = mediaStartMs - prevEnd
        }
      }

      chunkRecords[loadTaskId] = ChunkRecordBuilder(
        uriRaw = if (rawChunkUris) rawUri else null,
        dataType = dataTypeStr,
        trackType = when {
          trackType == 2 -> ChunkRecord.TRACK_TYPE_VIDEO
          trackType == 1 -> ChunkRecord.TRACK_TYPE_AUDIO
          trackType == 0 -> ChunkRecord.TRACK_TYPE_UNKNOWN
          else -> ChunkRecord.TRACK_TYPE_UNKNOWN
        },
        intTrackType = trackType,
        trackFormatId = trackFormatId,
        uriRedacted = redactedUri,
        uriHash = Redactor.hashUri(rawUri),
        byteStart = byteStart,
        byteLength = byteLength,
        mediaStartMs = mediaStartMs,
        mediaEndMs = mediaEndMs,
        durationMs = durationMs,
        requestStartMs = sessionTime(now),
        gapBeforeMs = gapBeforeMs,
      )
      }
    }

    override fun onLoadCompleted(eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      if (mediaLoadData.dataType == 4) {
        if (manifestLoadEndMs == 0L) manifestLoadEndMs = now
      }
      if (mediaLoadData.dataType == 1) {
        chunkCount++
        if (firstSegmentEndMs == 0L) firstSegmentEndMs = now
      }
      if (mediaLoadData.dataType == 2) {
        chunkCount++
        if (initSegmentEndMs == 0L) initSegmentEndMs = now
      }
      val isDrm = mediaLoadData.dataType == 3 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_DRM
      val dataTypeStr = when {
        mediaLoadData.dataType == 4 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MANIFEST -> ChunkRecord.DATA_TYPE_MANIFEST
        mediaLoadData.dataType == 1 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA -> ChunkRecord.DATA_TYPE_MEDIA
        mediaLoadData.dataType == 2 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA_INITIALIZATION -> ChunkRecord.DATA_TYPE_INIT
        isDrm -> ChunkRecord.DATA_TYPE_DRM
        else -> ChunkRecord.DATA_TYPE_OTHER
      }
      val rawUri = loadEventInfo.uri.toString()
      val redactedUri = if (isDrm) Redactor.redactDrmUrl(rawUri) else Redactor.redactUri(rawUri)

      val singleHeaders = mutableMapOf<String, String>()
      for ((k, vList) in loadEventInfo.responseHeaders) {
        if (vList.isNotEmpty()) {
          singleHeaders[k] = vList.first()
        }
      }
      val safeHeaders = Redactor.redactHeaders(singleHeaders)

      emit(LoadCompletedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        uriRedacted = redactedUri,
        uriHash = Redactor.hashUri(rawUri),
        // Chunked responses have no Content-Length, so use the bytes actually read.
        bytes = if (loadEventInfo.bytesLoaded > 0) loadEventInfo.bytesLoaded.toString() else safeHeaders["Content-Length"] ?: "",
        dataType = dataTypeStr,
      ), now)

      // Complete chunk record and emit ChunkLoadedV3
      val loadTaskId = loadEventInfo.loadTaskId
      chunkRecords.remove(loadTaskId)?.let { chunk ->
        val cacheHint = safeHeaders["X-Cache"]
            ?: safeHeaders["Age"]
            ?: safeHeaders["CF-Cache-Status"]
        val bytesLoaded = loadEventInfo.bytesLoaded
        val loadDurationMs = loadEventInfo.loadDurationMs

        // Update last media end time for gap calculation
        if (chunk.intTrackType == 1 || chunk.intTrackType == 2) { // media or audio
          if (chunk.mediaEndMs > 0) {
            lastMediaEndMsByTrackType[chunk.intTrackType] = chunk.mediaEndMs
          }
        }

        emit(ChunkLoadedV3(
          tMs = sessionTime(now),
          wallMs = System.currentTimeMillis(),
          thread = Thread.currentThread().name,
          contentKind = currentKind(),
          chunkId = chunkCounter,
          dataType = chunk.dataType,
          trackType = chunk.trackType,
          trackFormatId = chunk.trackFormatId,
          uriRedacted = chunk.uriRedacted,
          uriHash = chunk.uriHash,
          uriRaw = if (rawChunkUris) chunk.uriRaw else null,
          byteStart = chunk.byteStart,
          byteLength = chunk.byteLength,
          mediaStartMs = chunk.mediaStartMs,
          mediaEndMs = chunk.mediaEndMs,
          durationMs = chunk.durationMs,
          requestStartMs = chunk.requestStartMs,
          loadDurationMs = loadDurationMs,
          bytesLoaded = bytesLoaded,
          throughputKbps = if (loadDurationMs > 0) bytesLoaded * 8 / loadDurationMs else 0L,
          httpStatus = -1,
          cacheHint = cacheHint,
          outcome = ChunkRecord.OUTCOME_COMPLETED,
          errorMessage = null,
          gapBeforeMs = chunk.gapBeforeMs,
        ), now)
        chunkCounter++
      }
      }
    }

    override fun onTracksChanged(eventTime: EventTime, tracks: Tracks) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      val trackList = tracks.groups.flatMap { group ->
        (0 until group.length).map { i ->
          val format = group.getTrackFormat(i)
          val trackType = when {
            (format.sampleMimeType ?: "").contains("video") -> 2
            (format.sampleMimeType ?: "").contains("audio") -> 1
            (format.sampleMimeType ?: "").contains("text") ||
              (format.sampleMimeType ?: "").contains("application/ttml") ||
              (format.sampleMimeType ?: "").contains("subtitle") -> 3
            else -> 0
          }
          TrackInfoV3(
            trackType = trackType,
            trackId = format.id,
            id = format.id,
            mimeType = format.sampleMimeType,
            containerMimeType = format.containerMimeType,
            codecs = format.codecs,
            bitrate = format.bitrate,
            width = format.width,
            height = format.height,
            language = format.language,
            frameRate = format.frameRate,
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            pcmEncoding = format.pcmEncoding,
            rotationDegrees = format.rotationDegrees,
            stereoMode = format.stereoMode,
            roleFlags = format.roleFlags,
            selected = group.isSelected,
            supported = group.isTrackSupported(i),
            color = ColorDescription.from(format.colorInfo, format.codecs),
          )
        }
      }
      emit(TracksChangedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        tracks = trackList,
        selectedTextLanguage = tracks.groups
          .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected }
          .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
          .firstOrNull()?.language,
      ), now)
      }
    }

    override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(VideoInputFormatV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        format = FormatInfo.from(format),
      ), now)
      // Media3 reports ABR changes on the decoder input path too; some changes reach
      // only this callback. Share the per-track map with the downstream path so a
      // change is recorded once, whichever callback fires first.
      recordVideoFormatSwitch(now, format.id)
      }
    }

    private fun recordVideoFormatSwitch(now: Long, formatId: String?) {
      val trackType = 2
      val previousId = lastFormatIdByTrackType[trackType]
      if (previousId != null && formatId != null && previousId != formatId) {
        emit(TrackSwitchV3(
          tMs = sessionTime(now),
          wallMs = System.currentTimeMillis(),
          thread = Thread.currentThread().name,
          contentKind = currentKind(),
          trackType = trackType,
          fromFormatId = previousId,
          toFormatId = formatId,
          reason = 1,
          bufferMs = 0,
        ), now)
      }
      if (formatId != null) lastFormatIdByTrackType[trackType] = formatId
    }

    override fun onAudioInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(AudioInputFormatV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        format = FormatInfo.from(format),
      ), now)
      }
    }

    override fun onDrmSessionAcquired(eventTime: EventTime, state: Int) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      if (drmLicenseStartMs == 0L) {
        drmLicenseStartMs = now
      }
      emit(DrmSessionAcquiredV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        state = state,
      ), now)
      }
    }

    override fun onDrmKeysLoaded(eventTime: EventTime, keyRequestInfo: androidx.media3.exoplayer.drm.KeyRequestInfo) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      if (drmLicenseEndMs == 0L) {
        drmLicenseEndMs = now
      }
      val schemeCount = keyRequestInfo.schemeDatas?.size ?: 0
      emit(DrmKeysLoadedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        schemeDataCount = schemeCount,
      ), now)
      }
    }

    override fun onPlaybackStateChanged(eventTime: EventTime, playbackState: Int) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      // Playback starting again on the attached player after its session ended is a replay: it gets a new session.
      // Media3 Player.STATE_BUFFERING == 2, STATE_READY == 3.
      if (playbackState == 2 || playbackState == 3) reopenSessionIfEnded()
      emit(PlaybackStateChangedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        state = playbackState,
      ), now)
      // Content finished. Finalise now rather than waiting for a release that may not come.
      // Media3 Player.STATE_ENDED == 4; endSession() is idempotent.
      if (playbackState == 4) {
        try {
          endSession()
        } catch (e: Exception) {
          Health.record("engine.endOnPlaybackEnded", e)
        }
      }
      }
    }

    override fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(IsPlayingChangedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        isPlaying = isPlaying,
      ), now)
      }
    }

    override fun onPlayWhenReadyChanged(eventTime: EventTime, playWhenReady: Boolean, reason: Int) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(PlayWhenReadyChangedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        playWhenReady = playWhenReady,
      ), now)
      }
    }

    override fun onIsLoadingChanged(eventTime: EventTime, isLoading: Boolean) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(LoadingChangedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        isLoading = isLoading,
      ), now)
      }
    }

    override fun onTrackSelectionParametersChanged(eventTime: EventTime, trackSelectionParameters: TrackSelectionParameters) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      // Track switch detection handled in onDownstreamFormatChanged
      }
    }

    // Media3 signature: (eventTime, decoderName, initializedTimestampMs, initializationDurationMs).
    override fun onVideoDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(VideoDecoderInitializedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        decoderName = decoderName,
        initMs = initializationDurationMs,
        hardware = true,
        secure = false,
      ), now)
      }
    }

    override fun onAudioDecoderInitialized(eventTime: EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(AudioDecoderInitializedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        decoderName = decoderName,
        initMs = initializationDurationMs,
        hardware = true,
        secure = false,
      ), now)
      }
    }

    override fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      // Decoder released - no specific event type, could add but keeping minimal
      }
    }

    override fun onAudioDecoderReleased(eventTime: EventTime, decoderName: String) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      // Decoder released - no specific event type, could add but keeping minimal
      }
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    override fun onVideoSizeChanged(eventTime: EventTime, width: Int, height: Int, unappliedRotationDegrees: Int, pixelWidthHeightRatio: Float) { synchronized(this@PlaybackProfilerEngine) {
      // Not tracked as a separate event type; captured in VideoInputFormat
      }
    }

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      // Media3 reports the frames dropped since its previous call (a delta, not a running total), so
      // each callback is emitted as-is. A running-total comparison here lost drops after the first call.
      if (droppedFrames > 0) {
        emit(DroppedFramesV3(
          tMs = sessionTime(now),
          wallMs = System.currentTimeMillis(),
          thread = Thread.currentThread().name,
          contentKind = currentKind(),
          droppedFrames = droppedFrames,
          elapsedMs = elapsedMs,
        ), now)
        lastDroppedFramesEventTimeMs = now
      }
      }
    }

    override fun onAudioUnderrun(eventTime: EventTime, bufferSizeMs: Int, rebuffering: Long, underrunDurationMs: Long) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(AudioUnderrunV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        bufferSizeMs = bufferSizeMs.toLong(),
      ), now)
      }
    }

    // Media3 signature: (eventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long).
    override fun onBandwidthEstimate(eventTime: EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(BandwidthEstimateV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        bitrateBps = bitrateEstimate,
      ), now)
      }
    }

    override fun onLoadCanceled(eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      val isDrm = mediaLoadData.dataType == 3 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_DRM
      val dataTypeStr = when {
        mediaLoadData.dataType == 4 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MANIFEST -> ChunkRecord.DATA_TYPE_MANIFEST
        mediaLoadData.dataType == 1 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA -> ChunkRecord.DATA_TYPE_MEDIA
        mediaLoadData.dataType == 2 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA_INITIALIZATION -> ChunkRecord.DATA_TYPE_INIT
        isDrm -> ChunkRecord.DATA_TYPE_DRM
        else -> ChunkRecord.DATA_TYPE_OTHER
      }
      val rawUri = loadEventInfo.uri.toString()
      val redactedUri = if (isDrm) Redactor.redactDrmUrl(rawUri) else Redactor.redactUri(rawUri)
      emit(LoadStartedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        uriRedacted = redactedUri,
        uriHash = Redactor.hashUri(rawUri),
        dataType = "${dataTypeStr}_CANCELED",
      ), now)

      // Complete chunk as canceled
      val loadTaskId = loadEventInfo.loadTaskId
      chunkRecords.remove(loadTaskId)?.let { chunk ->
        emit(ChunkLoadedV3(
          tMs = sessionTime(now),
          wallMs = System.currentTimeMillis(),
          thread = Thread.currentThread().name,
          contentKind = currentKind(),
          chunkId = chunkCounter,
          dataType = chunk.dataType,
          trackType = chunk.trackType,
          trackFormatId = chunk.trackFormatId,
          uriRedacted = chunk.uriRedacted,
          uriHash = chunk.uriHash,
          uriRaw = if (rawChunkUris) chunk.uriRaw else null,
          byteStart = chunk.byteStart,
          byteLength = chunk.byteLength,
          mediaStartMs = chunk.mediaStartMs,
          mediaEndMs = chunk.mediaEndMs,
          durationMs = chunk.durationMs,
          requestStartMs = chunk.requestStartMs,
          loadDurationMs = 0L,
          bytesLoaded = 0L,
          throughputKbps = 0L,
          httpStatus = -1,
          cacheHint = null,
          outcome = ChunkRecord.OUTCOME_CANCELED,
          errorMessage = null,
          gapBeforeMs = chunk.gapBeforeMs,
        ), now)
        chunkCounter++
      }
      }
    }

    override fun onLoadError(eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, canceled: Boolean) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      val isDrm = mediaLoadData.dataType == 3 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_DRM
      val dataTypeStr = when {
        mediaLoadData.dataType == 4 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MANIFEST -> ChunkRecord.DATA_TYPE_MANIFEST
        mediaLoadData.dataType == 1 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA -> ChunkRecord.DATA_TYPE_MEDIA
        mediaLoadData.dataType == 2 || mediaLoadData.dataType == androidx.media3.common.C.DATA_TYPE_MEDIA_INITIALIZATION -> ChunkRecord.DATA_TYPE_INIT
        isDrm -> ChunkRecord.DATA_TYPE_DRM
        else -> ChunkRecord.DATA_TYPE_OTHER
      }
      val rawUri = loadEventInfo.uri.toString()
      val redactedUri = if (isDrm) Redactor.redactDrmUrl(rawUri) else Redactor.redactUri(rawUri)
      emit(LoadStartedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        uriRedacted = redactedUri,
        uriHash = Redactor.hashUri(rawUri),
        dataType = "${dataTypeStr}_ERROR",
      ), now)

      // Complete chunk as error
      val loadTaskId = loadEventInfo.loadTaskId
      chunkRecords.remove(loadTaskId)?.let { chunk ->
        emit(ChunkLoadedV3(
          tMs = sessionTime(now),
          wallMs = System.currentTimeMillis(),
          thread = Thread.currentThread().name,
          contentKind = currentKind(),
          chunkId = chunkCounter,
          dataType = chunk.dataType,
          trackType = chunk.trackType,
          trackFormatId = chunk.trackFormatId,
          uriRedacted = chunk.uriRedacted,
          uriHash = chunk.uriHash,
          uriRaw = if (rawChunkUris) chunk.uriRaw else null,
          byteStart = chunk.byteStart,
          byteLength = chunk.byteLength,
          mediaStartMs = chunk.mediaStartMs,
          mediaEndMs = chunk.mediaEndMs,
          durationMs = chunk.durationMs,
          requestStartMs = chunk.requestStartMs,
          loadDurationMs = 0L,
          bytesLoaded = 0L,
          throughputKbps = 0L,
          httpStatus = -1,
          cacheHint = null,
          outcome = ChunkRecord.OUTCOME_ERROR,
          errorMessage = Redactor.redactErrorMessage(error.message),
          gapBeforeMs = chunk.gapBeforeMs,
        ), now)
        chunkCounter++
      }
      }
    }

    override fun onUpstreamDiscarded(eventTime: EventTime, mediaLoadData: MediaLoadData) { synchronized(this@PlaybackProfilerEngine) {
      // Not tracked as a separate event type
      }
    }

    override fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      val formatId = mediaLoadData.trackFormat?.id
      val trackType = when {
        (mediaLoadData.trackFormat?.sampleMimeType ?: "").contains("video") -> 2
        (mediaLoadData.trackFormat?.sampleMimeType ?: "").contains("audio") -> 1
        else -> 0
      }
      val previousId = lastFormatIdByTrackType[trackType]
      if (previousId != null && previousId != formatId && formatId != null) {
        emit(TrackSwitchV3(
          tMs = sessionTime(now),
          wallMs = System.currentTimeMillis(),
          thread = Thread.currentThread().name,
          contentKind = currentKind(),
          trackType = trackType,
          fromFormatId = previousId,
          toFormatId = formatId,
          reason = 1,
          bufferMs = 0,
        ), now)
      }
      lastFormatIdByTrackType[trackType] = formatId
      }
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    override fun onSeekStarted(eventTime: EventTime) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      if (firstFrameTimeMs > 0L) awaitingFrameAfterSeek = true
      emit(SeekStartedV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        positionMs = 0L,
      ), now)
      }
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    override fun onPositionDiscontinuity(eventTime: EventTime, reason: Int) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(PositionDiscontinuityV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        reason = reason,
      ), now)
      }
    }

    override fun onPlayerReleased(eventTime: EventTime) { synchronized(this@PlaybackProfilerEngine) {
      // The app releases the player on exit. Finalise the session so the
      // file and upload sinks receive SessionEnded instead of losing it.
      try {
        endSession()
      } catch (e: Exception) {
        Health.record("engine.endOnRelease", e)
      }
      attachedPlayer = null
      }
    }

    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(PlayerErrorV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        errorCode = error.errorCode,
        errorCodeName = null,
        errorMessage = Redactor.redactErrorMessage(error.message),
      ), now)
      }
    }

    override fun onVideoCodecError(eventTime: EventTime, error: Exception) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(PlayerErrorV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        errorCode = -1,
        errorCodeName = null,
        errorMessage = Redactor.redactErrorMessage("Video codec error: ${error.message}"),
      ), now)
      }
    }

    override fun onAudioCodecError(eventTime: EventTime, error: Exception) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(PlayerErrorV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        errorCode = -1,
        errorCodeName = null,
        errorMessage = Redactor.redactErrorMessage("Audio codec error: ${error.message}"),
      ), now)
      }
    }

    override fun onDrmSessionReleased(eventTime: EventTime) { synchronized(this@PlaybackProfilerEngine) {
      // Not tracked
      }
    }

    override fun onDrmKeysRestored(eventTime: EventTime) { synchronized(this@PlaybackProfilerEngine) {
      // Not tracked
      }
    }

    override fun onDrmKeysRemoved(eventTime: EventTime) { synchronized(this@PlaybackProfilerEngine) {
      // Not tracked
      }
    }

    override fun onDrmSessionManagerError(eventTime: EventTime, error: Exception) { synchronized(this@PlaybackProfilerEngine) {
      val now = eventTime.realtimeMs
      emit(PlayerErrorV3(
        tMs = sessionTime(now),
        wallMs = System.currentTimeMillis(),
        thread = Thread.currentThread().name,
        contentKind = currentKind(),
        errorCode = -1,
        errorCodeName = null,
        errorMessage = Redactor.redactErrorMessage("DRM error: ${error.message}"),
      ), now)
      }
    }

    override fun onCues(eventTime: EventTime, cueGroup: androidx.media3.common.text.CueGroup) { synchronized(this@PlaybackProfilerEngine) {
      // Text cues - summary only
      }
    }

    override fun onMetadata(eventTime: EventTime, metadata: androidx.media3.common.Metadata) { synchronized(this@PlaybackProfilerEngine) {
      // Metadata - not tracked
      }
    }
  }

  /**
   * Initialises the engine. Called by the reporter during application session
   * activation. No writer thread is created unless [config.enabled] is true.
   */
  @Synchronized
  fun init(ctx: Context, config: ProfilerConfig) {
    this.context = ctx.applicationContext
    this.config = config
  }

  private var writerInitFailed = false

  /** True only in debuggable builds that allow raw capture; set when the writer starts. */
  private var rawChunkUris = false

  private fun ensureWriterThread(): WriterThread? {
    if (writerThread != null) return writerThread
    if (writerInitFailed) return null
    return try {
      val ctx = context ?: return null
      val debuggable = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
      val sinks = CopyOnWriteArrayList<EventSink>()
      val profilerDir = File(ctx.filesDir, "playback_profiler")
      // File sink is always enabled when the profiler is active
      fileSink = FileSink(
        profilerDir,
        redacted = true,
        maxEvents = config?.maxEventsPerSession?.coerceAtLeast(100) ?: 10_000,
      ).also { it.outputListener = sinkAdapter }
      sinks.add(fileSink!!)
      retentionManager = RetentionManager(
        profilerDir,
        config?.retentionSessions ?: 20,
        config?.retentionBytes ?: 50L * 1024 * 1024
      )
      capabilityProbe = CapabilityProbe(ctx)
      if (config?.captureRawLocal == true && debuggable) {
        sinks.add(LogcatSink(enabled = true, maxChunkChars = config?.maxEventPayloadChars?.coerceAtLeast(1024) ?: 4096))
        logcatSink = sinks.last() as LogcatSink
      }

      val wt = WriterThread.getOrCreate("PlaybackProfilerWriter", 4096, sinks)
      // Sessions left unfinished by an earlier run (force-stop, crash) are rebuilt from their journals.
      // The task is posted before this run offers any event, so this run's own journal cannot be picked up.
      val template = HeaderTemplate(
        packageName = ctx.packageName,
        media3Version = "1.11.0",
        androidVersion = Build.VERSION.RELEASE,
        apiLevel = Build.VERSION.SDK_INT,
        config = ConfigSnapshot(
          captureLevel = config?.captureLevel?.name ?: "FULL",
          maxEventsPerSession = config?.maxEventsPerSession ?: 10_000,
        ),
        redacted = true,
      )
      // Recovered sessions go to the same sinks as live ones (so uploads see them), then retention runs.
      val retention = retentionManager
      wt.post {
        try {
          if (SessionJournal.recoverAll(profilerDir, template, sinkAdapter) > 0) retention?.enforce()
        } catch (e: Exception) {
          Health.record("engine.recovery", e, Health.Kind.RECOVERY)
        }
      }
      rawChunkUris = debuggable && config?.captureRawLocal == true
      writerThread = wt
      wt
    } catch (e: Exception) {
      // Log once and stop retrying on every event; the profiler stays initialised.
      writerInitFailed = true
      fileErrorCount++
      Health.record("engine.writerInit", e)
      null
    }
  }

  private var fileErrorCount = 0

  private fun isActive(): Boolean = config?.enabled == true

  /** Offer a typed event to the writer thread. No JSON serialisation or I/O happens here. */
  private fun emit(event: SchemaV3Event, realtimeMs: Long = SystemClock.elapsedRealtime()) {
    if (!isActive()) return
    // No open session (before the first attach, or after STATE_ENDED): the event has no session clock, so it
    // is dropped rather than written into the next session's file (C3). beginSession sets the clock before it
    // emits SessionStarted, so the session's own events are not affected.
    if (sessionStartRealtimeMs == 0L) return
    if (!isCaptured(event.category)) return
    ensureWriterThread()
    eventCounter++
    writerThread?.offer(event)
  }

  /** True when the configured capture level records an event in [category] now (E1, C5). */
  private fun isCaptured(category: String): Boolean =
    CaptureFilter.allows(config?.captureLevel ?: CaptureLevel.FULL, category, afterFirstFrame = firstFrameTimeMs > 0L)

  /** A playback that starts on an attached player after its session ended begins a new session (replay, N5). */
  private fun reopenSessionIfEnded() {
    if (sessionStartRealtimeMs == 0L && attachedPlayer != null) beginSession()
  }

  /** Converts absolute realtime (ms since boot) to session-relative time (ms since session start). */
  private fun sessionTime(realtimeMs: Long): Long {
    return if (sessionStartRealtimeMs > 0L) realtimeMs - sessionStartRealtimeMs else 0L
  }

  /** Events the writer queue dropped during the current session (overflow, not the file cap). */
  private fun queueDropsThisSession(): Int =
    ((writerThread?.queueDropCount() ?: 0L) - queueDropBaseline)
      .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

  /** Queue overflow drops during the current session, per category (the same window as [queueDropsThisSession]). */
  private fun queueDropsByCategoryThisSession(): Map<String, Int> {
    val now = writerThread?.queueDropCountsByCategory() ?: return emptyMap()
    return now.mapNotNull { (category, count) ->
      val delta = count - (queueDropCategoryBaseline[category] ?: 0)
      if (delta > 0) category to delta else null
    }.toMap()
  }

  @Synchronized
  fun beginSession() {
    if (!isActive()) return
    // Finalise and export the previous session (writes its file) before clearing state (E10).
    if (sessionStartRealtimeMs > 0L) endSession()
    sessionStartRealtimeMs = SystemClock.elapsedRealtime()
    sessionStartEpochMs = System.currentTimeMillis()
    // The writer may outlive a session; count queue drops from here on for this session only.
    queueDropBaseline = ensureWriterThread()?.queueDropCount() ?: 0L
    queueDropCategoryBaseline = ensureWriterThread()?.queueDropCountsByCategory() ?: emptyMap()
    sessionId = UUID.randomUUID().toString()
    retentionManager?.markOpen(sessionId)
    // Marks made before the session existed (click, then create and attach the player) count.
    playClickTimeMs = pendingPlayClickMs
    authStartMs = pendingAuthStartMs
    authEndMs = pendingAuthEndMs
    pendingPlayClickMs = 0L
    pendingAuthStartMs = 0L
    pendingAuthEndMs = 0L
    sessionAttributes = synchronized(attributes) { LinkedHashMap(attributes) }
    firstFrameTimeMs = 0L
    awaitingFrameAfterSeek = false
    manifestLoadStartMs = 0L
    manifestLoadEndMs = 0L
    initSegmentStartMs = 0L
    initSegmentEndMs = 0L
    firstSegmentStartMs = 0L
    firstSegmentEndMs = 0L
    drmLicenseStartMs = 0L
    drmLicenseEndMs = 0L
    chunkCount = 0
    eventCounter = 0
    // State from the previous session must not carry over (C2): a format seen last session is not a switch,
    // a chunk load that started last session has no record here, and chunk ids start again at 0.
    lastFormatIdByTrackType.clear()
    lastDroppedFramesEventTimeMs = 0L
    chunkRecords.clear()
    chunkCounter = 0
    lastMediaEndMsByTrackType.clear()
    emit(SessionStartedV3(
      tMs = 0L,
      wallMs = sessionStartEpochMs,
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      device = "${Build.MANUFACTURER} ${Build.MODEL}",
      sessionId = sessionId,
    ))
    emit(SessionConfigV3(
      tMs = 0L,
      wallMs = sessionStartEpochMs,
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      attributes = sessionAttributes,
      captureLevel = config?.captureLevel?.name ?: "FULL",
      maxEventsPerSession = config?.maxEventsPerSession ?: 10_000,
    ))
    // The probe makes blocking MediaDrm and codec-list calls that can take seconds, so it runs on
    // its own thread. The writer stays free for events and session files. The result is offered
    // only if the same session is still running when the probe finishes.
    val probeSessionId = sessionId
    val probeTMs = sessionTime(SystemClock.elapsedRealtime())
    val probe = capabilityProbe
    Thread(
      Runnable {
        val capsEvent = try {
          probe?.probe(
            tMs = probeTMs,
            wallMs = System.currentTimeMillis(),
            thread = Thread.currentThread().name,
          )
        } catch (e: Exception) {
          Health.record("engine.probe", e)
          null
        }
        if (capsEvent != null) {
          synchronized(this@PlaybackProfilerEngine) {
            // Same rules as every other event (N4): the same session must still be open, the capture level
            // must record it, and it is counted like the rest.
            if (sessionId == probeSessionId && sessionStartRealtimeMs != 0L && isCaptured(capsEvent.category)) {
              eventCounter++
              writerThread?.offer(capsEvent)
            }
          }
        }
      },
      "PlaybackProfilerProbe",
    ).apply { isDaemon = true }.start()
  }

  @OptIn(UnstableApi::class)
  @Synchronized
  fun attach(player: ExoPlayer) {
    if (!isActive()) return
    if (attachedPlayer === player) {
      // The same player again: a session starts only if the last one has ended (N5).
      reopenSessionIfEnded()
      return
    }
    if (attachedPlayer != null) {
      // The previous player must stop feeding this engine, or its events would land in the new session.
      attachedPlayer?.removeAnalyticsListener(listener)
      endSession()
    }
    attachedPlayer = player
    player.addAnalyticsListener(listener)
    if (sessionStartRealtimeMs == 0L) {
      beginSession()
    }
    val rawMediaId = player.currentMediaItem?.mediaId ?: ""
    val safeUrl = if (rawMediaId.startsWith("http://") || rawMediaId.startsWith("https://")) {
      Redactor.redactUri(rawMediaId)
    } else {
      Redactor.hashContentId(rawMediaId)
    }
    emit(SessionAttachedV3(
      tMs = SystemClock.elapsedRealtime() - sessionStartRealtimeMs,
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      url = safeUrl,
    ))
  }

  @Synchronized
  fun detach() {
    if (!isActive()) return
    attachedPlayer?.removeAnalyticsListener(listener)
    attachedPlayer = null
    adBreakActive = false
    endSession()
  }

  private fun endSession() {
    if (sessionStartRealtimeMs == 0L) return
    // One clock for the end: the marker's tMs is also the session's duration (monotonic, session-relative).
    val endTms = SystemClock.elapsedRealtime() - sessionStartRealtimeMs
    emit(SessionEndedV3(
      tMs = endTms,
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      sessionId = sessionId,
      eventCount = eventCounter,
    ))
    // Built after SessionEnded is emitted, so the header's eventCount includes the end marker (the marker's
    // own eventCount field does not). The file holds the same number of events.
    val header = buildSessionHeader(endTms)
    // File I/O must not run on the player thread; post it to the writer thread.
    writerThread?.onSessionEnded(sessionId, eventCounter, header)
    retentionManager?.markClosed(sessionId)
    // Retention runs after the session file is written: the writer thread runs posted work in order.
    val retention = retentionManager
    writerThread?.post { try { retention?.enforce() } catch (e: Exception) { Health.record("engine.retention", e) } }
    sessionStartRealtimeMs = 0L
    sessionStartEpochMs = 0L
    playClickTimeMs = 0L
  }

  /** Ends the running session (if any) and starts a fresh one. */
  @Synchronized
  fun startNewSession() {
    if (!isActive()) return
    beginSession()
  }

  private fun relTime(now: Long): Long =
      if (sessionStartRealtimeMs > 0L) (now - sessionStartRealtimeMs).coerceAtLeast(0L) else 0L

  @Synchronized
  fun reportPlayClick() {
    if (!isActive()) return
    val now = SystemClock.elapsedRealtime()
    if (sessionStartRealtimeMs == 0L) { pendingPlayClickMs = now; return }
    playClickTimeMs = now
    emit(PlaybackClickV3(
      tMs = relTime(now),
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
    ))
  }

  @Synchronized
  fun reportAuthStart() {
    if (!isActive()) return
    val now = SystemClock.elapsedRealtime()
    if (sessionStartRealtimeMs == 0L) { pendingAuthStartMs = now; return }
    authStartMs = now
    emit(StartupObservationV3(
      tMs = relTime(now),
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      observation = "auth_start",
    ))
  }

  @Synchronized
  fun reportAuthEnd(durationMs: Long) {
    if (!isActive()) return
    val now = SystemClock.elapsedRealtime()
    if (sessionStartRealtimeMs == 0L) { pendingAuthEndMs = now; return }
    authEndMs = now
    emit(StartupObservationV3(
      tMs = relTime(now),
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      observation = "auth_end:${durationMs}ms",
    ))
  }

  @Synchronized
  fun reportPlaybackRequest(contentId: String?, details: Map<String, Any>?) {
    if (!isActive()) return
    beginSession()
    val hashedContentId = Redactor.hashContentId(contentId)
    val sanitizedDetails = details?.mapNotNull { (k, v) ->
      if (Redactor.isSensitiveKey(k)) null
      else "$k=${Redactor.redactCustomEventValue(k, v)}"
    }?.joinToString(",") ?: ""
    emit(StartupObservationV3(
      tMs = SystemClock.elapsedRealtime() - sessionStartRealtimeMs,
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      observation = "playback_request:$hashedContentId:$sanitizedDetails",
    ))
  }

  @Synchronized
  fun reportCustomEvent(event: String, data: Map<String, Any>?) {
    if (!isActive()) return
    // App-level events fired before a playback session opens have no session
    // clock. Stamping them with boot time corrupts the timeline, so drop them.
    if (sessionStartRealtimeMs == 0L) return
    val detailsObj = data?.let { map ->
      val obj = org.json.JSONObject()
      for ((k, v) in map) {
        if (!Redactor.isSensitiveKey(k)) {
          val safeValue = Redactor.redactCustomEventValue(k, v)
          obj.put(k, safeValue)
        }
      }
      obj
    }
    emit(CustomEventV3(
      tMs = SystemClock.elapsedRealtime() - sessionStartRealtimeMs,
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      name = event,
      details = detailsObj,
    ))
  }

  fun reset() {
    var oldWriter: WriterThread? = null
    synchronized(this) {
    if (!isActive()) return
    // Finalise the current session (session end event + sinks) before teardown,
    // otherwise the playback is lost when the writer thread is closed.
    if (sessionStartRealtimeMs > 0L) {
      // Failure isolation: a finalisation error must never reach the caller.
      try {
        endSession()
      } catch (e: Exception) {
        fileErrorCount++
        Health.record("engine.endOnReset", e)
      }
    }
    writerInitFailed = false
    // Clean up directly without going through detach() → endSession() → emit(),
    // which would try to create a writer thread and write events. This is
    // important in unit-test environments where no Looper is available.
    attachedPlayer?.removeAnalyticsListener(listener)
    attachedPlayer = null
    oldWriter = writerThread
    writerThread = null
    logcatSink = null
    fileSink = null
    retentionManager = null
    config = null
    context = null
    sessionId = ""
    sessionStartRealtimeMs = 0L
    sessionStartEpochMs = 0L
    eventCounter = 0
    }
    // Joined outside the engine monitor: a sink that calls back into the engine must not stall reset.
    oldWriter?.close()
  }

  private fun calculateVST(now: Long) {
    if (firstFrameTimeMs == 0L) return
    val vstMs = if (playClickTimeMs > 0L) firstFrameTimeMs - playClickTimeMs else firstFrameTimeMs - sessionStartRealtimeMs
    val authMs = if ((authStartMs > 0) && (authEndMs > 0)) authEndMs - authStartMs else 0L
    val manifestMs = if ((manifestLoadStartMs > 0) && (manifestLoadEndMs > 0)) manifestLoadEndMs - manifestLoadStartMs else 0L
    val drmMs = if ((drmLicenseStartMs > 0) && (drmLicenseEndMs > 0)) drmLicenseEndMs - drmLicenseStartMs else 0L
    val firstChunkMs = when {
      firstSegmentStartMs == 0L -> 0L
      firstSegmentEndMs > 0L && firstSegmentEndMs <= firstFrameTimeMs ->
        firstSegmentEndMs - firstSegmentStartMs
      else -> if (firstFrameTimeMs > firstSegmentStartMs) firstFrameTimeMs - firstSegmentStartMs else 0L
    }
    val initChunksMs = if ((initSegmentStartMs > 0) && (initSegmentEndMs > 0)) initSegmentEndMs - initSegmentStartMs else 0L
    emit(VstTotalV3(
      tMs = sessionTime(now),
      wallMs = System.currentTimeMillis(),
      thread = Thread.currentThread().name,
      contentKind = ContentKind.UNKNOWN,
      vstMs = vstMs,
      authMs = authMs,
      manifestMs = manifestMs,
      drmMs = drmMs,
      firstChunkMs = firstChunkMs,
      initChunksMs = initChunksMs,
      chunksDownloadedBeforeFirstFrame = chunkCount,
      chunksToPlay = eventCounter,
    ))
  }

  private fun buildSessionHeader(durationMs: Long): SessionHeader {
      val ctx = context
      val debuggable = ctx?.applicationInfo?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 } == true
      val isRedacted = !(config?.captureRawLocal == true && debuggable)
      return SessionHeader(
        health = Health.snapshot(),
        sessionId = sessionId,
        packageName = context?.packageName ?: "",
        startedAtEpochMs = sessionStartEpochMs,
        exportedAtEpochMs = System.currentTimeMillis(),
        durationMs = durationMs,
        eventCount = eventCounter,
        droppedEvents = queueDropsThisSession(),
        droppedByCategory = queueDropsByCategoryThisSession(),
        cappedEventsCounted = 0,
        redacted = isRedacted,
        device = DeviceInfo(
          manufacturer = Build.MANUFACTURER,
          model = Build.MODEL,
          android = Build.VERSION.RELEASE,
          apiLevel = Build.VERSION.SDK_INT,
        ),
        media3Version = "1.11.0",
        sdkVersion = null,
        config = ConfigSnapshot(
          captureLevel = config?.captureLevel?.name ?: "FULL",
          maxEventsPerSession = config?.maxEventsPerSession ?: 10_000,
        ),
        fileErrors = fileErrorCount,
        attributes = sessionAttributes,
      )
    }

  }