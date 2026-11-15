package com.exoplayerprofiler.internal.redact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `user info and query string are removed and the host is kept`() {
        val out = Redactor.redactUri("https://user:secret@cdn.example.com/video/index.mpd?token=abc#frag")
        assertEquals("https://cdn.example.com/video/index.mpd", out)
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("token"))
    }

    @Test
    fun `opaque path segments become id placeholders`() {
        val out = Redactor.redactUri("https://cdn.example.com/content/9f8e7d6c5b4a39281706f5e4d3c2b1a0/master.m3u8")
        assertTrue(out.startsWith("https://cdn.example.com/"))
        assertTrue(out.contains("<id>"))
        assertFalse(out.contains("9f8e7d6c5b4a"))
    }

    @Test
    fun `drm license urls are replaced by a constant`() {
        assertEquals("<drm-license-url>", Redactor.redactDrmUrl("https://license.example.com/w?token=xyz"))
        assertEquals("", Redactor.redactDrmUrl(null))
    }

    @Test
    fun `null or blank uri gives an empty string`() {
        assertEquals("", Redactor.redactUri(null))
        assertEquals("", Redactor.redactUri("   "))
    }
}
