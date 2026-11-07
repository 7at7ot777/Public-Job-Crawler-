package com.jobcrawler.app

import com.jobcrawler.app.sources.DrJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Dr.Job's walk over its keyword pages: drjobs.ae answers HTTP 500 for a
 * keyword it has nothing under, and that one page must not stop the source.
 */
class DrJobWalkTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val uae = DrJob.GULF.single { it.market == Gulf.UAE }
    private val keywords = listOf("Laravel Developer", "Node.js Developer", "Backend Developer", "PHP Developer")

    /** GuestClient's own messages, so [DrJob.serverStatus] is pinned to what it really throws. */
    private fun serverError(path: String, status: Int = 500) =
        BlockedException("Dr.Job returned HTTP $status after 3 attempts: $path")

    private fun throttled(path: String) = BlockedException("Dr.Job blocked the request (HTTP 429) after 3 attempts: $path")

    /** One card per page, named after the page, so the result shows which pages were read. */
    private fun parse(html: String, keyword: String, site: DrJob.Site) =
        listOf(Item(source = DrJob.KEY, externalId = "${site.market}:$html", title = keyword, url = "https://x/$html"))

    private fun pathOf(url: String) = url.substringAfterLast('/')

    @Test
    fun `a 5xx is a server error, a throttle or the deadline is not`() {
        assertEquals(500, DrJob.serverStatus(serverError("/laravel-developer-jobs")))
        assertEquals(503, DrJob.serverStatus(serverError("/x", 503)))
        assertNull(DrJob.serverStatus(throttled("/x")))
        assertNull(DrJob.serverStatus(BlockedException("Dr.Job: out of time for this run; the rest waits for the next one")))
        assertNull(DrJob.serverStatus(BlockedException("Dr.Job request failed after 3 attempts: timeout", transport = true)))
    }

    @Test
    fun `one page answering 500 is skipped and the rest are still read`() {
        val state = MapState()
        val asked = mutableListOf<String>()
        val items = DrJob.walk(listOf(uae), keywords, state, now = now, parse = this::parse, get = { url ->
            asked += pathOf(url)
            if (url.endsWith("/laravel-developer-jobs")) throw serverError("/laravel-developer-jobs")
            pathOf(url)
        })
        assertEquals(listOf("laravel-developer-jobs", "node-js-developer-jobs", "backend-developer-jobs", "php-developer-jobs"), asked)
        assertEquals(3, items.size)
        assertEquals("1|$now", state.getState(DrJob.FAILED_PREFIX + "https://drjobs.ae/laravel-developer-jobs"))
    }

    @Test
    fun `a page that keeps answering 500 is tried less and less often`() {
        val state = MapState()
        val url = "https://drjobs.ae/laravel-developer-jobs"
        var calls = 0
        fun run(at: Long) = DrJob.walk(listOf(uae), listOf("Laravel Developer", "Backend Developer"), state, now = at, parse = this::parse, get = {
            if (it == url) {
                calls += 1
                throw serverError("/laravel-developer-jobs")
            }
            pathOf(it)
        })

        run(now)
        assertEquals(1, calls)
        // Within a day of the first failure it is left out.
        run(now + day / 2)
        assertEquals(1, calls)
        run(now + day)
        assertEquals(2, calls)
        assertEquals("2|${now + day}", state.getState(DrJob.FAILED_PREFIX + url))
        // Second failure in a row: two days' rest.
        assertTrue(DrJob.isResting(state, url, now + day + day + day / 2))
        assertFalse(DrJob.isResting(state, url, now + 3 * day))

        // Never more than a week, however many times it failed.
        state.setState(DrJob.FAILED_PREFIX + url, "40|$now")
        assertTrue(DrJob.isResting(state, url, now + 6 * day))
        assertFalse(DrJob.isResting(state, url, now + 7 * day))
    }

    @Test
    fun `a page that answers again is forgotten`() {
        val state = MapState()
        val url = "https://drjobs.ae/laravel-developer-jobs"
        state.setState(DrJob.FAILED_PREFIX + url, "3|${now - 10 * day}")
        val items = DrJob.walk(listOf(uae), listOf("Laravel Developer"), state, now = now, parse = this::parse, get = { pathOf(it) })
        assertEquals(1, items.size)
        assertFalse(DrJob.isResting(state, url, now))
        assertEquals("", state.getState(DrJob.FAILED_PREFIX + url))
    }

    @Test
    fun `a throttle still stops the source, with what was read`() {
        try {
            DrJob.walk(listOf(DrJob.EGYPT), keywords, MapState(), now = now, parse = this::parse, get = { url ->
                if (url.endsWith("/backend-developer-jobs")) throw throttled("/egypt/backend-developer-jobs")
                pathOf(url)
            })
            fail("a 429 must stop the source")
        } catch (error: BlockedException) {
            assertEquals(2, error.partialItems.size)
        }
    }

    @Test
    fun `a site that only answers 500 is left for the next run, the other sites are still read`() {
        val state = MapState()
        val asked = mutableListOf<String>()
        val items = DrJob.walk(listOf(uae, DrJob.EGYPT), keywords, state, now = now, parse = this::parse, get = { url ->
            asked += url
            if (url.startsWith("https://drjobs.ae/")) throw serverError("/" + pathOf(url))
            pathOf(url)
        })
        // Three failures and nothing else on drjobs.ae: its fourth page is not asked for.
        assertEquals(DrJob.SITE_DOWN_AFTER, asked.count { it.startsWith("https://drjobs.ae/") })
        assertFalse("https://drjobs.ae/php-developer-jobs" in asked)
        assertEquals(keywords.size, items.size)
    }

    @Test
    fun `every site answering only 500 is reported, not taken for a quiet day`() {
        try {
            DrJob.walk(DrJob.sites(gulf = true), keywords, MapState(), now = now, parse = this::parse, get = { url ->
                throw serverError("/" + pathOf(url))
            })
            fail("a site that is down must be reported")
        } catch (error: BlockedException) {
            assertTrue(error.message!!.contains("looks down"))
            assertNull(DrJob.serverStatus(error))
        }
    }
}
