package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZonedDateTime

/** The breakage alert, snapshots, the rejection breakdown, the weekly summary and the digest. */
class MonitoringTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = Instant.parse("2026-11-02T08:00:00Z")

    private fun run(source: String = "wuzzuf", parsed: Int = 5, daysAgo: Long = 0, rejected: Map<String, Int> = emptyMap(),
                    blocked: Boolean = false, error: String? = null) =
        RunRow(source, now.minusSeconds(daysAgo * 86_400), parsed, 1, 1, 1, rejected = rejected, blocked = blocked, error = error)

    // ---- Breakage ----

    @Test
    fun `silence counts up only for a source that used to find jobs`() {
        assertEquals(3, Breakage.nextStreak(2, parsed = 0, blocked = false, error = null, yields = true))
        assertEquals(0, Breakage.nextStreak(2, parsed = 5, blocked = false, error = null, yields = true))
        assertEquals(0, Breakage.nextStreak(0, parsed = 0, blocked = false, error = null, yields = false))
    }

    @Test
    fun `a block or an error leaves the streak alone`() {
        assertNull(Breakage.nextStreak(2, parsed = 0, blocked = true, error = "429", yields = true))
        assertNull(Breakage.nextStreak(2, parsed = 0, blocked = false, error = "IOException", yields = true))
    }

    @Test
    fun `a run that listed jobs but had none new is not silence`() {
        // relocate.me: 25 listed, 25 seen before; Wuzzuf: 57 match the slug, 57 already opened.
        assertFalse(Breakage.silent(parsed = 0, listed = 25, blocked = false, error = null))
        assertFalse(Breakage.silent(parsed = 0, listed = 57, blocked = false, error = null))
        // The streak starts over, as after a run that parsed something.
        assertEquals(0, Breakage.nextStreak(29, parsed = 0, blocked = false, error = null, yields = true, listed = 57))
        assertEquals(0, Breakage.nextStreak(6, parsed = 0, blocked = false, error = null, yields = true, listed = 646))
    }

    @Test
    fun `a good answer that listed nothing is still silence`() {
        // A real break: the answer came back, nothing in it could be read.
        assertTrue(Breakage.silent(parsed = 0, listed = 0, blocked = false, error = null))
        assertEquals(3, Breakage.nextStreak(2, parsed = 0, blocked = false, error = null, yields = true, listed = 0))
        // A source that does not say what it listed is judged on what it handed back, as before.
        assertTrue(Breakage.silent(parsed = 0, listed = null, blocked = false, error = null))
        assertEquals(3, Breakage.nextStreak(2, parsed = 0, blocked = false, error = null, yields = true, listed = null))
    }

    @Test
    fun `a block, an error or a find is never silence`() {
        assertFalse(Breakage.silent(parsed = 0, listed = 0, blocked = true, error = "429"))
        assertFalse(Breakage.silent(parsed = 0, listed = null, blocked = false, error = "IOException"))
        assertFalse(Breakage.silent(parsed = 3, listed = null, blocked = false, error = null))
        assertNull(Breakage.nextStreak(2, parsed = 0, blocked = true, error = "429", yields = true, listed = 0))
    }

    @Test
    fun `a source says nothing about what it listed unless it reports it`() {
        val quiet = object : com.jobcrawler.app.sources.Source {
            override val key = "quiet"
            override val client = GuestClient("quiet")
            override fun fetch(keywords: List<String>, store: Store): List<Item> = emptyList()
        }
        assertNull(quiet.listed)
        // The sitemap sources report it, and start at nothing reported.
        assertNull(com.jobcrawler.app.sources.Wuzzuf().listed.count)
        assertNull(com.jobcrawler.app.sources.CareerSites().listed.count)
    }

    @Test
    fun `a kept instance does not carry its count into the next run`() {
        // Wuzzuf rebuilds its sitemap nightly: all day, the same 57 are listed and already opened.
        // Store needs Android, so this drives the count as fetch would, around what Pipeline.runSource does.
        val listed = com.jobcrawler.app.sources.ListedCount()
        var sitemapUrls = 57
        fun run(): Int? {
            listed.reset() // the pipeline, before fetch
            listed.add(sitemapUrls) // the source, inside fetch
            return listed.count // the pipeline, after fetch
        }
        assertEquals(57, run())
        assertEquals(57, run())
        assertFalse(Breakage.silent(parsed = 0, listed = run(), blocked = false, error = null))
        // The sitemap breaks: this run listed nothing, whatever earlier runs did.
        sitemapUrls = 0
        assertEquals(0, run())
        assertTrue(Breakage.silent(parsed = 0, listed = run(), blocked = false, error = null))
        listed.reset()
        assertNull(listed.count)
    }

    @Test
    fun `history yields with three clean runs that found something`() {
        assertFalse(Breakage.yieldsHistorically(listOf(run(), run())))
        assertTrue(Breakage.yieldsHistorically(listOf(run(), run(), run())))
        assertFalse(Breakage.yieldsHistorically(listOf(run(), run(), run(blocked = true))))
    }

    // ---- Snapshots ----

    @Test
    fun `snapshots keep the newest three per source`() {
        val dir = folder.newFolder("snapshots")
        java.io.File(dir, "ats-20261101-0900.html").writeText("x")
        for (minute in 0 until 5) Snapshots.save(dir, "wuzzuf", "<html>$minute</html>", now.plusSeconds(minute * 60L))
        val names = dir.list()!!.sorted()
        assertEquals(3, names.count { it.startsWith("wuzzuf-") })
        assertTrue("ats-20261101-0900.html" in names)
        assertTrue("wuzzuf-20261102-1004.html" in names)
    }

    @Test
    fun `a snapshot is capped, and JSON is named so`() {
        val dir = folder.newFolder("s2")
        val file = Snapshots.save(dir, "job_apis", "{" + "a".repeat(400_000), now)
        assertTrue(file.name.endsWith(".json"))
        assertEquals(Snapshots.MAX_CHARS, file.readText().length)
        // 08:00 UTC is 10:00 in Cairo.
        assertEquals("wuzzuf-20261102-1000.html", Snapshots.fileName("wuzzuf", now, "<html>"))
    }

    @Test
    fun `the snapshot caption names the source`() {
        assertEquals("Snapshot wuzzuf · 182 KB · fixture for ParsersTest", LogExport.snapshotCaption("wuzzuf-20261102-0847.html", 182 * 1024))
    }

    // ---- Run history and rejections ----

    @Test
    fun `rejections round-trip as JSON, and bad JSON reads as none`() {
        val map = mapOf("title" to 3, "location" to 1)
        assertEquals(map, RunHistory.parseRejected(RunHistory.toJson(map)))
        assertEquals(emptyMap<String, Int>(), RunHistory.parseRejected(""))
        assertEquals(emptyMap<String, Int>(), RunHistory.parseRejected("{oops"))
    }

    @Test
    fun `the breakdown has the last run and seven days`() {
        val runs = listOf(
            run(rejected = mapOf("title" to 2, "location" to 1), daysAgo = 3),
            run(rejected = mapOf("title" to 3), daysAgo = 0),
            run(rejected = mapOf("title" to 50), daysAgo = 9),
            run(source = "ats", rejected = mapOf("tech" to 1)),
        )
        val list = Rejections.build(runs, now)
        val wuzzuf = list.first { it.source == "wuzzuf" }
        assertEquals(mapOf("title" to 3), wuzzuf.last)
        assertEquals(mapOf("title" to 5, "location" to 1), wuzzuf.week)
        assertEquals("wuzzuf", list.first().source)
        assertEquals(listOf("title" to 5, "location" to 1, "tech" to 1), Rejections.totals(list))
    }

    // ---- Weekly ----

    private fun cairo(text: String) = ZonedDateTime.parse("$text+02:00[Africa/Cairo]")

    @Test
    fun `the weekly summary is due after Friday 10 00, once`() {
        assertFalse(Weekly.isDue(cairo("2026-10-30T09:59:00"), null))
        assertTrue(Weekly.isDue(cairo("2026-10-30T10:05:00"), null))
        assertFalse(Weekly.isDue(cairo("2026-10-30T10:05:00"), "2026-10-30"))
        assertTrue(Weekly.isDue(cairo("2026-10-31T12:00:00"), "2026-10-23"))
        // More than two days late: skipped, not sent.
        assertFalse(Weekly.isDue(cairo("2026-11-02T11:00:00"), "2026-10-23"))
        assertTrue(Weekly.isMissed(cairo("2026-11-02T11:00:00"), "2026-10-23"))
    }

    private fun row(id: Long, score: Int?, region: Region = Region.EGYPT, title: String = "Job $id") = Row(
        id = id,
        item = Item(source = "wuzzuf", externalId = "$id", title = title, url = "https://x.test/$id", company = "Co", score = score),
        status = Status.DIGEST,
        region = region,
    )

    @Test
    fun `the weekly message counts and stays under the limit`() {
        val facts = List(98) { ItemFact("wuzzuf", Region.EGYPT, Status.SENT, 70) } +
            List(27) { ItemFact("ats", Region.GULF, Status.PENDING, 60) } +
            List(18) { ItemFact("jobright", Region.WORLDWIDE, Status.SKIPPED, 50) }
        val data = Weekly.Data(
            from = java.time.LocalDate.parse("2026-10-23"), to = java.time.LocalDate.parse("2026-10-29"),
            facts = facts, top = List(200) { row(it.toLong(), 90, title = "A long title ".repeat(10)) },
            runs = listOf(run(rejected = mapOf("title" to 412))), applications = mapOf("link" to 9, "email" to 6),
            serperWeek = 231, quiet = mapOf("tanqeeb" to 5),
        )
        val text = Weekly.render(data)
        assertTrue(text, "143 new" in text)
        assertTrue(text, "🇪🇬 Egypt 98" in text)
        assertTrue(text.length <= Weekly.BUDGET)
        assertEquals(Regex("<a ").findAll(text).count(), Regex("</a>").findAll(text).count())
        assertEquals("143 new, 98 sent this week", Weekly.headline(data))
    }

    // ---- Digest ----

    @Test
    fun `off, everything goes now`() {
        val rows = listOf(row(1, 90), row(2, 10))
        assertEquals(rows to emptyList<Row>(), Digest.split(rows, on = false, threshold = 70))
    }

    @Test
    fun `on, the threshold splits, and an unscored row waits`() {
        val (now, later) = Digest.split(listOf(row(1, 90), row(2, 70), row(3, 69), row(4, null)), on = true, threshold = 70)
        assertEquals(listOf(1L, 2L), now.map { it.id })
        assertEquals(listOf(3L, 4L), later.map { it.id })
    }

    @Test
    fun `the digest is due every three hours, and once more after it is turned off`() {
        val h = 3_600_000L
        assertFalse(Digest.isDue(now = 2 * h, lastSent = 0, pending = 5, on = true))
        assertTrue(Digest.isDue(now = 3 * h, lastSent = 0, pending = 5, on = true))
        assertTrue(Digest.isDue(now = 1 * h, lastSent = 0, pending = 5, on = false))
        assertFalse(Digest.isDue(now = 9 * h, lastSent = 0, pending = 0, on = true))
    }

    @Test
    fun `the digest groups by region, best first, and says which rows it holds`() {
        val rows = (1L..30L).map { row(it, it.toInt(), if (it % 3 == 0L) Region.GULF else Region.EGYPT) }
        val (text, ids) = Digest.render(rows)
        assertEquals(Digest.TOP, ids.size)
        assertTrue(text.indexOf("Egypt") < text.indexOf("Saudi"))
        val egyptIds = ids.filter { it % 3 != 0L }
        assertEquals(egyptIds.sortedDescending(), egyptIds)
        for (id in ids) assertTrue("https://x.test/$id\"" in text)
        assertTrue("…and 10 more" in text)
    }

    @Test
    fun `a long digest stays under the limit and holds exactly what it shows`() {
        val rows = (1L..30L).map { row(it, 80, title = "Very long title ".repeat(20)) }
        val (text, ids) = Digest.render(rows)
        assertTrue(text.length <= Digest.BUDGET + 60)
        for (id in ids) assertTrue("https://x.test/$id\"" in text)
        for (id in (1L..30L) - ids.toSet()) assertFalse("https://x.test/$id\"" in text)
    }
}
