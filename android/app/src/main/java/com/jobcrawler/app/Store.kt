package com.jobcrawler.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant

object Status {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"
    /** Held for the next digest message (digest mode): sent in a bundle, never retried on its own. */
    const val DIGEST = "digest"
}

/**
 * SQLite dedup + delivery state, the schema of src/store.js.
 *
 * Full rows are kept, not just ids: a retry after a Telegram outage re-renders
 * the message without re-crawling a time window that has already rolled past.
 */
class Store(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION), StateStore {

    init {
        /*
         * Several source workers write at once (one worker per source), and
         * the screens read while they do. WAL lets readers and one writer
         * overlap instead of failing with "database is locked"; the mode is
         * kept in the file, so every helper on it gets it.
         */
        setWriteAheadLoggingEnabled(true)
    }

    /*
     * The tables beside items, each with its own queries. The schema and
     * every migration stay here, in one place, so the upgrade path reads
     * top to bottom.
     */
    val applications = ApplicationsDao(this)
    val mutes = MutesDao(this)
    val runs = RunsDao(this)
    val checked = CheckedUrlsDao(this)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE items (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source TEXT NOT NULL,
              external_id TEXT NOT NULL,
              item_type TEXT NOT NULL DEFAULT 'job',
              title TEXT NOT NULL,
              company TEXT,
              location TEXT,
              url TEXT NOT NULL,
              keyword TEXT,
              posted_at TEXT,
              excerpt TEXT,
              contact_emails TEXT,
              contact_phones TEXT,
              seniority TEXT,
              employment_type TEXT,
              workplace TEXT,
              fingerprint TEXT,
              board TEXT,
              market TEXT,
              status TEXT NOT NULL DEFAULT 'pending',
              send_attempts INTEGER NOT NULL DEFAULT 0,
              last_error TEXT,
              sent_at TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              UNIQUE (source, external_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_items_status ON items (status, source)")
        db.execSQL("CREATE INDEX idx_items_fingerprint ON items (fingerprint)")
        db.execSQL("CREATE INDEX idx_items_created ON items (created_at)")

        /*
         * Pages a sitemap-driven source has already opened. A sitemap lists
         * every live job every run; without this, a page rejected for its age
         * is fetched again every time.
         */
        db.execSQL(
            """
            CREATE TABLE checked_urls (
              source TEXT NOT NULL,
              external_id TEXT NOT NULL,
              checked_at TEXT NOT NULL,
              PRIMARY KEY (source, external_id)
            )
            """.trimIndent(),
        )

        createState(db)
        createApplications(db)
        upgradeTo4(db, backfill = false)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for ((version, step) in migrations) if (version in (oldVersion + 1)..newVersion) step(db)
    }

    /*
     * Each schema version and the step that reaches it from the one before,
     * in order. A new version is one more line here (and [VERSION] + 1);
     * StoreMigrationTest opens every older schema and upgrades it.
     */
    private val migrations: List<Pair<Int, (SQLiteDatabase) -> Unit>> = listOf(
        2 to ::createState,
        3 to ::createApplications,
        4 to { db -> upgradeTo4(db, backfill = true) },
    )

    /*
     * v4: where each job is, how well it fits, what it asks for, and what the
     * person thought of it; mutes; and a history of runs, which RunReport
     * (the latest run only) cannot give the weekly summary or the breakdown
     * of why jobs were dropped. Additive only, so an older row stays valid.
     */
    private fun upgradeTo4(db: SQLiteDatabase, backfill: Boolean) {
        for (column in listOf("region TEXT", "score INTEGER", "years_min INTEGER", "years_max INTEGER", "salary TEXT", "feedback TEXT")) {
            db.execSQL("ALTER TABLE items ADD COLUMN $column")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_region ON items (region, created_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mutes (
              kind TEXT NOT NULL,
              value TEXT NOT NULL,
              label TEXT,
              created_at TEXT NOT NULL,
              PRIMARY KEY (kind, value)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS runs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source TEXT NOT NULL,
              ran_at TEXT NOT NULL,
              parsed INTEGER NOT NULL DEFAULT 0,
              matched INTEGER NOT NULL DEFAULT 0,
              stored INTEGER NOT NULL DEFAULT 0,
              sent INTEGER NOT NULL DEFAULT 0,
              seeded INTEGER NOT NULL DEFAULT 0,
              rejected_json TEXT,
              blocked INTEGER NOT NULL DEFAULT 0,
              error TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_runs_source_time ON runs (source, ran_at)")
        if (backfill) backfill4(db)
    }

    /** Region, years and salary for the rows already there; the rules are Kotlin, so this cannot be one UPDATE. */
    private fun backfill4(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.rawQuery("SELECT * FROM items", null).use { c ->
                while (c.moveToNext()) {
                    val row = rowOf(c)
                    val item = row.item
                    Extract.apply(item)
                    db.execSQL(
                        "UPDATE items SET region = ?, years_min = ?, years_max = ?, salary = ? WHERE id = ?",
                        arrayOf<Any?>(Region.of(item).name, item.yearsMin, item.yearsMax, item.salary, row.id),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /*
     * Every application: "link" when the Apply button was tapped, "email" for
     * a CV sent, "email-failed" for one refused for good. Kept apart from
     * items, which are pruned: an address emailed must stay remembered.
     */
    private fun createApplications(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS applications (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source TEXT NOT NULL,
              external_id TEXT NOT NULL,
              title TEXT,
              company TEXT,
              channel TEXT NOT NULL,
              address TEXT,
              created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_applications_address ON applications (address, created_at)")
    }

    /*
     * The review list: jobs stored since a date that name an address and have
     * no email outcome yet — sent, failed, skipped or ignored. A seeding run's
     * rows are not jobs anyone was told about, so they are left out.
     */
    private val reviewWhere =
        "FROM items i WHERE contact_emails IS NOT NULL AND contact_emails != '' AND created_at >= ? " +
            "AND (last_error IS NULL OR last_error NOT LIKE 'seeded%') " +
            "AND NOT EXISTS (SELECT 1 FROM applications a WHERE a.source = i.source " +
            "AND a.external_id = i.external_id AND a.channel LIKE 'email%')"

    /** Newest first. */
    fun reviewQueue(since: Instant, limit: Int = 100): List<Row> =
        readableDatabase.rawQuery(
            "SELECT * $reviewWhere ORDER BY created_at DESC LIMIT ?",
            arrayOf(since.toString(), limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(rowOf(cursor)) } }

    fun reviewCount(since: Instant): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) $reviewWhere", arrayOf(since.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /*
     * The jobs screen: what passed the filters, newest first. A seeding run's
     * rows are what was already out there on the first day, not finds.
     */
    private val found = "(last_error IS NULL OR last_error NOT LIKE 'seeded%')"

    fun recentJobs(limit: Int = 300): List<Row> =
        readableDatabase.rawQuery(
            "SELECT * FROM items WHERE $found ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(rowOf(cursor)) } }

    fun jobsSince(since: Instant): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM items WHERE $found AND created_at > ?",
            arrayOf(since.toString()),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /*
     * Small values a source carries from one run to the next: where a
     * rotation got to, the day a once-a-day source last ran.
     */
    private fun createState(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun getState(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM state WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    override fun setState(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO state (key, value) VALUES (?, ?)",
            arrayOf(key, value),
        )
    }

    override fun statesWithPrefix(prefix: String): Map<String, String> =
        readableDatabase.rawQuery(
            "SELECT key, value FROM state WHERE substr(key, 1, length(?)) = ? ORDER BY key",
            arrayOf(prefix, prefix),
        ).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getString(1)) }
        }

    private fun now() = Instant.now().toString()

    /** Folds the write-ahead log into the main file, so a copy of that file is the whole database. */
    fun checkpoint() {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    /*
     * A post added by hand is not a crawl: a first run after one must still
     * seed, not send the backlog. With [source], whether that one source has
     * stored anything yet: each source seeds on its own first run.
     */
    fun isEmpty(source: String? = null): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM items WHERE (last_error IS NULL OR last_error != ?)" +
                (if (source != null) " AND source = ?" else "") + " LIMIT 1",
            if (source != null) arrayOf(PostIntake.NOTE, source) else arrayOf(PostIntake.NOTE),
        ).use { !it.moveToFirst() }

    /** Rows the person added on the Add a post screen, for their "Added by you" chip. */
    fun handAddedIds(): Set<Long> =
        readableDatabase.rawQuery("SELECT id FROM items WHERE last_error = ?", arrayOf(PostIntake.NOTE)).use {
            buildSet { while (it.moveToNext()) add(it.getLong(0)) }
        }

    /**
     * Insert unless seen before. UNIQUE (source, external_id) catches the same
     * posting twice; the fingerprint catches one job listed on two boards.
     *
     * @return the stored row and whether this call created it.
     */
    fun insertIfNew(item: Item): Pair<Row?, Boolean> {
        val fingerprint = fingerprintOf(item.company, item.title)

        if (fingerprint != null) {
            findOne("fingerprint = ?", arrayOf(fingerprint))?.let { return it to false }
        }

        val timestamp = now()
        val values = ContentValues().apply {
            put("source", item.source)
            put("external_id", item.externalId)
            put("item_type", item.itemType)
            put("title", item.title)
            put("company", item.company)
            put("location", item.location)
            put("url", item.url)
            put("keyword", item.keyword)
            put("posted_at", item.postedAt)
            put("excerpt", item.excerpt)
            put("contact_emails", item.contactEmails.joinToString(", ").ifEmpty { null })
            put("contact_phones", item.contactPhones.joinToString(", ").ifEmpty { null })
            put("seniority", item.seniority)
            put("employment_type", item.employmentType)
            put("workplace", item.workplace)
            put("fingerprint", fingerprint)
            put("board", item.board)
            put("market", item.market)
            put("region", Region.of(item).name)
            put("score", item.score)
            put("years_min", item.yearsMin)
            put("years_max", item.yearsMax)
            put("salary", item.salary)
            put("status", Status.PENDING)
            put("send_attempts", 0)
            put("created_at", timestamp)
            put("updated_at", timestamp)
        }

        val id = writableDatabase.insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return findByExternalId(item.source, item.externalId) to (id != -1L)
    }

    fun findById(id: Long) = findOne("id = ?", arrayOf(id.toString()))

    fun findByExternalId(source: String, externalId: String) =
        findOne("source = ? AND external_id = ?", arrayOf(source, externalId))

    fun getRetryable(limit: Int): List<Row> {
        val cutoff = Instant.now().minusSeconds(Limits.RETRY_AFTER_MINUTES * 60).toString()
        return readableDatabase.rawQuery(
            "SELECT * FROM items WHERE status IN (?, ?) AND send_attempts < ? AND updated_at < ? " +
                "ORDER BY COALESCE(posted_at, created_at) ASC LIMIT ?",
            arrayOf(Status.PENDING, Status.FAILED, Limits.MAX_SEND_ATTEMPTS.toString(), cutoff, limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(rowOf(cursor)) } }
    }

    fun markSent(id: Long) {
        val timestamp = now()
        writableDatabase.execSQL(
            "UPDATE items SET status = ?, sent_at = ?, send_attempts = send_attempts + 1, last_error = NULL, " +
                "updated_at = ? WHERE id = ?",
            arrayOf(Status.SENT, timestamp, timestamp, id),
        )
    }

    fun markFailed(id: Long, error: String, permanent: Boolean = false) {
        val message = error.take(2000)
        if (permanent) {
            // Parked at the attempt ceiling so it is never picked up again.
            writableDatabase.execSQL(
                "UPDATE items SET status = ?, send_attempts = ?, last_error = ?, updated_at = ? WHERE id = ?",
                arrayOf(Status.FAILED, Limits.MAX_SEND_ATTEMPTS, message, now(), id),
            )
            return
        }
        writableDatabase.execSQL(
            "UPDATE items SET status = ?, send_attempts = send_attempts + 1, last_error = ?, updated_at = ? WHERE id = ?",
            arrayOf(Status.FAILED, message, now(), id),
        )
    }

    /** A post read again by hand: its fuller text, and any address the first read missed. */
    fun updateContent(id: Long, item: Item) {
        writableDatabase.execSQL(
            "UPDATE items SET excerpt = ?, contact_emails = ?, contact_phones = ?, updated_at = ? WHERE id = ?",
            arrayOf(
                item.excerpt,
                item.contactEmails.joinToString(", ").ifEmpty { null },
                item.contactPhones.joinToString(", ").ifEmpty { null },
                now(),
                id,
            ),
        )
    }

    fun markSkipped(id: Long, reason: String) {
        writableDatabase.execSQL(
            "UPDATE items SET status = ?, last_error = ?, updated_at = ? WHERE id = ?",
            arrayOf(Status.SKIPPED, reason.take(2000), now(), id),
        )
    }

    /** Counts an attempt but stays pending, so a throttled row is retried. */
    fun recordAttempt(id: Long, note: String) {
        writableDatabase.execSQL(
            "UPDATE items SET send_attempts = send_attempts + 1, last_error = ?, updated_at = ? WHERE id = ?",
            arrayOf(note.take(2000), now(), id),
        )
    }

    /** Kept for the next digest message instead of being sent on its own. */
    fun markDigest(id: Long) {
        writableDatabase.execSQL(
            "UPDATE items SET status = ?, updated_at = ? WHERE id = ?",
            arrayOf(Status.DIGEST, now(), id),
        )
    }

    /** What waits for the digest, best first. */
    fun digestRows(limit: Int = 200): List<Row> =
        readableDatabase.rawQuery(
            "SELECT * FROM items WHERE status = ? ORDER BY COALESCE(score, 0) DESC, created_at DESC LIMIT ?",
            arrayOf(Status.DIGEST, limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(rowOf(cursor)) } }

    /** The person's verdict on a job, or null to clear it. */
    fun setFeedback(id: Long, value: String?) {
        writableDatabase.execSQL("UPDATE items SET feedback = ? WHERE id = ?", arrayOf(value, id))
    }

    /** Jobs the person marked with [value], newest first: what the score learns from. */
    fun withFeedback(value: String, limit: Int = 500): List<Item> =
        readableDatabase.rawQuery(
            "SELECT * FROM items WHERE feedback = ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(value, limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(rowOf(cursor).item) } }

    /** Per stored job since [since]: enough to count by region, source and status without loading rows. */
    fun itemFacts(since: Instant): List<ItemFact> =
        readableDatabase.rawQuery(
            "SELECT source, region, status, score FROM items WHERE $found AND created_at >= ?",
            arrayOf(since.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ItemFact(
                        source = c.getString(0),
                        region = Region.parse(if (c.isNull(1)) null else c.getString(1)) ?: Region.EGYPT,
                        status = c.getString(2),
                        score = if (c.isNull(3)) null else c.getInt(3),
                    ))
                }
            }
        }

    /** The best-scored jobs stored since [since]. */
    fun topScored(since: Instant, limit: Int = 5): List<Row> =
        readableDatabase.rawQuery(
            "SELECT * FROM items WHERE $found AND created_at >= ? AND score IS NOT NULL ORDER BY score DESC, created_at DESC LIMIT ?",
            arrayOf(since.toString(), limit.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(rowOf(cursor)) } }

    fun stats(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM items GROUP BY status", null).use {
            buildMap { while (it.moveToNext()) put(it.getString(0), it.getInt(1)) }
        }

    /**
     * A phone has less room than a server. Postings older than this are long
     * gone from every board's search, so their rows no longer guard anything.
     */
    fun prune(days: Long = 90) {
        val cutoff = Instant.now().minusSeconds(days * 86_400).toString()
        writableDatabase.delete("items", "created_at < ? AND status != ?", arrayOf(cutoff, Status.PENDING))
        writableDatabase.delete("checked_urls", "checked_at < ?", arrayOf(cutoff))
        val runsCutoff = Instant.now().minusSeconds(RUNS_KEPT_DAYS * 86_400).toString()
        writableDatabase.delete("runs", "ran_at < ?", arrayOf(runsCutoff))
    }

    private fun findOne(where: String, args: Array<String>): Row? =
        readableDatabase.rawQuery("SELECT * FROM items WHERE $where LIMIT 1", args).use {
            if (it.moveToFirst()) rowOf(it) else null
        }

    private fun rowOf(c: Cursor): Row {
        fun str(name: String): String? = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        // A column a v3 cursor (the backfill reads before v4 is complete) does not have reads as null.
        fun int(name: String): Int? = c.getColumnIndex(name).let { if (it < 0 || c.isNull(it)) null else c.getInt(it) }
        fun list(name: String) = str(name)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val item = Item(
            source = str("source")!!,
            externalId = str("external_id")!!,
            title = str("title")!!,
            url = str("url")!!,
            company = str("company"),
            location = str("location"),
            market = str("market"),
            postedAt = str("posted_at"),
            keyword = str("keyword"),
            excerpt = str("excerpt"),
            seniority = str("seniority"),
            employmentType = str("employment_type"),
            workplace = str("workplace"),
            board = str("board"),
            contactEmails = list("contact_emails"),
            contactPhones = list("contact_phones"),
            itemType = str("item_type") ?: "job",
            yearsMin = int("years_min"),
            yearsMax = int("years_max"),
            salary = str("salary"),
            score = int("score"),
        )
        return Row(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            item = item,
            status = str("status")!!,
            createdAt = str("created_at"),
            region = Region.parse(str("region")) ?: Region.of(item),
            feedback = str("feedback"),
        )
    }

    companion object {
        const val NAME = "crawler.sqlite"
        const val VERSION = 4
        const val RUNS_KEPT_DAYS = 60L

        @Volatile
        private var shared: Store? = null

        /*
         * One helper for the whole process, kept open: the source workers run
         * in parallel, and one connection pool with WAL is what keeps their
         * writes from colliding. Screens use it too, never a Store of their own.
         */
        fun shared(context: Context): Store =
            shared ?: synchronized(this) { shared ?: Store(context.applicationContext).also { shared = it } }

        /** Before the database file is replaced (a restore): the next [shared] opens the new one. */
        fun closeShared() = synchronized(this) {
            shared?.close()
            shared = null
        }

        /**
         * A job's identity across boards: company + title, stripped of what
         * differs between listings. Null without a company — titles alone
         * collide, and a false match silently swallows a job.
         */
        fun fingerprintOf(company: String?, title: String?): String? {
            val c = normalize(company) ?: return null
            val t = normalize(title) ?: return null
            val digest = MessageDigest.getInstance("SHA-1").digest("$c::$t".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private val nonWord = Regex("[^\\p{L}\\p{N}]+")

        /** Lowercased, accents and punctuation dropped: how mutes and fingerprints compare names. */
        fun normalize(value: String?): String? {
            if (value == null) return null
            val text = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
                .replace(nonWord, " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            return text.ifEmpty { null }
        }
    }
}

/**
 * The state table as the Serper budget and the query ledger see it, so they
 * can be tested against a map instead of a database.
 */
interface StateStore {
    fun getState(key: String): String?
    fun setState(key: String, value: String)
    fun statesWithPrefix(prefix: String): Map<String, String>
}
