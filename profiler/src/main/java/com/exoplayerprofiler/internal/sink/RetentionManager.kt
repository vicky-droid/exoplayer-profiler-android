package com.exoplayerprofiler.internal.sink

import java.io.File

/**
 * Manages retention of profiler session files on disk.
 *
 * After each session end, deletes oldest sessions until at most
 * [retentionSessions] sessions and at most [retentionBytes] bytes remain.
 * Never deletes the currently open session or one whose upload is still pending.
 */
internal class RetentionManager(
    private val profilerDir: File,
    private val retentionSessions: Int = 20,
    private val retentionBytes: Long = 50L * 1024 * 1024,
) {

    private val openSessionIds = mutableSetOf<String>()
    private val pendingUploadIds = mutableSetOf<String>()

    /** Marks a session as currently open (should not be deleted). */
    @Synchronized
    fun markOpen(sessionId: String) {
        if (sessionId.isNotEmpty()) {
            openSessionIds.add(sessionId)
        }
    }

    /** Marks a session as no longer open. */
    @Synchronized
    fun markClosed(sessionId: String) {
        openSessionIds.remove(sessionId)
    }

    /** Marks a session as having a pending upload (should not be deleted). */
    @Synchronized
    fun markUploadPending(sessionId: String) {
        if (sessionId.isNotEmpty()) {
            pendingUploadIds.add(sessionId)
        }
    }

    /** Marks a session's upload as complete. */
    @Synchronized
    fun markUploadComplete(sessionId: String) {
        pendingUploadIds.remove(sessionId)
    }

    /**
     * Enforces retention limits. Called after each session end.
     * Returns the list of deleted files.
     */
    @Synchronized
    fun enforce(): List<File> {
        val deleted = mutableListOf<File>()
        if (!profilerDir.exists()) return deleted

        // Gather all session files (ndjson + json + pending), grouped by base session name
        val allFiles = profilerDir.listFiles() ?: return deleted
        val sessionFiles = allFiles
            .filter { it.name.startsWith("session_") && (it.name.endsWith(".ndjson") || it.name.endsWith(".json")) }
            .groupBy { it.name.substringBeforeLast('.') }
            .filter { (baseName, _) -> baseName.isNotEmpty() }

        if (sessionFiles.isEmpty()) return deleted

        // Sort sessions by modification time (oldest first)
        val sortedSessions = sessionFiles.entries
            .sortedBy { (_, files) -> files.maxOfOrNull { it.lastModified() } ?: 0L }

        // Compute total bytes across all files in profilerDir
        var totalBytes = allFiles.sumOf { it.length() }
        var currentSessionCount = sortedSessions.size

        // Delete oldest sessions until within limits, skipping protected sessions
        for ((baseName, files) in sortedSessions) {
            if (totalBytes <= retentionBytes && currentSessionCount <= retentionSessions) {
                break
            }

            if (isSessionProtected(baseName, files)) {
                continue
            }

            var sessionDeleted = false
            for (file in files) {
                val fileLen = file.length()
                if (file.delete()) {
                    deleted.add(file)
                    totalBytes -= fileLen
                    sessionDeleted = true
                }
            }
            // Also clean up any associated .pending file if the session files were deleted
            val pendingFile = File(profilerDir, "$baseName.pending")
            if (pendingFile.exists()) {
                val pLen = pendingFile.length()
                if (pendingFile.delete()) {
                    deleted.add(pendingFile)
                    totalBytes -= pLen
                }
            }
            if (sessionDeleted) {
                currentSessionCount--
            }
        }

        return deleted
    }

    private fun isSessionProtected(baseName: String, files: List<File>): Boolean {
        // Extract id8 from session_<stamp>_<id8>
        val id8 = baseName.substringAfterLast('_')

        val isOpen = openSessionIds.any { id ->
            id == baseName || id == id8 || id.startsWith(id8) || id8.startsWith(id.take(8))
        }
        if (isOpen) return true

        val isPending = pendingUploadIds.any { id ->
            id == baseName || id == id8 || id.startsWith(id8) || id8.startsWith(id.take(8))
        }
        if (isPending) return true

        // Check if a .pending marker exists on disk
        val pendingMarker = File(profilerDir, "$baseName.pending")
        if (pendingMarker.exists()) return true

        return false
    }
}
