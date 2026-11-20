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

    // --- plan 1.15: nested custom-event values -------------------------------

    @Test
    fun `nested map drops sensitive keys and redacts urls inside strings`() {
        val input = mapOf("a" to mapOf("token" to "x", "u" to "see https://h/p?sig=1"))
        val out = Redactor.redactCustomEventValue("data", input) as org.json.JSONObject
        val a = out.getJSONObject("a")
        assertFalse(a.has("token"))
        assertEquals("see https://h/p", a.getString("u"))
    }

    @Test
    fun `sensitive top level key is replaced by a marker`() {
        assertEquals("<redacted>", Redactor.redactCustomEventValue("authorization", "Bearer abc"))
    }

    @Test
    fun `lists of maps are walked and sensitive keys dropped in each item`() {
        val input = listOf(mapOf("password" to "p", "name" to "n"))
        val out = Redactor.redactCustomEventValue("items", input) as org.json.JSONArray
        val first = out.getJSONObject(0)
        assertFalse(first.has("password"))
        assertEquals("n", first.getString("name"))
    }

    @Test
    fun `bearer tokens inside nested strings are removed`() {
        val input = mapOf("h" to mapOf("note" to "Authorization: Bearer abc123secret"))
        val out = Redactor.redactCustomEventValue("data", input) as org.json.JSONObject
        assertFalse(out.getJSONObject("h").getString("note").contains("abc123secret"))
    }

    @Test
    fun `nesting deeper than the limit is replaced by a marker`() {
        var deep: Any? = "leaf"
        repeat(12) { deep = mapOf("n" to deep) }
        val out = Redactor.redactCustomEventValue("deep", deep).toString()
        assertTrue(out.contains("<truncated>"))
    }

    @Test
    fun `long strings are capped`() {
        val long = "x".repeat(5_000)
        val out = Redactor.redactCustomEventValue("text", mapOf("body" to long)) as org.json.JSONObject
        assertTrue(out.getString("body").length <= 2_049)
    }
}
