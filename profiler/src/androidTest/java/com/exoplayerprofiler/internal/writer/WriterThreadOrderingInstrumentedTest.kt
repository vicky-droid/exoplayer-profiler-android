package com.exoplayerprofiler.internal.writer

import com.exoplayerprofiler.internal.model.ContentKind
import com.exoplayerprofiler.internal.model.PlaybackClickV3
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.sink.EventSink
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Regression test for session attribution. Before the fix, a session end was a separate message, so
 * the writer could drain the next session's events into the current session's file. Every event
 * offered before an end marker must be dispatched before that marker, and no event after it.
 */
@RunWith(AndroidJUnit4::class)
class WriterThreadOrderingInstrumentedTest {

  private val log = CopyOnWriteArrayList<String>()
  private var writer: WriterThread? = null

  private val recorder = object : EventSink {
    override fun onEvent(event: SchemaV3Event) { log.add("E:${event.tMs}") }
    override fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?) {
      log.add("END:$sessionId")
    }
    override fun close() {}
  }

  @After
  fun tearDown() {
    writer?.close()
    WriterThread.resetInstance()
  }

  private fun click(t: Long) = PlaybackClickV3(tMs = t, wallMs = 1000L + t, thread = "test", contentKind = ContentKind.CONTENT)

  @Test
  fun sessionEndsSeparateEventsIntoTheirOwnSessions() {
    WriterThread.resetInstance()
    val sinks = CopyOnWriteArrayList<EventSink>().apply { add(recorder) }
    val wt = WriterThread.getOrCreate("OrderingTestWriter", 4096, sinks)
    writer = wt

    // Offer everything synchronously so both sessions are queued before the writer runs.
    wt.offer(click(1)); wt.offer(click(2))
    wt.onSessionEnded("s1", 2, null)
    wt.offer(click(3)); wt.offer(click(4)); wt.offer(click(5))
    wt.onSessionEnded("s2", 3, null)
    wt.close()
    Thread.sleep(0)
    wt.join(2_000)

    assertEquals(
      listOf("E:1", "E:2", "END:s1", "E:3", "E:4", "E:5", "END:s2"),
      log.toList(),
    )
  }
}
