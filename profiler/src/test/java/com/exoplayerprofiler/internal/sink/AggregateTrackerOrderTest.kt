package com.exoplayerprofiler.internal.sink

import com.exoplayerprofiler.internal.model.Aggregates
import org.junit.Assert.assertEquals
import org.junit.Test

class AggregateTrackerOrderTest {

  private data class Ev(val tMs: Long, val category: String, val event: String, val details: String)

  private fun json(e: Ev) =
    """{"tMs":${e.tMs},"category":"${e.category}","event":"${e.event}","details":${e.details}}"""

  private fun state(tMs: Long, state: Int) = Ev(tMs, "PLAYBACK", "StateChanged", """{"state":$state}""")

  /** Startup buffering, two rebuffers (one still open at the end), drops, a chunk and an error. */
  private val session = listOf(
    state(100L, 2),                                                    // startup buffering: not a rebuffer
    state(400L, 3),                                                    // ready
    Ev(500L, "VIDEO", "RenderedFirstFrame", "{}"),
    state(1_000L, 2), state(1_800L, 3),                                // rebuffer 800 ms
    Ev(2_000L, "VIDEO", "DroppedFrames", """{"droppedFrames":4}"""),
    Ev(2_500L, "CHUNK", "Loaded", "{}"),
    state(3_000L, 2), state(3_400L, 3),                                // rebuffer 400 ms
    Ev(3_600L, "VIDEO", "DroppedFrames", """{"droppedFrames":6}"""),
    state(4_000L, 2),                                                  // open at the end
    Ev(4_500L, "ERROR", "Error", "{}"),
  )

  private fun totals(events: List<Ev>): Aggregates {
    val tracker = AggregateTracker()
    for (e in events) tracker.track(e.category, e.event, json(e))
    return tracker.snapshot()
  }

  @Test
  fun `totals are the same whatever order the events arrive in`() {
    val expected = totals(session)

    // Three rebuffers: 1000 to 1800, 3000 to 3400, and 4000 to the last event at 4500 (still open).
    assertEquals(3, expected.rebufferCount)
    assertEquals(800L + 400L + (4_500L - 4_000L), expected.rebufferTotalMs)
    assertEquals(10L, expected.droppedFrames)

    assertEquals(expected, totals(session.reversed()))
    for (seed in 1..20) {
      assertEquals("seed $seed", expected, totals(session.shuffled(java.util.Random(seed.toLong()))))
    }
  }

  @Test
  fun `a late first frame does not turn startup buffering into a rebuffer`() {
    // The first frame arrives last, so its position in arrival order says nothing about the timeline.
    val events = listOf(state(100L, 2), state(400L, 3), state(900L, 2), state(1_000L, 3),
      Ev(1_100L, "VIDEO", "RenderedFirstFrame", "{}"))

    assertEquals(0, totals(events.reversed()).rebufferCount)
    assertEquals(0, totals(events).rebufferCount)
  }
}
