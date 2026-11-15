package com.exoplayerprofiler.internal.model

import androidx.media3.common.util.UnstableApi

/**
 * Compares a content format with an ad format (P7). Pure function, no Android
 * dependencies. Only the fields the viewer's Formats tab shows are compared.
 */
@UnstableApi
internal object FormatDiff {

    /** One compared field. [content] and [ad] are the rendered values; equal means no mismatch. */
    data class Delta(val field: String, val content: String?, val ad: String?) {
        val mismatch: Boolean get() = content != ad
    }

    fun diff(content: FormatInfo, ad: FormatInfo): List<Delta> = listOf(
        Delta("codecs", content.codecs, ad.codecs),
        Delta("sampleMime", content.sampleMime, ad.sampleMime),
        Delta("resolution", resolution(content), resolution(ad)),
        Delta("bitrate", content.bitrate.toString(), ad.bitrate.toString()),
        Delta("frameRate", content.frameRate.toString(), ad.frameRate.toString()),
        Delta("channels", content.channels.toString(), ad.channels.toString()),
        Delta("sampleRate", content.sampleRate.toString(), ad.sampleRate.toString()),
        Delta("hdrType", content.color?.hdrType, ad.color?.hdrType),
        Delta("colorSpace", content.color?.space, ad.color?.space),
        Delta("colorTransfer", content.color?.transfer, ad.color?.transfer),
    )

    /** "same format" when no compared field differs, otherwise "mismatch". */
    fun verdict(deltas: List<Delta>): String =
        if (deltas.any { it.mismatch }) "mismatch" else "same format"

    private fun resolution(f: FormatInfo): String = "${f.width}x${f.height}"
}
