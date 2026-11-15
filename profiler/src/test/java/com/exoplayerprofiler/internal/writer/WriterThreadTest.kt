package com.exoplayerprofiler.internal.writer

import com.exoplayerprofiler.internal.model.ContentKind
import com.exoplayerprofiler.internal.model.PlaybackClickV3
import com.exoplayerprofiler.internal.model.SchemaV3Event
import com.exoplayerprofiler.internal.model.SessionHeader
import com.exoplayerprofiler.internal.sink.EventSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WriterThreadTest {

  @Test
  fun `multi-producer stress test preserves events and drains to sink safely`() {
    val queue = BoundedEventQueue(capacity = 5000)
    val receivedEvents = CopyOnWriteArrayList<SchemaV3Event>()

    val fakeSink = object : EventSink {
      override fun onEvent(event: SchemaV3Event) {
        receivedEvents.add(event)
      }
      override fun onSessionEnded(sessionId: String, eventCount: Int, header: SessionHeader?) {}
      override fun close() {}
    }

    val sinks = CopyOnWriteArrayList<EventSink>().apply { add(fakeSink) }

    val threadCount = 8
    val eventsPerThread = 200
    val totalExpected = threadCount * eventsPerThread
    val latch = CountDownLatch(threadCount)
    val executor = Executors.newFixedThreadPool(threadCount)

    for (t in 0 until threadCount) {
      executor.execute {
        try {
          for (i in 0 until eventsPerThread) {
            val event = PlaybackClickV3(tMs = i.toLong(), wallMs = 1000L + i, thread = "thread-$t", contentKind = ContentKind.CONTENT)
            queue.offer(event)
          }
        } finally {
          latch.countDown()
        }
      }
    }

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    executor.shutdown()

    // Drain the queue to the sinks
    val drained = mutableListOf<Any>()
    queue.drainAll(drained)
    assertEquals(totalExpected, drained.size)

    for (item in drained) {
      if (item is SchemaV3Event) {
        for (sink in sinks) {
          sink.onEvent(item)
        }
      }
    }

    assertEquals(totalExpected, receivedEvents.size)
  }
}
