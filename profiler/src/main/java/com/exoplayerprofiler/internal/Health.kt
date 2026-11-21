package com.exoplayerprofiler.internal

import android.util.Log
import com.exoplayerprofiler.internal.model.HealthSnapshot

/**
 * Counts failures the profiler swallows so they show up in the session header (plan 1.12).
 *
 * The counts are for the whole process, not one session. Each failing site is logged once per process,
 * so a failure that repeats for every event does not flood the log. [firstError] keeps the first failure
 * seen anywhere, with its site.
 */
internal object Health {

    enum class Kind { SINK, RECOVERY, OTHER }

    private val lock = Any()
    private var swallowed = 0
    private var sinkErrors = 0
    private var recoveryErrors = 0
    private var firstError: String? = null
    private val loggedSites = HashSet<String>()

    /** Records one swallowed [error] raised at [site]. */
    fun record(site: String, error: Exception, kind: Kind = Kind.OTHER) {
        synchronized(lock) {
            swallowed++
            when (kind) {
                Kind.SINK -> sinkErrors++
                Kind.RECOVERY -> recoveryErrors++
                Kind.OTHER -> Unit
            }
            if (firstError == null) firstError = "$site: ${error.javaClass.simpleName}"
            if (loggedSites.add(site)) {
                Log.w("PlaybackProfiler", "$site failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun snapshot(): HealthSnapshot = synchronized(lock) {
        HealthSnapshot(swallowed, sinkErrors, recoveryErrors, firstError)
    }

    /** Clears every count. For tests only. */
    internal fun resetForTest() {
        synchronized(lock) {
            swallowed = 0
            sinkErrors = 0
            recoveryErrors = 0
            firstError = null
            loggedSites.clear()
        }
    }
}
