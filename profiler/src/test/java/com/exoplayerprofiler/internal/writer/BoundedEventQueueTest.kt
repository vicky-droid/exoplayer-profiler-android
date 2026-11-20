package com.exoplayerprofiler.internal.writer

import com.exoplayerprofiler.internal.model.ContentKind
import com.exoplayerprofiler.internal.model.LoadStartedV3
import com.exoplayerprofiler.internal.model.SessionStartedV3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedEventQueueTest {

  private fun loadStarted(name: String) = LoadStartedV3(
    tMs = 1L, wallMs = 1L, thread = "main", contentKind = ContentKind.CONTENT,
    uriRedacted = name, uriHash = name, dataType = "MEDIA",
  )

  private fun sessionStarted(id: String) = SessionStartedV3(
    tMs = 0L, wallMs = 0L, thread = "main", contentKind = ContentKind.UNKNOWN,
    device = "test device", sessionId = id,
  )

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
    assertEquals(1, queue.droppedCount)
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
    assertEquals(2, queue.droppedCount)
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

  // --- plan 1.5: priority eviction ---------------------------------------------

  @Test
  fun `low priority events are evicted before normal ones`() {
    val queue = BoundedEventQueue(3)
    queue.offer("n1")
    queue.offer(loadStarted("l1")) // LOW
    queue.offer("n2")
    queue.offer("n3") // full: evicts the LOW event, keeps n1

    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertEquals(listOf<Any>("n1", "n2", "n3"), buffer)
    assertEquals(1, queue.droppedByCategory()["NETWORK"])
  }

  @Test
  fun `a new low priority event is dropped rather than evicting a normal one`() {
    val queue = BoundedEventQueue(2)
    queue.offer("n1")
    queue.offer("n2")
    val accepted = queue.offer(loadStarted("l1"))

    assertFalse(accepted)
    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertEquals(listOf<Any>("n1", "n2"), buffer)
    assertEquals(1, queue.droppedByCategory()["NETWORK"])
  }

  @Test
  fun `start and end markers at head and middle survive a flood of low priority events`() {
    val queue = BoundedEventQueue(8)
    queue.offerControl("START") // head
    repeat(4) { queue.offer("n$it") }
    queue.offerControl("END1") // middle
    repeat(20) { queue.offer(loadStarted("x$it")) }

    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertTrue(buffer.contains("START"))
    assertTrue(buffer.contains("END1"))
    assertEquals(8, buffer.size)
    // 20 low events into 2 free slots: the first 2 fit, then each of the other 18 evicts an older low event.
    assertEquals(18, queue.droppedCount)
    assertEquals(18, queue.droppedByCategory()["NETWORK"])
  }

  @Test
  fun `session start is protected from normal events`() {
    val queue = BoundedEventQueue(2)
    queue.offerControl(sessionStarted("s1")) // PROTECTED via the SESSION category
    queue.offer("n1")
    queue.offer("n2") // evicts n1, never the session start

    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    assertTrue(buffer.any { it is SessionStartedV3 })
    assertEquals(2, buffer.size)
  }

  @Test
  fun `drops per category are reported separately`() {
    val queue = BoundedEventQueue(1)
    queue.offer(loadStarted("a"))
    queue.offer("normal") // evicts the LOW event "a": NETWORK drop 1
    queue.offer(loadStarted("b")) // full of a NORMAL item, so the new LOW event is dropped: NETWORK drop 2
    val drops = queue.droppedByCategory()
    assertEquals(2, drops["NETWORK"])
    assertEquals(null, drops["other"])
  }

  @Test
  fun `four producers under overflow keep every offer accounted for`() {
    val capacity = 100
    val perProducer = 5_000
    val queue = BoundedEventQueue(capacity)
    val accepted = java.util.concurrent.atomic.AtomicInteger(0)
    val threads = (0 until 4).map { p ->
      Thread {
        repeat(perProducer) { i ->
          // Mix priorities so both eviction paths run under contention.
          val item = if (i % 3 == 0) loadStarted("p$p-$i") else "p$p-$i"
          if (queue.offer(item)) accepted.incrementAndGet()
        }
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    val buffer = mutableListOf<Any>()
    queue.drainAll(buffer)
    // Every offer is either still queued or counted as dropped (rejected on arrival, or evicted later).
    assertEquals(4 * perProducer, buffer.size + queue.droppedCount)
    // An accepted offer that was evicted later is both accepted and dropped.
    assertTrue(accepted.get() >= buffer.size)
    assertTrue(buffer.size <= capacity)
    assertEquals(queue.droppedCount, queue.droppedByCategory().values.sum())
  }
}
