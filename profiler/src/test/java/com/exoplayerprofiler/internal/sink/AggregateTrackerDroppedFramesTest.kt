package com.exoplayerprofiler.internal.sink

import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateTrackerDroppedFramesTest {

  private fun droppedEvent(tMs: Long, frames: Int) =
    """{"tMs":$tMs,"category":"VIDEO","event":"DroppedFrames","details":{"droppedFrames":$frames}}"""

  @Test
  fun `dropped frame callbacks add up to the session total`() {
    // Media3 reports each callback as the frames dropped since the previous one: 5, then 3, then 10.
    val tracker = AggregateTracker()
    tracker.track("VIDEO", "DroppedFrames", droppedEvent(1_000L, 5))
    tracker.track("VIDEO", "DroppedFrames", droppedEvent(2_000L, 3))
    tracker.track("VIDEO", "DroppedFrames", droppedEvent(3_000L, 10))

    assertEquals(18L, tracker.snapshot().droppedFrames)
  }

  @Test
  fun `a new session starts from zero`() {
    val tracker = AggregateTracker()
    tracker.track("VIDEO", "DroppedFrames", droppedEvent(1_000L, 7))
    tracker.snapshot()
    tracker.reset()
    tracker.track("VIDEO", "DroppedFrames", droppedEvent(1_000L, 2))

    assertEquals(2L, tracker.snapshot().droppedFrames)
  }
}
