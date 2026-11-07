package com.jobcrawler.app

import java.time.Instant

/**
 * Why jobs were dropped, per source: the last run's reasons and the last
 * seven days', from the run history. The fastest way to tell a filter that
 * is too tight (hundreds dropped for "title") from a source finding little.
 */
object Rejections {
    data class Breakdown(
        val source: String,
        val last: Map<String, Int>,
        val week: Map<String, Int>,
        val lastAt: Instant?,
        val lastParsed: Int,
        val lastMatched: Int,
    ) {
        val weekTotal get() = week.values.sum()
    }

    /** One per source that ran in the window, most dropped first. */
    fun build(runs: List<RunRow>, now: Instant): List<Breakdown> {
        val since = now.minusSeconds(7 * 86_400)
        return runs.filter { !it.ranAt.isBefore(since) }.groupBy { it.source }.map { (source, list) ->
            val newest = list.maxBy { it.ranAt }
            val week = mutableMapOf<String, Int>()
            for (run in list) run.rejected.forEach { (reason, count) -> week.merge(reason, count, Int::plus) }
            Breakdown(source, newest.rejected, week, newest.ranAt, newest.parsed, newest.matched)
        }.sortedByDescending { it.weekTotal }
    }

    /** Every reason across [list], over the seven days, most first. */
    fun totals(list: List<Breakdown>): List<Pair<String, Int>> {
        val all = mutableMapOf<String, Int>()
        for (b in list) b.week.forEach { (reason, count) -> all.merge(reason, count, Int::plus) }
        return sorted(all)
    }

    fun sorted(map: Map<String, Int>): List<Pair<String, Int>> =
        map.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.toPair() }

    /** The filter's reason, as a person reads it. */
    fun describe(reason: String): String = when (reason) {
        "title" -> "Title not on your list"
        "title-excluded" -> "Specialist field (medical, law…)"
        "language" -> "Another language, not Arabic"
        "tech" -> "None of your stack"
        "location" -> "Not in your places"
        "location-blocked" -> "Blocked place"
        "seniority-level" -> "Director or executive"
        "seniority-excluded" -> "Senior title"
        "seniority-excluded-body" -> "Senior in the text"
        "experience" -> "Asks too many years"
        "muted-company" -> "Muted company"
        "muted-title" -> "Muted title word"
        "ineligible" -> "Cannot apply (nationality, visa…)"
        "seeking-work" -> "Someone looking for work"
        "intent-unclear" -> "Not clearly hiring"
        else -> reason
    }
}
