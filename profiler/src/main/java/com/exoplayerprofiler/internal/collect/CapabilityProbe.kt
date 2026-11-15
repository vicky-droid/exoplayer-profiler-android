package com.exoplayerprofiler.internal.collect

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaDrm
import com.exoplayerprofiler.internal.model.DeviceCapabilitiesV3
import java.util.UUID

/**
 * Probes device capabilities once per session on the writer thread.
 *
 * Collects:
 * - Decoder info from MediaCodecList (name, encoder flag, supported types)
 * - Display HDR support flag (API 26+)
 * - DRM scheme, security level from MediaDrm property reads (API 23+)
 * - Audio sink configuration
 *
 * All property reads are blocking and must run on the writer thread, never
 * on the player thread.
 */
internal class CapabilityProbe(private val context: Context) {

    /**
     * Probes all device capabilities and returns a DeviceCapabilitiesV3 event.
     * Safe to call once per session on the writer thread.
     */
    fun probe(tMs: Long, wallMs: Long, thread: String): DeviceCapabilitiesV3 {
        val caps = mutableMapOf<String, Any>()

        // Decoder capabilities
        caps["decoders"] = probeDecoders()

        // Display HDR capabilities (API 26+)
        caps["display"] = probeDisplay()

        // DRM capabilities (API 23+)
        caps["drm"] = probeDrm()

        // Audio capabilities
        caps["audio"] = probeAudio()

        return DeviceCapabilitiesV3(
            tMs = tMs,
            wallMs = wallMs,
            thread = thread,
            contentKind = "UNKNOWN",
            deviceCapabilities = caps,
        )
    }

    private fun probeDecoders(): Map<String, Any> {
        val decoders = mutableMapOf<String, Any>()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val infos = codecList.codecInfos
        for (info in infos) {
            val name = info.name
            val isEncoder = info.isEncoder
            val supportedTypes = info.supportedTypes.toList()
            decoders[name] = mapOf(
                "isEncoder" to isEncoder,
                "supportedTypes" to supportedTypes,
            )
        }
        return decoders
    }

    private fun probeDisplay(): Map<String, Any> {
        // HDR capabilities (Display.HdrCapabilities) require API 26+, which is
        // above minSdk. Report not supported for now.
        return mapOf("hdrSupported" to false)
    }

    private fun probeDrm(): Map<String, Any> {
        val drmMap = mutableMapOf<String, Any>()
        val schemes = listOf(
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"), // Widevine
            UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95"), // ClearKey
            UUID.fromString("1077efec-c0b2-4d02-ace3-3c1d56e4a00b"), // PlayReady
        )
        for (uuid in schemes) {
            try {
                val drm = MediaDrm(uuid)
                drmMap[uuid.toString()] = mapOf(
                    "securityLevel" to drm.getPropertyString("securityLevel"),
                    "hdcpLevel" to drm.getPropertyString("hdcpLevel"),
                )
            } catch (e: Exception) {
                // DRM scheme not supported on this device
                drmMap[uuid.toString()] = mapOf("supported" to false, "error" to e.message.toString())
            }
        }
        return drmMap
    }

    private fun probeAudio(): Map<String, Any> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return mapOf(
            "mode" to audioManager.mode,
            "microphoneMute" to audioManager.isMicrophoneMute,
            "musicVolume" to audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
            "maxMusicVolume" to audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
        )
    }
}
