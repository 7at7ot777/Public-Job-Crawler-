package com.jobcrawler.app

import org.json.JSONObject
import java.time.Instant

/** One source run as the `runs` table keeps it. */
data class RunRow(
    val source: String,
    val ranAt: Instant,
    val parsed: Int,
    val matched: Int,
    val stored: Int,
    val sent: Int,
    /** Rows a first run recorded without sending: not finds, so the summaries leave them out. */
    val seeded: Int = 0,
    /** Why the rest were dropped: filter reason → count. */
    val rejected: Map<String, Int> = emptyMap(),
    val blocked: Boolean = false,
    val error: String? = null,
)

/** One stored job, reduced to what the summaries count. */
data class ItemFact(val source: String, val region: Region, val status: String, val score: Int?)

object RunHistory {
    fun of(stat: Pipeline.SourceStat, at: Instant = Instant.now()) = RunRow(
        source = stat.source,
        ranAt = at,
        parsed = stat.parsed,
        matched = stat.matched,
        stored = stat.stored,
        sent = stat.delivered,
        seeded = stat.seeded,
        rejected = stat.rejected.toMap(),
        blocked = stat.blocked,
        error = stat.error,
    )

    fun toJson(rejected: Map<String, Int>): String = JSONObject(rejected as Map<*, *>).toString()

    /** Empty or broken JSON reads as no rejections, never as an error. */
    fun parseRejected(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            buildMap { for (key in obj.keys()) put(key, obj.optInt(key)) }
        }.getOrDefault(emptyMap())
    }
}
