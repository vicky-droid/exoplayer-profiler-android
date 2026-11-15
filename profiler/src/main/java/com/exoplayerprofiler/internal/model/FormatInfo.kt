package com.exoplayerprofiler.internal.model

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * Typed snapshot of a Media3 [Format]. Mirrors the fields the viewer needs to
 * render track details and color/HDR info.
 */
@UnstableApi
internal data class FormatInfo(
    val id: String?,
    val sampleMime: String?,
    val containerMime: String?,
    val codecs: String?,
    val bitrate: Int,
    val averageBitrate: Int,
    val peakBitrate: Int,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val pixelRatio: Float,
    val channels: Int,
    val sampleRate: Int,
    val language: String?,
    val roleFlags: Int,
    val color: ColorDescription?,
) {
    companion object {
        fun from(format: Format): FormatInfo {
            val color = ColorDescription.from(format.colorInfo, format.codecs)
            return FormatInfo(
                id = format.id,
                sampleMime = format.sampleMimeType,
                containerMime = format.containerMimeType,
                codecs = format.codecs,
                bitrate = format.bitrate,
                averageBitrate = format.averageBitrate,
                peakBitrate = format.peakBitrate,
                width = format.width,
                height = format.height,
                frameRate = format.frameRate,
                pixelRatio = format.pixelWidthHeightRatio,
                channels = format.channelCount,
                sampleRate = format.sampleRate,
                language = format.language,
                roleFlags = format.roleFlags,
                color = color,
            )
        }
    }
}
