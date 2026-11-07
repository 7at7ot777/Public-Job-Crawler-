package com.jobcrawler.app.sources

import com.jobcrawler.app.StateStore
import org.json.JSONObject

/**
 * The queries the hiring-posts search asks Serper for, and which of them it
 * may skip this run. Ported from src/sources/searchPlan.js.
 *
 * Every query is a paid Serper search, so this is where a run's cost is
 * decided.
 */
object SearchPlan {
    /** One query: [key] is its stable identity, for the yield record. */
    data class Planned(val key: String, val keyword: String, val places: List<String>, val query: String)

    /**
     * Keywords × places as queries. Pairs asks one query per keyword per
     * place; grouped names up to [groupSize] places in one query as an OR
     * group, which Google honours — the same search for a fraction of the
     * queries.
     */
    fun plan(
        template: String,
        keywords: List<String>,
        places: List<String>,
        grouped: Boolean,
        groupSize: Int = GROUP_SIZE,
    ): List<Planned> {
        val groups = if (grouped) places.chunked(groupSize.coerceAtLeast(1)) else places.map { listOf(it) }
        return keywords.flatMap { keyword ->
            groups.map { group ->
                Planned("$keyword|${group.joinToString("+")}", keyword, group, fill(template, keyword, group))
            }
        }
    }

    /** `"{location}"`, quotes included, becomes the OR group, so one template serves both modes. */
    fun fill(template: String, keyword: String, places: List<String>): String {
        if (places.size == 1) return template.replace("{keyword}", keyword).replace("{location}", places[0])

        val group = places.joinToString(" OR ", "(", ")") { "\"$it\"" }
        return template
            .replace("{keyword}", keyword)
            .replace("\"{location}\"", group)
            .replace("{location}", group)
    }

    /**
     * The place a result is about, for the message: the query's own place, or
     * for a grouped one the first of its places the text names, longest first
     * ("New Cairo" before "Cairo"). Null when it names none.
     */
    fun placeFor(planned: Planned, vararg texts: String?): String? {
        if (planned.places.size == 1) return planned.places[0]
        val haystack = texts.filterNotNull().joinToString(" ").lowercase()
        return planned.places.sortedByDescending { it.length }.firstOrNull { haystack.contains(it.lowercase()) }
    }

    const val TEMPLATE = "site:linkedin.com/posts \"{keyword}\" \"{location}\" hiring"
    const val GROUP_SIZE = 6

    /*
     * A query that found no fresh post on COLD_AFTER runs in a row is asked
     * every COLD_EVERY_DAYS days until it finds one. Not dropped: a quiet
     * suburb can start posting.
     */
    const val COLD_AFTER = 5
    const val COLD_EVERY_DAYS = 3
}

/** How each query has been doing, so a cold one is asked less often. */
class YieldLedger(
    private val store: StateStore,
    private val source: String,
    private val coldAfter: Int = SearchPlan.COLD_AFTER,
    private val coldEveryDays: Int = SearchPlan.COLD_EVERY_DAYS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Record(val runs: Int = 0, val misses: Int = 0, val hits: Int = 0, val lastAsked: Long? = null)

    private fun key(planned: SearchPlan.Planned) = "$source.yield.${planned.key}"

    fun read(planned: SearchPlan.Planned): Record = parse(store.getState(key(planned)))

    fun isDue(planned: SearchPlan.Planned): Boolean {
        if (coldAfter <= 0) return true
        val record = read(planned)
        val last = record.lastAsked ?: return true
        if (record.misses < coldAfter) return true
        val since = now() - last
        // A clock set back must not stall the query for good.
        return since < 0 || since >= coldEveryDays * DAY_MS - HOUR_MS
    }

    /** [fresh]: results the query found within the age cut. */
    fun record(planned: SearchPlan.Planned, fresh: Int) {
        val record = read(planned)
        store.setState(
            key(planned),
            JSONObject()
                .put("runs", record.runs + 1)
                .put("misses", if (fresh > 0) 0 else record.misses + 1)
                .put("hits", record.hits + fresh)
                .put("lastAsked", now())
                .toString(),
        )
    }

    /** Every query's record, best first: which ones earn their cost. */
    fun report(): List<Pair<String, Record>> {
        val prefix = "$source.yield."
        return store.statesWithPrefix(prefix)
            .map { (key, value) -> key.removePrefix(prefix) to parse(value) }
            .sortedByDescending { it.second.hits }
    }

    private fun parse(value: String?): Record {
        val json = value?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Record()
        return Record(
            runs = json.optInt("runs"),
            misses = json.optInt("misses"),
            hits = json.optInt("hits"),
            lastAsked = if (json.has("lastAsked")) json.optLong("lastAsked") else null,
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
    }
}
