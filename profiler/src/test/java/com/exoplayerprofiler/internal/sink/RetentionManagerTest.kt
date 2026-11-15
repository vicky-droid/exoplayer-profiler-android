package com.exoplayerprofiler.internal.sink

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetentionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Session i is written with an id8 of `abcdef0<i>` and a modification time that grows with i. */
    private fun session(i: Int, bytes: Int = 100): File {
        val file = File(tmp.root, "session_20250101_00000${i}_abcdef0$i.json")
        file.writeText("x".repeat(bytes))
        file.setLastModified(1_000_000L + i * 1_000L)
        return file
    }

    private fun remaining(): List<String> =
        tmp.root.listFiles()!!.map { it.name.substringAfter("_00000").substringBefore(".") }.sorted()

    @Test
    fun `keeps only the newest sessions up to the session limit`() {
        (1..5).forEach { session(it) }
        val deleted = RetentionManager(tmp.root, retentionSessions = 3, retentionBytes = Long.MAX_VALUE).enforce()

        assertEquals(listOf("3", "4", "5"), remaining().map { it.take(1) }.sorted())
        assertEquals(2, deleted.count { it.name.endsWith(".json") })
    }

    @Test
    fun `never deletes an open session, even when it is the oldest`() {
        (1..5).forEach { session(it) }
        val retention = RetentionManager(tmp.root, retentionSessions = 3, retentionBytes = Long.MAX_VALUE)
        retention.markOpen("abcdef01-open")

        retention.enforce()

        val names = tmp.root.list()!!.map { it.substringAfter("_00000").substringBefore(".") }.map { it.take(1) }.sorted()
        assertEquals(listOf("1", "4", "5"), names)
    }

    @Test
    fun `deletes oldest sessions until the byte budget is met`() {
        (1..4).forEach { session(it, bytes = 100) }
        RetentionManager(tmp.root, retentionSessions = 100, retentionBytes = 250L).enforce()

        val total = tmp.root.listFiles()!!.sumOf { it.length() }
        assertEquals(200L, total)
        assertEquals(listOf("3", "4"), tmp.root.list()!!.map { it.substringAfter("_00000").take(1) }.sorted())
    }
}
