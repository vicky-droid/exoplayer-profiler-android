package com.exoplayerprofiler.internal.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedEventQueueTest {

  @Test
  fun `offers and drains in FIFO order`() {
    val queue = BoundedEventQueue(10)
    val events = listOf("a", "b", "c")
    events.forEach { queue.offer(it) }

    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertEquals(events, buffer)
  }

  @Test
  fun `drops oldest when full`() {
    val queue = BoundedEventQueue(3)
    queue.offer("a")
    queue.offer("b")
    queue.offer("c") // full: [a, b, c]
    queue.offer("d") // drops "a": [b, c, d]

    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertEquals(listOf("b", "c", "d"), buffer)
    assertEquals(1, queue.droppedCount.get())
  }

  @Test
  fun `currentSize reflects queued count`() {
    val queue = BoundedEventQueue(10)
    assertEquals(0, queue.currentSize)
    queue.offer("a")
    queue.offer("b")
    assertEquals(2, queue.currentSize)
    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertEquals(0, queue.currentSize)
  }
}
