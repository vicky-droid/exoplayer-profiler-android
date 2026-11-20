package com.exoplayerprofiler.internal.collect

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import com.exoplayerprofiler.internal.model.DeviceCapabilitiesV3
import java.util.UUID

/** Holds one computed value for the life of the process. Thread-safe. */
internal class CapabilityCache {
    private val lock = Any()
    private var value: Map<String, Any>? = null

    /** Returns the cached value, computing it with [compute] on the first call only. */
    fun getOrCompute(compute: () -> Map<String, Any>): Map<String, Any> = synchronized(lock) {
        value ?: compute().also { value = it }
    }
}

/**
 * Probes device capabilities for each session on the writer thread (plan 1.7).
 *
 * Collects:
 * - Decoders from MediaCodecList (decoders only: name and supported types)
 * - DRM scheme and security level from MediaDrm property reads (API 23+)
 * - The audio mode
 *
 * The result does not change within a process, so it is computed once and reused by every session.
 * Each MediaDrm instance is released as soon as it is read. Opening one per session without releasing
 * it leaked native DRM sessions.
 *
 * Property reads are blocking and must run on the writer thread, never on the player thread.
 */
internal class CapabilityProbe(
    private val context: Context,
    private val drmReader: (UUID) -> Map<String, Any> = ::readDrm,
) {

    companion object {
        private val DRM_SCHEMES = listOf(
            UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"), // Widevine
            UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95"), // ClearKey
            UUID.fromString("1077efec-c0b2-4d02-ace3-3c1d56e4a00b"), // PlayReady
        )

        /** One cache for the whole process, shared by every probe instance. */
        private val processCache = CapabilityCache()

        /** Releases a MediaDrm on every API level: close() from API 28, release() before. */
        @Suppress("DEPRECATION")
        private fun releaseDrm(drm: MediaDrm) {
            if (Build.VERSION.SDK_INT >= 28) drm.close() else drm.release()
        }

        /** Reads one DRM scheme and always releases the instance, even when a property read throws. */
        internal fun readDrm(uuid: UUID): Map<String, Any> {
            return try {
                val drm = MediaDrm(uuid)
                try {
                    mapOf(
                        "securityLevel" to drm.getPropertyString("securityLevel"),
                        "hdcpLevel" to drm.getPropertyString("hdcpLevel"),
                    )
                } finally {
                    releaseDrm(drm)
                }
            } catch (e: Exception) {
                // Scheme not supported on this device, or the read failed.
                mapOf("supported" to false, "error" to (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /**
     * Returns the device capabilities event for a session. The capability map is computed the first
     * time in this process and reused afterwards.
     */
    fun probe(tMs: Long, wallMs: Long, thread: String): DeviceCapabilitiesV3 {
        val caps = processCache.getOrCompute { collect() }
        return DeviceCapabilitiesV3(
            tMs = tMs,
            wallMs = wallMs,
            thread = thread,
            contentKind = "UNKNOWN",
            deviceCapabilities = caps,
        )
    }

    private fun collect(): Map<String, Any> {
        val caps = mutableMapOf<String, Any>()
        caps["decoders"] = probeDecoders()
        caps["drm"] = probeDrm()
        caps["audio"] = probeAudio()
        return caps
    }

    private fun probeDecoders(): Map<String, Any> {
        val decoders = mutableMapOf<String, Any>()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (info.isEncoder) continue
            decoders[info.name] = mapOf("supportedTypes" to info.supportedTypes.toList())
        }
        return decoders
    }

    private fun probeDrm(): Map<String, Any> {
        val drmMap = mutableMapOf<String, Any>()
        for (uuid in DRM_SCHEMES) {
            drmMap[uuid.toString()] = drmReader(uuid)
        }
        return drmMap
    }

    private fun probeAudio(): Map<String, Any> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return mapOf("mode" to audioManager.mode)
    }
}
