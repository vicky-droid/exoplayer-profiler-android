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
  fun `control items are never evicted and do not count as drops`() {
    val queue = BoundedEventQueue(2)
    queue.offer("a")
    queue.offer("b")
    queue.offerControl("END")
    // Overflowing with ordinary events evicts ordinary events, never the control item.
    queue.offer("c")
    queue.offer("d")
    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertTrue(buffer.contains("END"))
    assertEquals(2, queue.droppedCount.get())
  }

  @Test
  fun `control items keep their position relative to events`() {
    val queue = BoundedEventQueue(10)
    queue.offer("e1")
    queue.offerControl("END1")
    queue.offer("e2")
    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertEquals(listOf<Any>("e1", "END1", "e2"), buffer)
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
