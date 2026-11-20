package com.exoplayerprofiler.internal.writer

import com.exoplayerprofiler.internal.model.SchemaV3Event

/**
 * A bounded event queue for the playback profiler. Collectors (player thread, etc.) call [offer] or
 * [offerControl] from any thread; the writer thread drains via [drainAll].
 *
 * On overflow the queue evicts by priority (plan 1.5):
 *  - PROTECTED items are never evicted: session start and end, player errors, the session category,
 *    and control items. Losing one would merge sessions or hide a failure.
 *  - LOW items (chunk loads, bandwidth estimates, successful network loads) are evicted first, oldest first.
 *  - NORMAL items are evicted only when no LOW item is queued. A new LOW item never evicts a NORMAL one;
 *    if nothing LOW can be evicted, the new LOW item is dropped.
 *
 * Every drop is counted in [droppedCount] and per category in [droppedByCategory].
 */
internal class BoundedEventQueue(private val capacity: Int) {

  private enum class Priority { PROTECTED, NORMAL, LOW }

  private class Entry(val item: Any, val priority: Priority, val category: String)

  private val lock = Any()
  // Guarded by [lock]. A deque with a lock keeps eviction by priority simple and fast.
  private val queue = ArrayDeque<Entry>()
  private val drops = HashMap<String, Int>()

  /** Total events dropped for overflow since creation. Control items are never counted here. */
  @Volatile
  var droppedCount: Int = 0
    private set

  /**
   * Offers [event]. Returns true if it was queued, false if it was dropped because the queue is full
   * and nothing lower in priority could be evicted for it.
   */
  fun offer(event: Any): Boolean = synchronized(lock) {
    val priority = priorityOf(event)
    val category = categoryOf(event)
    if (queue.size >= capacity && !makeRoom(priority, category)) {
      return@synchronized false
    }
    queue.addLast(Entry(event, priority, category))
    true
  }

  /**
   * Offers a control item (such as a session-end marker) that is never evicted: losing one would merge
   * two sessions' events into one file. Control items are rare (one per session), so the queue may
   * briefly exceed [capacity] because of them.
   */
  fun offerControl(item: Any) {
    synchronized(lock) {
      queue.addLast(Entry(item, Priority.PROTECTED, "control"))
    }
  }

  /** Drains all currently queued items into [buffer], in arrival order. */
  fun drainAll(buffer: MutableList<Any>) {
    synchronized(lock) {
      for (entry in queue) buffer.add(entry.item)
      queue.clear()
    }
  }

  /** Overflow drops per event category since creation. */
  fun droppedByCategory(): Map<String, Int> = synchronized(lock) { HashMap(drops) }

  val currentSize: Int
    get() = synchronized(lock) { queue.size }

  /**
   * Frees one slot for an incoming item of [incoming] priority. Returns true if the item may be queued.
   * Must be called with [lock] held.
   */
  private fun makeRoom(incoming: Priority, incomingCategory: String): Boolean {
    val victim = findOldest(Priority.LOW)
      ?: if (incoming == Priority.LOW) null else findOldest(Priority.NORMAL)
    if (victim != null) {
      queue.remove(victim)
      recordDrop(victim.category)
      return true
    }
    if (incoming == Priority.LOW) {
      recordDrop(incomingCategory)
      return false
    }
    // Only protected items are queued and the incoming item is not low: keep it, above capacity.
    return true
  }

  /** Oldest entry with [wanted] priority, or null. Must be called with [lock] held. */
  private fun findOldest(wanted: Priority): Entry? = queue.firstOrNull { it.priority == wanted }

  /** Must be called with [lock] held. */
  private fun recordDrop(category: String) {
    droppedCount++
    drops[category] = (drops[category] ?: 0) + 1
  }

  private fun priorityOf(item: Any): Priority {
    if (item is SchemaV3Event) {
      val category = item.category
      val name = item.event
      return when {
        category == "SESSION" || name == "PlayerError" -> Priority.PROTECTED
        category == "CHUNK" || category == "BANDWIDTH" -> Priority.LOW
        category == "NETWORK" && (name == "LoadStarted" || name == "LoadCompleted") -> Priority.LOW
        else -> Priority.NORMAL
      }
    }
    return Priority.NORMAL
  }

  private fun categoryOf(item: Any): String = (item as? SchemaV3Event)?.category ?: "other"
}
