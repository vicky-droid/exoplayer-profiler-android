package com.exoplayerprofiler.internal.model

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi

/**
 * Decoded color description for a video track. Labels are derived from Media3
 * [ColorInfo] fields plus codec/HDR hints where the raw constants don't carry
 * enough information (for example Dolby Vision vs HDR10 vs HLG).
 *
 * The constants come from Media3's [C] so they follow the Media3 version the profiler uses.
 */
@UnstableApi
internal data class ColorDescription(
    /** BT601 | BT709 | BT2020 | null */
    val space: String?,
    /** LIMITED | FULL */
    val range: String?,
    /** SDR | SRGB | ST2084(PQ) | HLG | LINEAR */
    val transfer: String?,
    /** SDR | HDR10 | HDR10_PLUS | HLG | DOLBY_VISION | UNKNOWN */
    val hdrType: String,
    val lumaBitDepth: Int,
    val chromaBitDepth: Int,
    /** Raw Media3 colorSpace constant (for viewer-side re-interpretation) */
    val rawSpace: Int,
    /** Raw Media3 colorRange constant */
    val rawRange: Int,
    /** Raw Media3 colorTransfer constant */
    val rawTransfer: Int,
) {
    companion object {
        /**
         * Decodes a Media3 [ColorInfo] into a [ColorDescription].
         * HDR classification uses the transfer function plus codec hints where
         * available (dvh1/dvhe = Dolby Vision, hvc1/hev1 with ST2084 = HDR10,
         * HLG transfer = HLG).
         */
        fun from(colorInfo: ColorInfo?, codec: String? = null): ColorDescription {
            if (colorInfo == null) {
                return ColorDescription(
                    space = null, range = null, transfer = null, hdrType = "SDR",
                    lumaBitDepth = 0, chromaBitDepth = 0,
                    rawSpace = -1, rawRange = -1, rawTransfer = -1,
                )
            }
            val space = when (colorInfo.colorSpace) {
                C.COLOR_SPACE_BT601 -> "BT601"
                C.COLOR_SPACE_BT709 -> "BT709"
                C.COLOR_SPACE_BT2020 -> "BT2020"
                else -> null
            }
            val range = when (colorInfo.colorRange) {
                C.COLOR_RANGE_LIMITED -> "LIMITED"
                C.COLOR_RANGE_FULL -> "FULL"
                else -> null
            }
            val transfer = when (colorInfo.colorTransfer) {
                C.COLOR_TRANSFER_SDR -> "SDR"
                C.COLOR_TRANSFER_SRGB -> "SRGB"
                C.COLOR_TRANSFER_ST2084 -> "ST2084"
                C.COLOR_TRANSFER_HLG -> "HLG"
                C.COLOR_TRANSFER_LINEAR -> "LINEAR"
                else -> null
            }
            val hdrType = classifyHdr(transfer, colorInfo, codec)
            return ColorDescription(
                space = space,
                range = range,
                transfer = transfer,
                hdrType = hdrType,
                lumaBitDepth = colorInfo.lumaBitdepth,
                chromaBitDepth = colorInfo.chromaBitdepth,
                rawSpace = colorInfo.colorSpace,
                rawRange = colorInfo.colorRange,
                rawTransfer = colorInfo.colorTransfer,
            )
        }

        private fun classifyHdr(
            transfer: String?,
            colorInfo: ColorInfo?,
            codec: String?,
        ): String {
            val transferStr = transfer ?: "SDR"
            val codecLower = codec?.lowercase() ?: ""
            val hdrStaticInfo = colorInfo?.hdrStaticInfo
            return when {
                transferStr == "ST2084" && (codecLower.contains("dvh1") || codecLower.contains("dvhe")) -> "DOLBY_VISION"
                transferStr == "ST2084" && (codecLower.contains("hvc1") || codecLower.contains("hev1")) -> "HDR10"
                hdrStaticInfo != null && transferStr == "ST2084" -> "HDR10"
                transferStr == "HLG" -> "HLG"
                transferStr == "ST2084" -> "HDR10_PLUS"
                else -> "SDR"
            }
        }
    }
}
