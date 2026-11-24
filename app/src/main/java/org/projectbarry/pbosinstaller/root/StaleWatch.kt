package org.projectbarry.pbosinstaller.root

/**
 * Whether a job that says it's running stopped: its status is older than [limitMs], or from
 * the future for [FUTURE] looks in a row. (The clock can be set back while it runs; a job that
 * still runs freshens its status every few seconds, one that stopped stays in the future.)
 */
class StaleWatch(private val limitMs: Long) {
    private var future = 0

    /** Asked about once a second. */
    fun stopped(ageMs: Long): Boolean {
        future = if (ageMs < 0) future + 1 else 0
        return ageMs > limitMs || future >= FUTURE
    }

    companion object {
        const val FUTURE = 10
    }
}
