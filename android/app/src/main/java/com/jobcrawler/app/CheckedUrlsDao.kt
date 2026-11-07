package com.jobcrawler.app

import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/**
 * The `checked_urls` table: pages a sitemap-driven source has already
 * opened, so a page rejected for its age is not fetched again every run.
 */
class CheckedUrlsDao internal constructor(private val helper: SQLiteOpenHelper) {
    fun mark(source: String, externalId: String) {
        helper.writableDatabase.execSQL(
            // Not ON CONFLICT ... DO UPDATE: that needs SQLite 3.24, which
            // Android only ships from 11, and minSdk is 26 (Android 8).
            "INSERT OR REPLACE INTO checked_urls (source, external_id, checked_at) VALUES (?, ?, ?)",
            arrayOf(source, externalId, now()),
        )
    }

    fun ids(source: String): Set<String> =
        helper.readableDatabase.rawQuery("SELECT external_id FROM checked_urls WHERE source = ?", arrayOf(source)).use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }

    private fun now() = Instant.now().toString()
}
