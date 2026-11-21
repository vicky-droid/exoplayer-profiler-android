package com.exoplayerprofiler.internal.writer

import com.exoplayerprofiler.internal.Health

import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.sink.EventSink
import java.util.concurrent.CopyOnWriteArrayList

private const val MSG_DRAIN = 1
private const val MSG_CLOSE = 2

/** Longest time [WriterThread.close] waits for pending events and sinks to flush. */
private const val CLOSE_TIMEOUT_MS = 2_000L

/**
 * The single writer thread for the playback profiler.
 *
 * Collectors only enqueue immutable events via [offer] (lock-free, returns
 * immediately). This thread drains the queue and fans out to all sinks. Sinks
 * that do I/O must use their own executor so the writer is never blocked for long.
 */
internal class WriterThread private constructor(
    name: String,
    private val queue: BoundedEventQueue,
    private val sinks: CopyOnWriteArrayList<EventSink>
) : HandlerThread(name), Handler.Callback {

    private var handler: Handler? = null

    init {
        start()
        handler = Handler(looper, this)
    }

    /** Enqueues an event from any collector thread and requests a drain. */
    fun offer(event: SchemaV3Event) {
        queue.offer(event)
        handler?.obtainMessage(MSG_DRAIN)?.sendToTarget()
    }

    /** Posts work to run on the writer thread (used for file I/O such as export). */
    fun post(runnable: Runnable) {
        handler?.post(runnable)
    }

    /** Number of events dropped because the queue overflowed, since the writer started. */
    fun queueDropCount(): Long = queue.droppedCount.toLong()

    /** Overflow drops since the writer started, per event category. */
    fun queueDropCountsByCategory(): Map<String, Int> = queue.droppedByCategory()

    /**
     * Ends a session. The end marker goes into the same ordered queue as the session's events, so
     * the writer finalises the file after exactly the events that were offered before it. A separate
     * message here let the writer pull the next session's events into this session's file.
     */
    fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?) {
        queue.offerControl(SessionEndMarker(sessionId, eventCount, header))
        handler?.obtainMessage(MSG_DRAIN)?.sendToTarget()
    }

    /**
     * Flushes queued events and sinks, then stops the thread. Waits up to
     * [CLOSE_TIMEOUT_MS] so that a caller such as `reset()` returns only after the last
     * session file is written. The shared instance is cleared so the next [getOrCreate]
     * starts a fresh writer instead of posting to a dead one.
     */
    fun close() {
        val h = handler ?: return
        h.sendMessage(h.obtainMessage(MSG_CLOSE))
        synchronized(WriterThread::class.java) {
            if (instance === this) instance = null
        }
        // Joining from the writer thread itself would never return.
        if (Thread.currentThread() !== this) {
            try {
                join(CLOSE_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_DRAIN -> drainAndDispatch()
            MSG_CLOSE -> {
                // Drain what is still queued so no event is lost on teardown.
                drainAndDispatch()
                for (sink in sinks) {
                    try { sink.close() } catch (e: Exception) { Health.record("writer.close", e) }
                }
                quitSafely()
            }
        }
        return true
    }

    private fun drainAndDispatch() {
        val buffer = mutableListOf<Any>()
        queue.drainAll(buffer)
        // Items arrive in the order they were offered, so a session's end marker is handled only
        // after that session's events and before the next session's events.
        for (item in buffer) {
            when (item) {
                is SchemaV3Event -> for (sink in sinks) {
                    try { sink.onEvent(item) } catch (e: Exception) { Health.record("writer.onEvent", e, Health.Kind.SINK) }
                }
                is SessionEndMarker -> for (sink in sinks) {
                    try { sink.onSessionEnded(item.sessionId, item.eventCount, item.header) } catch (e: Exception) { Health.record("writer.onSessionEnded", e, Health.Kind.SINK) }
                }
            }
        }
    }

    private class SessionEndMarker(
        val sessionId: String,
        val eventCount: Int,
        val header: SessionHeader?,
    )

    companion object {
        @Volatile private var instance: WriterThread? = null

        fun getOrCreate(
            name: String = "PlaybackProfilerWriter",
            queueCapacity: Int = 4096,
            sinks: CopyOnWriteArrayList<EventSink> = CopyOnWriteArrayList()
        ): WriterThread {
            return synchronized(WriterThread::class.java) {
                instance?.takeIf { it.isAlive }
                    ?: WriterThread(name, BoundedEventQueue(queueCapacity), sinks).also {
                        instance = it
                    }
            }
        }

        fun resetInstance() {
            instance?.close()
            instance = null
        }
    }
}
