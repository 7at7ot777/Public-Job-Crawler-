package com.jobcrawler.app

import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/** The `runs` table: one row per finished source run, for the breakdowns and the breakage check. */
class RunsDao internal constructor(private val helper: SQLiteOpenHelper) {
    /** One finished source run, for the breakdown, the weekly summary and the breakage check. */
    fun record(run: RunRow) {
        helper.writableDatabase.execSQL(
            "INSERT INTO runs (source, ran_at, parsed, matched, stored, sent, seeded, rejected_json, blocked, error) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                run.source, run.ranAt.toString(), run.parsed, run.matched, run.stored, run.sent, run.seeded,
                RunHistory.toJson(run.rejected), if (run.blocked) 1 else 0, run.error?.take(500),
            ),
        )
    }

    /** Runs since [since], oldest first; one source's when [source] is set. */
    fun since(since: Instant, source: String? = null): List<RunRow> =
        helper.readableDatabase.rawQuery(
            "SELECT source, ran_at, parsed, matched, stored, sent, seeded, rejected_json, blocked, error FROM runs " +
                "WHERE ran_at >= ?" + (if (source != null) " AND source = ?" else "") + " ORDER BY ran_at ASC",
            if (source != null) arrayOf(since.toString(), source) else arrayOf(since.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(RunRow(
                        source = c.getString(0),
                        ranAt = Instant.parse(c.getString(1)),
                        parsed = c.getInt(2),
                        matched = c.getInt(3),
                        stored = c.getInt(4),
                        sent = c.getInt(5),
                        seeded = c.getInt(6),
                        rejected = RunHistory.parseRejected(if (c.isNull(7)) null else c.getString(7)),
                        blocked = c.getInt(8) != 0,
                        error = if (c.isNull(9)) null else c.getString(9),
                    ))
                }
            }
        }

}
