package com.jobcrawler.app

import com.jobcrawler.app.sources.Cadence
import com.jobcrawler.app.sources.Recruitera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recruitera's six-hour turn, what the network cutting a run short does to it, and the notifications. */
class OutagesTest {
    private val key = Recruitera.KEY

    @Test
    fun `Recruitera runs every six hours`() {
        assertEquals(6 * 3_600_000L, Recruitera.EVERY_MS)
        assertEquals(360L, Pipeline.spec(key)?.everyMinutes)
    }

    @Test
    fun `a turn handed back makes the source due again`() {
        val store = MapState()
        val cadence = Cadence(key, Recruitera.EVERY_MS)
        val now = 1_800_000_000_000L

        // An earlier run three hours ago: not due for another three.
        cadence.started(store, now - 3 * 3_600_000L)
        val previous = cadence.last(store)
        assertFalse(cadence.isDue(store, now))

        // This run starts, and the network cuts it short.
        cadence.started(store, now)
        cadence.restore(store, previous)
        assertEquals(previous, cadence.last(store))
        assertFalse(cadence.isDue(store, now))
        assertTrue(cadence.isDue(store, now + 3 * 3_600_000L))
    }

    @Test
    fun `a first run handed back is due at once`() {
        val store = MapState()
        val cadence = Cadence(key, Recruitera.EVERY_MS)
        val now = 1_800_000_000_000L

        cadence.started(store, now)
        cadence.restore(store, null)
        assertTrue(cadence.isDue(store, now))
    }

    @Test
    fun `one unanswered site is skipped, five in a row are an outage`() {
        val streak = Recruitera.Unanswered()

        assertFalse(streak.failed("a"))
        streak.answered()
        assertNull(streak.firstSite)

        assertFalse(streak.failed("b"))
        assertFalse(streak.failed("c"))
        assertFalse(streak.failed("d"))
        assertFalse(streak.failed("e"))
        assertTrue(streak.failed("f"))
        assertEquals(Recruitera.OUTAGE_AFTER, streak.count)
        // The retry starts where the answers stopped.
        assertEquals("b", streak.firstSite)
    }

    @Test
    fun `three failed runs in a row notify once, and the recovery notifies again`() {
        val store = MapState()

        assertNull(Outages.record(store, key, failed = true))
        assertNull(Outages.record(store, key, failed = true))
        val alert = Outages.record(store, key, failed = true)
        assertNotNull(alert)
        assertTrue(alert!!.text.startsWith("3 runs in a row"))

        // Not again for the same streak.
        assertNull(Outages.record(store, key, failed = true))

        val recovered = Outages.record(store, key, failed = false)
        assertNotNull(recovered)
        assertTrue(recovered!!.text.contains("after 4 runs"))

        // A clean run after a clean run says nothing.
        assertNull(Outages.record(store, key, failed = false))
    }

    @Test
    fun `a short outage that recovers before the alert says nothing`() {
        val store = MapState()

        assertNull(Outages.record(store, key, failed = true))
        assertNull(Outages.record(store, key, failed = true))
        assertNull(Outages.record(store, key, failed = false))

        // The count started over.
        assertNull(Outages.record(store, key, failed = true))
        assertNull(Outages.record(store, key, failed = true))
        assertNotNull(Outages.record(store, key, failed = true))
    }

    @Test
    fun `sources that are not watched are never counted`() {
        val store = MapState()
        repeat(5) { assertNull(Outages.record(store, "wuzzuf", failed = true)) }
        assertTrue(store.values.isEmpty())
    }
}
