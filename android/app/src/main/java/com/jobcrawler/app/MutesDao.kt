package com.jobcrawler.app

import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

/** The `mutes` table: companies and title words the person never wants to see. */
class MutesDao internal constructor(private val helper: SQLiteOpenHelper) {
    fun add(kind: String, value: String, label: String) {
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO mutes (kind, value, label, created_at) VALUES (?, ?, ?, ?)",
            arrayOf(kind, value, label, now()),
        )
    }

    fun remove(kind: String, value: String) {
        helper.writableDatabase.delete("mutes", "kind = ? AND value = ?", arrayOf(kind, value))
    }

    /** Every mute, newest first. */
    fun all(): List<Mutes.Entry> =
        helper.readableDatabase.rawQuery("SELECT kind, value, label, created_at FROM mutes ORDER BY created_at DESC", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = Mutes.Kind.parse(c.getString(0)) ?: continue
                    add(Mutes.Entry(kind, c.getString(1), if (c.isNull(2)) c.getString(1) else c.getString(2), c.getString(3)))
                }
            }
        }

    private fun now() = Instant.now().toString()
}
