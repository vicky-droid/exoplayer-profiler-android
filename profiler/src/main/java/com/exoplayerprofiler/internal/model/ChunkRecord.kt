package com.exoplayerprofiler.internal.model

/**
 * One manifest/init/media/DARM load seen through the load callbacks. A row in
 * the chunk table and input to gap/late analysis.
 */
internal data class ChunkRecord(
    val chunkId: Int,
    val dataType: String,
    val trackType: String,
    val trackFormatId: String?,
    val uriRedacted: String,
    val uriHash: String,
    val uriRaw: String?,
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
    val contentKind: String,
    val gapBeforeMs: Long?,
) {
    companion object {
        const val DATA_TYPE_MANIFEST = "MANIFEST"
        const val DATA_TYPE_MEDIA = "MEDIA"
        const val DATA_TYPE_INIT = "INIT"
        const val DATA_TYPE_DRM = "DRM"
        const val DATA_TYPE_TIME_SYNC = "TIME_SYNC"
        const val DATA_TYPE_AD = "AD"
        const val DATA_TYPE_OTHER = "OTHER"

        const val TRACK_TYPE_VIDEO = "VIDEO"
        const val TRACK_TYPE_AUDIO = "AUDIO"
        const val TRACK_TYPE_TEXT = "TEXT"
        const val TRACK_TYPE_UNKNOWN = "UNKNOWN"

        const val OUTCOME_COMPLETED = "COMPLETED"
        const val OUTCOME_CANCELED = "CANCELED"
        const val OUTCOME_ERROR = "ERROR"

        const val CONTENT_KIND_CONTENT = "CONTENT"
        const val CONTENT_KIND_AD = "AD"
        const val CONTENT_KIND_UNKNOWN = "UNKNOWN"
    }
}
