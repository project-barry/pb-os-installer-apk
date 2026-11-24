package org.projectbarry.pbosinstaller.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleWatchTest {
    @Test fun oldIsStopped() {
        val w = StaleWatch(60_000)
        assertFalse(w.stopped(0))
        assertFalse(w.stopped(60_000))
        assertTrue(w.stopped(60_001))
    }

    /** A status from the future that stays there belongs to a job that stopped; one that's freshened doesn't. */
    @Test fun theFutureIsStoppedToo() {
        val w = StaleWatch(60_000)
        repeat(StaleWatch.FUTURE - 1) { assertFalse(w.stopped(-3_600_000)) }
        assertTrue(w.stopped(-3_590_000))
        val set = StaleWatch(60_000)
        repeat(5) { assertFalse(set.stopped(-3_600_000)) }
        assertFalse(set.stopped(100)) // the running job touched it
        repeat(StaleWatch.FUTURE - 1) { assertFalse(set.stopped(-1)) }
    }
}
