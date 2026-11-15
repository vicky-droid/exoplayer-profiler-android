package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader

/**
 * A consumer of profiler events. All sink methods run on the writer thread.
 * Network I/O inside a sink must use its own executor so the writer is never
 * blocked for more than a bounded time.
 */
internal interface EventSink {
    fun onEvent(event: SchemaV3Event)
    fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?)
    fun close()
}
