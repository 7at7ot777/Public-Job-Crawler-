package com.jobcrawler.app

import java.io.Closeable
import java.util.concurrent.Semaphore

/**
 * What lets the per-source workers run side by side without upsetting the
 * sites or each other.
 *
 * At most [MAX_PARALLEL] crawl at once (WorkManager's own pool is 2–4
 * threads, so on a small phone it is fewer). Sources that talk to the same
 * remote take turns: LinkedIn's patience is one budget however many sources
 * spend it, and Serper's daily cap is one count. A worker never waits for a
 * slot: it gets one at once or asks WorkManager to retry, because a blocked
 * worker would hold one of WorkManager's few threads doing nothing.
 */
object Lanes {
    const val MAX_PARALLEL = 3

    val slots = Semaphore(MAX_PARALLEL)
    val linkedin = Semaphore(1)
    val serper = Semaphore(1)

    private fun semaphore(lane: Pipeline.Lane) = when (lane) {
        Pipeline.Lane.LINKEDIN -> linkedin
        Pipeline.Lane.SERPER -> serper
    }

    /** The lanes [key] shares (its [Pipeline.Spec.lanes]), always in the enum's order (LinkedIn, then Serper), so two sources never deadlock. */
    private fun lanesOf(key: String): List<Pipeline.Lane> {
        val lanes = Pipeline.spec(key)?.lanes ?: return emptyList()
        return Pipeline.Lane.entries.filter { it in lanes }
    }

    /** The remotes [key] shares, always in the same order. */
    fun of(key: String): List<Semaphore> = lanesOf(key).map(::semaphore)

    /** Which lane names [key] shares, for the log. */
    fun names(key: String): List<String> = lanesOf(key).map { it.label }

    /** A slot and every lane [key] needs, or null with nothing held. */
    fun tryEnter(key: String): Permit? {
        val held = mutableListOf<Semaphore>()
        for (lock in listOf(slots) + of(key)) {
            if (!lock.tryAcquire()) {
                held.asReversed().forEach { it.release() }
                return null
            }
            held += lock
        }
        return Permit(held)
    }

    class Permit internal constructor(private val held: List<Semaphore>) : Closeable {
        private var open = true

        @Synchronized
        override fun close() {
            if (!open) return
            open = false
            held.asReversed().forEach { it.release() }
        }
    }
}

/**
 * One pace for every message to the channel, whichever worker sends it:
 * three workers each waiting [Limits.SEND_DELAY_MS] between their own sends
 * would together break Telegram's per-channel limit.
 */
object SendPace {
    @Volatile
    private var last = 0L

    /** Waits until [gapMs] has passed since the last send anywhere in the app; false when stopped first. */
    fun await(isStopped: () -> Boolean = { false }, gapMs: Long = Limits.SEND_DELAY_MS): Boolean {
        while (true) {
            val wait = synchronized(this) {
                val now = System.currentTimeMillis()
                val due = last + gapMs
                if (now >= due) {
                    last = now
                    return true
                }
                due - now
            }
            if (isStopped()) return false
            Thread.sleep(minOf(wait, 500L).coerceAtLeast(1))
        }
    }

    /** For tests: forget the last send. */
    fun reset() = synchronized(this) { last = 0L }
}

/**
 * What one source run carries that used to be global: whether the network
 * was lost. Two workers no longer share it, so one going offline does not
 * stop the other, and a new run never clears another's state.
 */
class RunContext {
    @Volatile
    var lost = false

    /**
     * Online, as far as the phone can tell, but the source's requests got no
     * answer: a router without internet, DNS down. [lost] waits for a network
     * that is already "there", so this asks for a retry after a delay instead.
     */
    @Volatile
    var retrySoon = false
}
