package com.exoplayerprofiler.internal.json

import com.exoplayerprofiler.internal.model.ChunkLoadedV3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R11: the raw URI must never reach a redacted sink, and only reaches raw sinks when asked. */
class RawUriExclusionTest {

    private val event = ChunkLoadedV3(
        tMs = 1L, wallMs = 2L, thread = "main", contentKind = "CONTENT",
        chunkId = 1, dataType = "MEDIA", trackType = "VIDEO", trackFormatId = "5",
        uriRedacted = "https://cdn.example.com/<id>/seg.m4s", uriHash = "0123456789ab",
        byteStart = 0, byteLength = -1, mediaStartMs = 0, mediaEndMs = 2000, durationMs = 2000,
        requestStartMs = 10, loadDurationMs = 20, bytesLoaded = 100, throughputKbps = 40,
        httpStatus = 200, cacheHint = null, outcome = "COMPLETED", errorMessage = null,
        gapBeforeMs = null,
        uriRaw = "https://cdn.example.com/abc/seg.m4s?token=SECRET",
    )

    @Test
    fun `redacted output never contains the raw uri or its token`() {
        val json = EventJsonWriter.eventToJson(event)
        assertFalse(json.contains("uriRaw"))
        assertFalse(json.contains("SECRET"))
    }

    @Test
    fun `raw output includes the raw uri only when requested`() {
        val json = EventJsonWriter.eventToJson(event, includeRaw = true)
        assertTrue(json.contains("\"uriRaw\""))
        assertTrue(json.contains("token=SECRET"))
    }
}
