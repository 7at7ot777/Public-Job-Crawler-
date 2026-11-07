package com.jobcrawler.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * LinkedIn's memory of being told to stop, shared by every LinkedIn source
 * and kept across runs. LinkedIn's logged-out job search answers too many
 * requests from one IP with HTTP 429 and keeps that IP in a penalty window;
 * each new run used to walk straight back into it with a fresh client, and
 * every retry stretched the penalty. Now one 429 rests LinkedIn: 30 minutes,
 * doubling while it keeps happening (up to 6 hours), shrinking after clean
 * runs. On top of that, an hourly cap keeps the request rate below the
 * throttle in the first place.
 */
object LinkedInRest {
    private const val UNTIL = "linkedin.restUntil"
    private const val PENALTY = "linkedin.penaltyMinutes"
    private const val REASON = "linkedin.restReason"
    private const val HOUR = "linkedin.hour"
    private const val COUNT = "linkedin.hourCount"
    private const val WORLDWIDE_HOUR = "linkedin.worldwide.hour"
    private const val WORLDWIDE_COUNT = "linkedin.worldwide.hourCount"

    const val FIRST_REST_MINUTES = 30L
    const val MAX_REST_MINUTES = 360L

    /*
     * Requests to LinkedIn an hour, every LinkedIn source together. Kept at 150:
     * LinkedIn jobs runs every 3 minutes (9 searches a run), which would be ~180
     * an hour, so the cap is reached after ~16 runs (~48 min) and the source
     * rests the rest of the hour rather than pushing past a safe rate. That holds
     * the average near 2.5 a minute, under the 4 a minute the server crawler takes
     * without a 429; one 429 still rests every source.
     */
    const val HOURLY_CAP = 150

    /** When LinkedIn may be asked again, or null when it may be now. */
    @Synchronized
    fun restingUntil(store: StateStore, now: Long = System.currentTimeMillis()): Long? =
        store.getState(UNTIL)?.toLongOrNull()?.takeIf { it > now }

    /*
     * Which "stop" rests every LinkedIn source. A 429 is LinkedIn's per-IP
     * rate limit, whichever endpoint earned it, so it rests them all. A 999
     * on the job search API is the same limit in LinkedIn's own code. But a
     * post page (linkedin.com/posts/...) answers a logged-out visitor 999 or
     * 403 as a login wall all the time; that used to rest the job search for
     * 30 minutes to 6 hours although the job API was fine. Now it only stops
     * opening posts for that run.
     */
    fun restsOn(status: Int?, jobSearch: Boolean): Boolean =
        status == 429 || (jobSearch && status == 999)

    /**
     * After a [client] said "stop": rests LinkedIn when [restsOn] says so, and
     * logs the real status either way. True when it rested.
     */
    fun after(client: GuestClient, store: StateStore, source: String, jobSearch: Boolean): Boolean {
        val status = client.throttleStatus
        if (!restsOn(status, jobSearch)) {
            Log.warn("$source: LinkedIn answered HTTP $status; this run stops, the other LinkedIn searches do not rest")
            return false
        }
        throttled(store, source, status)
        return true
    }

    /** Who started the current rest and with what, e.g. "LinkedIn posts, HTTP 429"; null when unknown. */
    fun reason(store: StateStore): String? = store.getState(REASON)?.ifEmpty { null }

    /** "until 09:19 (LinkedIn posts, HTTP 429)", for the log and the home screen. */
    fun describe(store: StateStore, until: Long): String =
        "until ${clock(until)}" + (reason(store)?.let { " ($it)" } ?: "")

    /** A 429: rest, longer each time it happens again soon after the last rest. */
    @Synchronized
    fun throttled(
        store: StateStore,
        source: String = "LinkedIn",
        status: Int? = 429,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val last = store.getState(PENALTY)?.toLongOrNull()
        val lastUntil = store.getState(UNTIL)?.toLongOrNull() ?: 0L
        // Throttled again within a day of the last rest: LinkedIn still remembers this IP.
        val minutes = if (last != null && now - lastUntil < 24 * 3_600_000L) minOf(last * 2, MAX_REST_MINUTES) else FIRST_REST_MINUTES
        val until = now + minutes * 60_000L
        store.setState(PENALTY, minutes.toString())
        store.setState(UNTIL, until.toString())
        store.setState(REASON, "$source, HTTP ${status ?: "?"}")
        Log.warn("$source throttled (HTTP ${status ?: "?"}): LinkedIn rests until ${clock(until)} (${minutes} min), every LinkedIn search waits")
        return until
    }

    /** A run with no 429: the next rest, if any, starts shorter. */
    @Synchronized
    fun clean(store: StateStore) {
        val last = store.getState(PENALTY)?.toLongOrNull() ?: return
        val next = last / 2
        store.setState(PENALTY, if (next < FIRST_REST_MINUTES) "" else next.toString())
    }

    /** Counts one request against the hour; false when this hour's share is used up. */
    @Synchronized
    fun take(store: StateStore, now: Long = System.currentTimeMillis()): Boolean {
        val hour = (now / 3_600_000L).toString()
        val count = if (store.getState(HOUR) == hour) store.getState(COUNT)?.toIntOrNull() ?: 0 else 0
        if (count >= HOURLY_CAP) return false
        store.setState(HOUR, hour)
        store.setState(COUNT, (count + 1).toString())
        return true
    }

    /*
     * The worldwide search's own share (sources/LinkedInWorldwide.kt): 9
     * searches and up to 8 job pages an hour. Counted apart from
     * [HOURLY_CAP], so it can never use the Egypt search's requests.
     */
    const val WORLDWIDE_HOURLY_CAP = 17

    /** Counts one worldwide request against its own hour; false when that share is used up. */
    @Synchronized
    fun takeWorldwide(store: StateStore, now: Long = System.currentTimeMillis()): Boolean {
        val hour = (now / 3_600_000L).toString()
        val count = if (store.getState(WORLDWIDE_HOUR) == hour) store.getState(WORLDWIDE_COUNT)?.toIntOrNull() ?: 0 else 0
        if (count >= WORLDWIDE_HOURLY_CAP) return false
        store.setState(WORLDWIDE_HOUR, hour)
        store.setState(WORLDWIDE_COUNT, (count + 1).toString())
        return true
    }

    fun clock(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
}
