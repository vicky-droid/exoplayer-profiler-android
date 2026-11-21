package com.exoplayerprofiler.internal

import com.exoplayerprofiler.ProfilerSink
import com.exoplayerprofiler.internal.sink.ProfilerSinkAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HealthTest {

  @Before
  fun reset() = Health.resetForTest()

  @Test
  fun `counts every swallowed failure and sink and recovery failures separately`() {
    Health.record("facade", IllegalStateException("x"))
    Health.record("sink.onEvent", IllegalArgumentException("y"), Health.Kind.SINK)
    Health.record("journal.recovery", RuntimeException("z"), Health.Kind.RECOVERY)

    val snap = Health.snapshot()
    assertEquals(3, snap.swallowed)
    assertEquals(1, snap.sinkErrors)
    assertEquals(1, snap.recoveryErrors)
  }

  @Test
  fun `the first failure seen is kept with its site`() {
    Health.record("facade", IllegalStateException("first"))
    Health.record("sink.onEvent", IllegalArgumentException("second"), Health.Kind.SINK)

    assertEquals("facade: IllegalStateException", Health.snapshot().firstError)
  }

  @Test
  fun `a fresh process has no failures`() {
    val snap = Health.snapshot()
    assertEquals(0, snap.swallowed)
    assertNull(snap.firstError)
  }

  @Test
  fun `a throwing user sink is counted as a sink error and does not stop the others`() {
    val adapter = ProfilerSinkAdapter()
    var delivered = 0
    adapter.add(object : ProfilerSink {
      override fun onEvent(eventJson: String) { throw IllegalStateException("broken sink") }
      override fun onSessionEnded(sessionId: String, sessionJson: String) { throw IllegalStateException("broken") }
    })
    adapter.add(object : ProfilerSink {
      override fun onEvent(eventJson: String) { delivered++ }
      override fun onSessionEnded(sessionId: String, sessionJson: String) { delivered++ }
    })

    adapter.onEventJson("{}")
    adapter.onSessionJson("s", "{}")

    val snap = Health.snapshot()
    assertEquals(2, snap.sinkErrors)
    assertEquals(2, delivered)
    assertEquals("sink.onEvent: IllegalStateException", snap.firstError)
  }
}
