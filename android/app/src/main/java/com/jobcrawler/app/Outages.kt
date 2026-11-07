package com.jobcrawler.app

import com.jobcrawler.app.sources.Recruitera

/**
 * Runs in a row that the network cut short, per source, and when that is
 * worth a notification: after [ALERT_AFTER] of them, once, and again when the
 * source gets through. A local notification, not a Telegram message: the
 * outage it reports is often the one that would keep the message from going.
 */
object Outages {
    const val ALERT_AFTER = 3

    /** The sources whose outages are counted. */
    val WATCHED = setOf(Recruitera.KEY)

    data class Notice(val title: String, val text: String)

    /**
     * @param failed whether this run was cut short (offline, unanswered, or crashed)
     * @return the notification to post, or null for none
     */
    fun record(store: StateStore, key: String, failed: Boolean): Notice? {
        if (key !in WATCHED) return null

        val count = store.getState(countKey(key))?.toIntOrNull() ?: 0
        val alerted = store.getState(alertedKey(key)) == "1"

        if (!failed) {
            if (count == 0) return null
            store.setState(countKey(key), "0")
            store.setState(alertedKey(key), "0")
            return if (alerted) Notice("$key is running again", "It got through after $count runs the network cut short.") else null
        }

        val failures = count + 1
        store.setState(countKey(key), failures.toString())
        if (failures < ALERT_AFTER || alerted) return null

        store.setState(alertedKey(key), "1")
        return Notice(
            "$key keeps failing",
            "$failures runs in a row were cut short by the network. It retries every ${Scheduler.RETRY_MINUTES} min, " +
                "or as soon as the phone is back online, and tells you when it gets through.",
        )
    }

    private fun countKey(key: String) = "$key.outages"
    private fun alertedKey(key: String) = "$key.outagesAlerted"
}
