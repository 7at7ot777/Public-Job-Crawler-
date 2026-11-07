package com.jobcrawler.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The latest run of each source, kept for the home screen: what each found,
 * and when. Each source runs in its own worker, so every finish merges its
 * own row in ([merge]); the others stay as they were.
 */
data class RunReport(
    val started: Instant,
    val ended: Instant,
    val sources: List<Source>,
    /** Due but never started: the network went away first. Older builds wrote it; kept so their report still reads. */
    val notReached: List<String> = emptyList(),
) {
    data class Source(
        val key: String,
        val parsed: Int,
        val matched: Int,
        val stored: Int,
        val blocked: Boolean,
        val error: String?,
        /** Set when this source was run on its own, by hand, after the rest of the report. */
        val ranAt: Instant? = null,
    )

    companion object {
        private const val PREFS = "status"

        fun save(context: Context, started: Instant, ended: Instant, stats: List<Pipeline.SourceStat>, notReached: List<String> = emptyList()) =
            write(context, RunReport(started, ended, stats.map { it.toSource() }, notReached))

        /**
         * One source's run: its row is replaced, the rest stay. Synchronized,
         * and written with commit(): two workers finishing together must not
         * each write a report missing the other's row.
         */
        @Synchronized
        fun merge(context: Context, started: Instant, ended: Instant, stats: List<Pipeline.SourceStat>, notReached: List<String> = emptyList()) {
            val previous = load(context)
            if (previous == null) {
                save(context, started, ended, stats, notReached)
                return
            }
            write(context, mergeInto(previous, ended, stats, notReached))
        }

        /** [merge] without the storage, for tests. */
        fun mergeInto(previous: RunReport, ended: Instant, stats: List<Pipeline.SourceStat>, notReached: List<String> = emptyList()): RunReport {
            val ran = stats.associate { it.source to it.toSource(ranAt = ended) }
            val keys = ran.keys + notReached
            val sources = previous.sources.map { ran[it.key] ?: it } + ran.values.filter { fresh -> previous.sources.none { it.key == fresh.key } }
            return previous.copy(
                ended = maxOf(previous.ended, ended),
                sources = sources,
                notReached = previous.notReached.filter { it !in keys } + notReached,
            )
        }

        private fun Pipeline.SourceStat.toSource(ranAt: Instant? = null) =
            Source(source, parsed, matched, stored, blocked, error, ranAt)

        private fun write(context: Context, report: RunReport) {
            val sources = JSONArray()
            for (source in report.sources) {
                sources.put(
                    JSONObject()
                        .put("key", source.key)
                        .put("parsed", source.parsed)
                        .put("matched", source.matched)
                        .put("stored", source.stored)
                        .put("blocked", source.blocked)
                        .put("error", source.error ?: JSONObject.NULL)
                        .put("ranAt", source.ranAt?.toString() ?: JSONObject.NULL),
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("report", JSONObject()
                    .put("started", report.started.toString())
                    .put("ended", report.ended.toString())
                    .put("sources", sources)
                    .put("notReached", JSONArray(report.notReached))
                    .toString())
                .commit()
        }

        fun load(context: Context): RunReport? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("report", null) ?: return null
            return parse(raw)
        }

        private fun parse(raw: String): RunReport? = runCatching {
            val json = JSONObject(raw)
            val list = json.getJSONArray("sources")
            val skipped = json.optJSONArray("notReached")
            RunReport(
                started = Instant.parse(json.getString("started")),
                ended = Instant.parse(json.getString("ended")),
                sources = (0 until list.length()).map { i ->
                    val s = list.getJSONObject(i)
                    Source(
                        key = s.getString("key"),
                        parsed = s.optInt("parsed"),
                        matched = s.optInt("matched"),
                        stored = s.optInt("stored"),
                        blocked = s.optBoolean("blocked"),
                        error = if (s.isNull("error")) null else s.optString("error"),
                        ranAt = if (s.isNull("ranAt")) null else runCatching { Instant.parse(s.getString("ranAt")) }.getOrNull(),
                    )
                },
                notReached = skipped?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
            )
        }.getOrNull()
    }
}
