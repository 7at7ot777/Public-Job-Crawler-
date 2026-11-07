package com.jobcrawler.app

import com.jobcrawler.app.sources.LinkedInJobs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why every run used to hit LinkedIn's 429 again: nothing remembered the last one. */
class LinkedInRestTest {
    private class MapStore : StateStore {
        val map = HashMap<String, String>()
        override fun getState(key: String) = map[key]
        override fun setState(key: String, value: String) { map[key] = value }
        override fun statesWithPrefix(prefix: String) = map.filterKeys { it.startsWith(prefix) }
    }

    private val min = 60_000L

    @Test
    fun `a 429 rests every LinkedIn search, longer when it happens again`() {
        val store = MapStore()
        val t0 = 1_000_000_000_000L
        assertNull(LinkedInRest.restingUntil(store, t0))

        val first = LinkedInRest.throttled(store, now = t0)
        assertEquals(t0 + 30 * min, first)
        assertEquals(first, LinkedInRest.restingUntil(store, t0 + 10 * min))
        assertNull(LinkedInRest.restingUntil(store, first + 1))

        // Throttled again right after the rest: the IP is still remembered, so twice as long.
        val second = LinkedInRest.throttled(store, now = first + 1)
        assertEquals(first + 1 + 60 * min, second)

        // Never more than six hours.
        var until = second
        repeat(6) { until = LinkedInRest.throttled(store, now = until + 1) }
        assertEquals(LinkedInRest.MAX_REST_MINUTES.toString(), store.getState("linkedin.penaltyMinutes"))

        // Clean runs shrink it back to the first rest.
        repeat(5) { LinkedInRest.clean(store) }
        assertEquals(t0 + 2 * 24 * 60 * min + 30 * min, LinkedInRest.throttled(store, now = t0 + 2 * 24 * 60 * min))
    }

    @Test
    fun `only a rate limit rests the job search, not a post page's login wall`() {
        // A 429 is the IP's limit wherever it came from.
        assertTrue(LinkedInRest.restsOn(429, jobSearch = true))
        assertTrue(LinkedInRest.restsOn(429, jobSearch = false))
        // 999 on the job API is LinkedIn's own "slow down".
        assertTrue(LinkedInRest.restsOn(999, jobSearch = true))
        // A post page's 999 or 403 is a login wall: it used to rest the job search for hours.
        assertFalse(LinkedInRest.restsOn(999, jobSearch = false))
        assertFalse(LinkedInRest.restsOn(403, jobSearch = false))
        assertFalse(LinkedInRest.restsOn(403, jobSearch = true))
        assertFalse(LinkedInRest.restsOn(null, jobSearch = true))
    }

    @Test
    fun `a rest says who started it and with what status`() {
        val store = MapStore()
        val t0 = 1_000_000_000_000L
        assertNull(LinkedInRest.reason(store))
        val until = LinkedInRest.throttled(store, "LinkedIn posts", 429, now = t0)
        assertEquals("LinkedIn posts, HTTP 429", LinkedInRest.reason(store))
        assertTrue(LinkedInRest.describe(store, until).endsWith("(LinkedIn posts, HTTP 429)"))
    }

    @Test
    fun `an hourly cap shared by every LinkedIn source`() {
        val store = MapStore()
        val hour = 3_600_000L * 500_000
        repeat(LinkedInRest.HOURLY_CAP) { assertTrue(LinkedInRest.take(store, hour + it)) }
        assertFalse(LinkedInRest.take(store, hour + 1_000))
        // The next hour starts again.
        assertTrue(LinkedInRest.take(store, hour + 3_600_000L))
    }

    @Test
    fun `the search window covers the time since the last clean run`() {
        val now = 2_000_000_000_000L
        assertEquals("r3600", LinkedInJobs.windowSince(null, now))
        assertEquals("r3600", LinkedInJobs.windowSince(now - 30 * min, now))
        // Rested for three hours: three hours and ten minutes, so no job is missed.
        assertEquals("r11400", LinkedInJobs.windowSince(now - 180 * min, now))
        assertEquals("r86400", LinkedInJobs.windowSince(now - 3 * 24 * 60 * min, now))
    }

    @Test
    fun `a run by hand searches the hours it asked for, up to a week`() {
        assertEquals("r3600", LinkedInJobs.windowForHours(1))
        assertEquals("r36000", LinkedInJobs.windowForHours(10))
        assertEquals("r86400", LinkedInJobs.windowForHours(24))
        assertEquals("r604800", LinkedInJobs.windowForHours(168))
        // Out of range is held to 1 hour .. 1 week.
        assertEquals("r3600", LinkedInJobs.windowForHours(0))
        assertEquals("r604800", LinkedInJobs.windowForHours(1_000))
    }
}
