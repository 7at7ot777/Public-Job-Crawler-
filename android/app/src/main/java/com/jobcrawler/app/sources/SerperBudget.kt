package com.jobcrawler.app.sources

import com.jobcrawler.app.Alert
import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.StateStore
import java.time.Instant
import java.time.ZoneId

/**
 * What Serper has cost, and the cap that stops it costing more. Ported from
 * src/sources/serperBudget.js; the count is in the state table, one row a day,
 * under the same keys.
 *
 * The key has no spend limit of its own, so a long list of post keywords or
 * places, or a run repeated by hand, could spend the month's credits in a day.
 */
class SerperBudget(
    private val store: StateStore,
    private val dailyCap: Int = DEFAULT_DAILY_CAP,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun today(): String = Instant.ofEpochMilli(now()).atZone(CAIRO).toLocalDate().toString()

    fun used(day: String = today()): Int = store.getState(PREFIX + day)?.toIntOrNull() ?: 0

    /**
     * Counts one paid query, before it is sent: a request that fails was
     * still billed.
     *
     * @throws BlockedException with an alert once the day's cap is reached;
     *         the search stops and what it found is delivered.
     */
    fun spend() = synchronized(LOCK) {
        // Read and write as one step: the posts and Glassdoor workers may spend at the same moment.
        val day = today()
        val used = used(day)
        if (dailyCap in 1..used) {
            throw BlockedException(
                "Serper: the daily cap of $dailyCap searches is reached; the rest waits for tomorrow",
                alert = "serper-cap",
            )
        }
        store.setState(PREFIX + day, (used + 1).toString())
    }

    data class Usage(val today: Int, val month: Int, val dailyCap: Int, val balance: Int?)

    fun usage(): Usage {
        val day = today()
        val days = store.statesWithPrefix(PREFIX + day.take(7))
        return Usage(
            today = used(day),
            month = days.values.sumOf { it.toIntOrNull() ?: 0 },
            dailyCap = dailyCap,
            balance = store.getState(BALANCE)?.toIntOrNull(),
        )
    }

    /**
     * An alert when fewer than [threshold] credits are left, asked at most
     * once a day. [read] fetches the balance (null when it cannot be told);
     * it never decides the run, so any failure is no alert.
     */
    fun dailyBalanceAlert(threshold: Int = LOW_BALANCE, read: () -> Int?): Alert? {
        if (threshold <= 0) return null
        val day = today()
        if (store.getState(BALANCE_DAY) == day) return null
        store.setState(BALANCE_DAY, day)

        val credits = runCatching(read).getOrNull() ?: return null
        store.setState(BALANCE, credits.toString())
        if (credits >= threshold) return null

        return Alert(
            "serper-low-balance",
            "Serper has $credits credits left (alert below $threshold). " +
                "Top up at serper.dev, or the hiring-posts search stops when they run out.",
        )
    }

    companion object {
        /** One count for the whole app, whichever worker spends. */
        private val LOCK = Any()

        private const val PREFIX = "serper.queries."
        private const val BALANCE_DAY = "serper.balance.day"
        private const val BALANCE = "serper.balance"
        private val CAIRO = ZoneId.of("Africa/Cairo")

        /* A daily run of 33 searches, with room for a run by hand. */
        const val DEFAULT_DAILY_CAP = 100
        const val LOW_BALANCE = 300
        const val ACCOUNT_URL = "https://google.serper.dev/account"
    }
}
