package com.jobcrawler.app

import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/**
 * The `applications` table: every Apply tap and every CV email, kept apart
 * from items (which are pruned) so an address emailed stays remembered.
 * The schema and its migrations stay in [Store].
 */
class ApplicationsDao internal constructor(private val helper: SQLiteOpenHelper) {
    fun record(item: Item, channel: String, address: String? = null) {
        helper.writableDatabase.execSQL(
            "INSERT INTO applications (source, external_id, title, company, channel, address, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(item.source, item.externalId, item.title, item.company, channel, address, now()),
        )
    }

    /** The latest email outcome for a job, as (channel, address), or null when none yet. */
    fun emailOutcome(item: Item): Pair<String, String?>? =
        helper.readableDatabase.rawQuery(
            "SELECT channel, address FROM applications WHERE source = ? AND external_id = ? AND channel LIKE 'email%' " +
                "ORDER BY id DESC LIMIT 1",
            arrayOf(item.source, item.externalId),
        ).use { if (it.moveToFirst()) it.getString(0) to (if (it.isNull(1)) null else it.getString(1)) else null }

    /** One email outcome for a job from a source that emails on its own (Saudi & UAE). */
    data class AutoEmail(
        val source: String,
        val externalId: String,
        val title: String?,
        val company: String?,
        val channel: String,
        val address: String?,
        val createdAt: String,
    )

    /** What the sources in [sources] emailed, skipped or had refused, newest first. */
    fun autoEmails(sources: Collection<String>, limit: Int = 100): List<AutoEmail> {
        if (sources.isEmpty()) return emptyList()
        val marks = sources.joinToString(",") { "?" }
        return helper.readableDatabase.rawQuery(
            "SELECT source, external_id, title, company, channel, address, created_at FROM applications " +
                "WHERE source IN ($marks) AND channel LIKE 'email%' AND channel != 'email-ignored' ORDER BY id DESC LIMIT ?",
            (sources.toList() + limit.toString()).toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(AutoEmail(
                        source = c.getString(0),
                        externalId = c.getString(1),
                        title = if (c.isNull(2)) null else c.getString(2),
                        company = if (c.isNull(3)) null else c.getString(3),
                        channel = c.getString(4),
                        address = if (c.isNull(5)) null else c.getString(5),
                        createdAt = c.getString(6),
                    ))
                }
            }
        }
    }

    /** CVs the sources in [sources] emailed since [since]. */
    fun autoSentSince(sources: Collection<String>, since: Instant): Int {
        if (sources.isEmpty()) return 0
        val marks = sources.joinToString(",") { "?" }
        return helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM applications WHERE source IN ($marks) AND channel = 'email' AND created_at >= ?",
            (sources.toList() + since.toString()).toTypedArray(),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Applications by channel, all time or since [since]. */
    fun counts(since: Instant? = null): Map<String, Int> =
        helper.readableDatabase.rawQuery(
            "SELECT channel, COUNT(*) FROM applications" + (if (since != null) " WHERE created_at >= ?" else "") + " GROUP BY channel",
            since?.let { arrayOf(it.toString()) },
        ).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
        }

    private fun now() = Instant.now().toString()
}
