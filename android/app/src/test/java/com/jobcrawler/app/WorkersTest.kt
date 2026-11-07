package com.jobcrawler.app

import com.jobcrawler.app.sources.Cadence
import com.jobcrawler.app.sources.SerperBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One worker per source: the schedule, the lanes, and the state they share. */
class WorkersTest {
    private val settings = Settings(keywords = listOf("Laravel"), sources = Defaults.sources.toSet(), intervalMinutes = 30)

    @Test
    fun `each source's interval follows its own cadence`() {
        assertEquals(15L, Scheduler.intervalFor("linkedin_jobs", settings))
        assertEquals(30L, Scheduler.intervalFor("wuzzuf", settings))
        // Recruitera, every 6 hours.
        assertEquals(360L, Scheduler.intervalFor("recruitera", settings))
        // Company boards, twice a day.
        assertEquals(720L, Scheduler.intervalFor("ats", settings))
    }

    @Test
    fun `a person's interval wins, and nothing is under 15 minutes`() {
        val mine = settings.copy(sourceIntervals = mapOf("ats" to 60L, "wuzzuf" to 5L))
        assertEquals(60L, Scheduler.intervalFor("ats", mine))
        assertEquals(15L, Scheduler.intervalFor("wuzzuf", mine))
    }

    @Test
    fun `every default source has a spec, and no retired one does`() {
        val keys = Pipeline.SOURCES.map { it.key }.toSet()
        assertEquals(Defaults.sources.toSet(), keys)
        assertTrue(Defaults.retiredSources.none { it in keys })
    }

    @Test
    fun `LinkedIn sources take turns`() {
        // linkedin_jobs is the only LinkedIn source left on (OFF 2026-11-05), so a second entry stands in for another one.
        val first = Lanes.tryEnter("linkedin_jobs")
        assertNotNull(first)
        assertNull(Lanes.tryEnter("linkedin_jobs"))
        first!!.close()
        Lanes.tryEnter("linkedin_jobs")!!.close()
    }

    @Test
    fun `the posts and Glassdoor share the Serper lane`() {
        assertEquals(setOf("linkedin_posts", "glassdoor", "gulf_posts"), Pipeline.keysOn(Pipeline.Lane.SERPER))
    }

    @Test
    fun `unrelated sources run side by side, up to the cap`() {
        val held = listOf("wuzzuf", "ats", "workable").map { Lanes.tryEnter(it) }
        assertTrue(held.all { it != null })
        assertNull("a fourth gets no slot", Lanes.tryEnter("tanqeeb"))
        held.forEach { it!!.close() }
        Lanes.tryEnter("tanqeeb")!!.close()
    }

    @Test
    fun `a refused entry gives back what it took`() {
        val linkedin = Lanes.tryEnter("linkedin_jobs")!!
        // A second LinkedIn entry takes a slot, then fails on LinkedIn: the slot must come back.
        repeat(5) { assertNull(Lanes.tryEnter("linkedin_jobs")) }
        val two = listOf(Lanes.tryEnter("wuzzuf"), Lanes.tryEnter("ats"))
        assertTrue(two.all { it != null })
        two.forEach { it!!.close() }
        linkedin.close()
        linkedin.close() // twice is harmless
        assertEquals(Lanes.MAX_PARALLEL, Lanes.slots.availablePermits())
    }

    @Test
    fun `the send pace spaces sends across threads`() {
        SendPace.reset()
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val pool = Executors.newFixedThreadPool(3)
        val done = CountDownLatch(3)
        repeat(3) {
            pool.execute {
                SendPace.await(gapMs = 100)
                times += System.currentTimeMillis()
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        val sorted = times.sorted()
        for (i in 1 until sorted.size) assertTrue("gap ${sorted[i] - sorted[i - 1]}", sorted[i] - sorted[i - 1] >= 90)
    }

    @Test
    fun `two workers spending Serper at once never pass the cap`() {
        val store = MapState()
        val budget = SerperBudget(store, dailyCap = 50)
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(200)
        var refused = java.util.concurrent.atomic.AtomicInteger()
        repeat(200) {
            pool.execute {
                runCatching { budget.spend() }.onFailure { refused.incrementAndGet() }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(50, budget.used())
        assertEquals(150, refused.get())
    }

    @Test
    fun `a cadence fired a little early still counts as due`() {
        val store = MapState()
        val cadence = Cadence("ats", 3 * 3_600_000L)
        val start = 1_000_000_000L
        cadence.started(store, start)
        assertFalse(cadence.isDue(store, start + 60 * 60_000L))
        assertTrue(cadence.isDue(store, start + 3 * 3_600_000L - 5 * 60_000L))
        // A short cadence keeps at least half of itself.
        val quick = Cadence("li", 15 * 60_000L)
        quick.started(store, start)
        assertFalse(quick.isDue(store, start + 5 * 60_000L))
        assertTrue(quick.isDue(store, start + 8 * 60_000L))
    }

    @Test
    fun `only a source's first empty run seeds`() {
        assertTrue(Pipeline.shouldSeed(null, empty = true))
        assertFalse(Pipeline.shouldSeed("1", empty = true))
        assertFalse(Pipeline.shouldSeed(null, empty = false))
    }

    @Test
    fun `a lost network belongs to its run`() {
        val a = GuestClient("A").apply { run = RunContext() }
        val b = GuestClient("B").apply { run = RunContext() }
        a.run.lost = true
        assertFalse(b.run.lost)
    }

    @Test
    fun `parallel finishes keep each other's rows`() {
        val t0 = Instant.parse("2026-11-02T08:00:00Z")
        var report = RunReport(t0, t0, emptyList())
        report = RunReport.mergeInto(report, t0.plusSeconds(60), listOf(Pipeline.SourceStat("wuzzuf", parsed = 4)))
        report = RunReport.mergeInto(report, t0.plusSeconds(30), listOf(Pipeline.SourceStat("ats", parsed = 9)))
        assertEquals(setOf("wuzzuf", "ats"), report.sources.map { it.key }.toSet())
        assertEquals(t0.plusSeconds(60), report.ended)
        report = RunReport.mergeInto(report, t0.plusSeconds(90), listOf(Pipeline.SourceStat("wuzzuf", parsed = 1)))
        assertEquals(1, report.sources.first { it.key == "wuzzuf" }.parsed)
        assertEquals(2, report.sources.size)
    }

    @Test
    fun `housekeeping's hourly parts run once an hour`() {
        val store = MapState()
        assertTrue(Housekeeping.hourly(store, "h", now = 0))
        assertFalse(Housekeeping.hourly(store, "h", now = 30 * 60_000L))
        assertTrue(Housekeeping.hourly(store, "h", now = 61 * 60_000L))
    }
}
