package com.jobcrawler.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Every older schema a phone may still have opens and upgrades without
 * losing a row: the dedup history is what keeps the channel from repeating.
 */
@RunWith(RobolectricTestRunner::class)
class StoreMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val path get() = context.getDatabasePath(Store.NAME)

    @Before
    @After
    fun clean() {
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
    }

    @Test
    fun `a version 1 database upgrades to the current schema with its rows`() {
        writeOld(version = 1)
        assertUpgraded()
    }

    @Test
    fun `a version 3 database upgrades to the current schema with its rows`() {
        writeOld(version = 3)
        Store(context).use { store ->
            assertNotNull("v3 applications survive", store.applications.emailOutcome(item("wuzzuf", "w1")))
        }
        assertUpgraded()
    }

    private fun assertUpgraded() = Store(context).use { store ->
        val db = store.writableDatabase
        assertEquals(Store.VERSION, db.version)

        val columns = db.rawQuery("PRAGMA table_info(items)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
        assertTrue(columns.containsAll(listOf("region", "score", "years_min", "years_max", "salary", "feedback")))
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(tables.containsAll(listOf("items", "checked_urls", "state", "applications", "mutes", "runs")))

        // The old rows are all there, and the backfill gave them a region.
        assertEquals(Region.EGYPT, store.findByExternalId("wuzzuf", "w1")!!.region)
        assertEquals(Region.WORLDWIDE, store.findByExternalId("jobright", "j1")!!.region)
        assertEquals(setOf("w9"), store.checked.ids("wuzzuf"))
        // And the new tables work.
        store.setState("k", "v")
        assertEquals("v", store.getState("k"))
        store.mutes.add("company", "acme", "Acme")
        assertEquals(1, store.mutes.all().size)
    }

    /** The schema as that version's Store.onCreate / onUpgrade left it, with two jobs in it. */
    private fun writeOld(version: Int) {
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE items (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, external_id TEXT NOT NULL,
                  item_type TEXT NOT NULL DEFAULT 'job', title TEXT NOT NULL, company TEXT, location TEXT,
                  url TEXT NOT NULL, keyword TEXT, posted_at TEXT, excerpt TEXT, contact_emails TEXT,
                  contact_phones TEXT, seniority TEXT, employment_type TEXT, workplace TEXT, fingerprint TEXT,
                  board TEXT, market TEXT, status TEXT NOT NULL DEFAULT 'pending',
                  send_attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, sent_at TEXT,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE (source, external_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_items_status ON items (status, source)")
            db.execSQL("CREATE INDEX idx_items_fingerprint ON items (fingerprint)")
            db.execSQL("CREATE INDEX idx_items_created ON items (created_at)")
            db.execSQL("CREATE TABLE checked_urls (source TEXT NOT NULL, external_id TEXT NOT NULL, checked_at TEXT NOT NULL, PRIMARY KEY (source, external_id))")
            if (version >= 2) db.execSQL("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            if (version >= 3) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS applications (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, " +
                        "external_id TEXT NOT NULL, title TEXT, company TEXT, channel TEXT NOT NULL, address TEXT, created_at TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO applications (source, external_id, channel, address, created_at) VALUES ('wuzzuf', 'w1', 'email', 'hr@acme.eg', ?)",
                    arrayOf(Instant.now().toString()),
                )
            }
            val now = Instant.now().toString()
            for ((source, id, location) in listOf(Triple("wuzzuf", "w1", "Cairo, Egypt"), Triple("jobright", "j1", "Austin, TX"))) {
                db.execSQL(
                    "INSERT INTO items (source, external_id, title, company, location, url, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'Backend Developer', 'Acme', ?, 'https://example.com', 'sent', ?, ?)",
                    arrayOf(source, id, location, now, now),
                )
            }
            db.execSQL("INSERT INTO checked_urls VALUES ('wuzzuf', 'w9', ?)", arrayOf(now))
            db.version = version
        }
    }

    private fun item(source: String, id: String) =
        Item(source = source, externalId = id, title = "Backend Developer", url = "https://example.com")
}
