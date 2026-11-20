package com.exoplayerprofiler.internal.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CapabilityCacheTest {

  @Test
  fun `capabilities are computed once for many sessions`() {
    val cache = CapabilityCache()
    var computed = 0
    val first = cache.getOrCompute { computed++; mapOf("decoders" to "x") }
    repeat(49) { cache.getOrCompute { computed++; mapOf("decoders" to "y") } }

    assertEquals(1, computed)
    assertSame(first, cache.getOrCompute { error("must not run again") })
  }
}
