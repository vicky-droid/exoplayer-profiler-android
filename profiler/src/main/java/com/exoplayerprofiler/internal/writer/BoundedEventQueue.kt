package com.exoplayerprofiler.internal.writer

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A lock-free, bounded, drop-oldest event queue for the playback profiler.
 *
 * Collectors (player thread, etc.) call [offer] from any thread. The writer
 * thread drains via [drainAll]. On overflow the oldest events are dropped and
 * [droppedCount] is incremented so the session header can report it.
 */
internal class BoundedEventQueue(private val capacity: Int) {

  private val queue = ConcurrentLinkedQueue<Any>()
  private val size = AtomicInteger(0)
  val droppedCount = AtomicInteger(0)

  /**
   * Offers [event]. Returns true if accepted, false if the queue is full and
   * the event was dropped (oldest evicted to make room).
   */
  fun offer(event: Any): Boolean {
    if (size.get() >= capacity) {
      // Drop the oldest event to make room for the new one.
      val dropped = queue.poll()
      if (dropped != null) {
        size.decrementAndGet()
        droppedCount.incrementAndGet()
      }
    }
    queue.offer(event)
    size.incrementAndGet()
    return true
  }

  /**
   * Offers a control item (such as a session-end marker) that must never be evicted: losing one
   * would merge two sessions' events into one file. It is not counted as a dropped event.
   * Control items are rare (one per session), so letting the queue briefly exceed [capacity] is safe.
   */
  fun offerControl(item: Any) {
    queue.offer(item)
    size.incrementAndGet()
  }

  /** Drains all currently queued events into [buffer]. */
  fun drainAll(buffer: MutableList<Any>) {
    var drained = 0
    while (true) {
      val event = queue.poll()
      if (event == null) break
      buffer.add(event)
      drained++
    }
    size.addAndGet(-drained)
  }

  val currentSize: Int
    get() = size.get()
}
