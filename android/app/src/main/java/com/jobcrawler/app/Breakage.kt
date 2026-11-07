package com.jobcrawler.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A source that answers fine but parses nothing, run after run, has usually
 * been broken by a change on the site, and says nothing about it. This
 * notices: a source that used to find jobs, now silent [STREAK] clean runs in
 * a row. A block or an error is not silence; those are reported already.
 *
 * Nor is a run that listed jobs and dropped every one itself (seen before,
 * too old, wrong title) before handing any back: the parser worked, there
 * was just nothing new. Only a source that says so ([com.jobcrawler.app.sources.Source.listed]) can be
 * told apart; one that does not is judged on what it handed back, as before.
 */
object Breakage {
    const val STREAK = 3
    const val LOOKBACK_DAYS = 14L
    const val MIN_YIELDING = 3

    /** Whether [history] has enough runs that found something to make silence suspicious. */
    fun yieldsHistorically(history: List<RunRow>): Boolean =
        history.count { !it.blocked && it.error == null && it.parsed > 0 } >= MIN_YIELDING

    /**
     * Whether a run looks broken: it ended clean, handed back nothing, and
     * listed nothing either. [listed] null (the source does not say) counts
     * as nothing listed. Only such a run keeps a snapshot or grows the streak.
     */
    fun silent(parsed: Int, listed: Int?, blocked: Boolean, error: String?): Boolean =
        !blocked && error == null && parsed == 0 && (listed ?: 0) == 0

    /**
     * The next streak of silent runs; null leaves it as it was (a block or an
     * error says nothing either way). A run that listed jobs proves the
     * parser works, so it ends the streak like a run that parsed some.
     */
    fun nextStreak(previous: Int, parsed: Int, blocked: Boolean, error: String?, yields: Boolean, listed: Int? = null): Int? = when {
        blocked || error != null -> null
        !silent(parsed, listed, blocked, error) -> 0
        !yields -> 0
        else -> previous + 1
    }

    private val day = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.of("Africa/Cairo"))

    fun message(key: String, streak: Int, lastYield: Instant?): String =
        "$key has parsed nothing for $streak runs in a row" +
            (lastYield?.let { ", though it found jobs until ${day.format(it)}" } ?: "") +
            ". The site may have changed; the page it got is saved under Log → Snapshots."
}
