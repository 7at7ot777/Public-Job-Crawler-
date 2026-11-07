package com.jobcrawler.app

import com.jobcrawler.app.sources.SearchPlan
import com.jobcrawler.app.sources.SerperBudget
import com.jobcrawler.app.sources.YieldLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SerperTest {
    /** The state table, as a map. */
    private class MapStore : StateStore {
        val values = sortedMapOf<String, String>()
        override fun getState(key: String) = values[key]
        override fun setState(key: String, value: String) {
            values[key] = value
        }
        override fun statesWithPrefix(prefix: String) = values.filterKeys { it.startsWith(prefix) }
    }

    @Test
    fun `pairs asks one search per keyword and place`() {
        val plan = SearchPlan.plan(SearchPlan.TEMPLATE, listOf("Backend", "Full Stack"), listOf("Egypt", "Cairo", "Giza"), grouped = false)
        assertEquals(6, plan.size)
        assertEquals("site:linkedin.com/posts \"Backend\" \"Egypt\" hiring", plan[0].query)
    }

    @Test
    fun `grouped puts up to six places in an OR group`() {
        val places = listOf("Egypt", "Cairo", "New Cairo", "Giza", "Maadi", "Dokki", "Nasr City")
        val plan = SearchPlan.plan(SearchPlan.TEMPLATE, listOf("Backend", "Full Stack", "Software Engineer"), places, grouped = true)

        assertEquals(6, plan.size)
        assertEquals(
            "site:linkedin.com/posts \"Backend\" (\"Egypt\" OR \"Cairo\" OR \"New Cairo\" OR \"Giza\" OR \"Maadi\" OR \"Dokki\") hiring",
            plan[0].query,
        )
        assertEquals("site:linkedin.com/posts \"Backend\" \"Nasr City\" hiring", plan[1].query)
    }

    @Test
    fun `the default lists cost 30 searches in pairs and 10 grouped`() {
        val keywords = com.jobcrawler.app.sources.LinkedInPosts.KEYWORDS
        val places = com.jobcrawler.app.sources.LinkedInPosts.LOCATIONS
        assertEquals(30, SearchPlan.plan(SearchPlan.TEMPLATE, keywords, places, grouped = false).size)
        assertEquals(10, SearchPlan.plan(SearchPlan.TEMPLATE, keywords, places, grouped = true).size)
    }

    @Test
    fun `each Saudi or UAE place is searched in its own market`() {
        assertEquals("sa", com.jobcrawler.app.sources.LinkedInPosts.countryOf("Riyadh"))
        assertEquals("ae", com.jobcrawler.app.sources.LinkedInPosts.countryOf("Dubai"))
        assertEquals("eg", com.jobcrawler.app.sources.LinkedInPosts.countryOf("Cairo"))
    }

    @Test
    fun `a grouped result is labelled with the place it names, longest first`() {
        val planned = SearchPlan.Planned("k", "Backend", listOf("Cairo", "New Cairo", "Giza"), "q")
        assertEquals("New Cairo", SearchPlan.placeFor(planned, "Hiring in New Cairo", null))
        assertEquals("Giza", SearchPlan.placeFor(planned, null, "office in giza"))
        assertNull(SearchPlan.placeFor(planned, "Remote"))
    }

    @Test
    fun `a quiet search is asked every few days until it finds a post`() {
        var now = 1_790_000_000_000L
        val ledger = YieldLedger(MapStore(), "linkedin_posts", coldAfter = 2, coldEveryDays = 3) { now }
        val query = SearchPlan.Planned("Backend|Maadi", "Backend", listOf("Maadi"), "q")

        assertTrue(ledger.isDue(query))
        ledger.record(query, 0)
        assertTrue("one miss is not cold", ledger.isDue(query))
        ledger.record(query, 0)
        assertFalse("two in a row is", ledger.isDue(query))

        now += 2 * 86_400_000L
        assertFalse(ledger.isDue(query))
        now += 86_400_000L
        assertTrue(ledger.isDue(query))

        ledger.record(query, 4)
        assertTrue(ledger.isDue(query))
        val (key, record) = ledger.report().single()
        assertEquals("Backend|Maadi", key)
        assertEquals(YieldLedger.Record(runs = 3, misses = 0, hits = 4, lastAsked = now), record)
    }

    @Test
    fun `the budget counts a day's searches and stops at the cap with an alert`() {
        val store = MapStore()
        val budget = SerperBudget(store, dailyCap = 2)
        budget.spend()
        budget.spend()

        try {
            budget.spend()
            fail("the third search is over the cap")
        } catch (error: BlockedException) {
            assertEquals("serper-cap", error.alert)
        }

        val usage = budget.usage()
        assertEquals(2, usage.today)
        assertEquals(2, usage.month)
    }

    @Test
    fun `a cap of 0 never stops`() {
        val budget = SerperBudget(MapStore(), dailyCap = 0)
        repeat(500) { budget.spend() }
        assertEquals(500, budget.used())
    }

    @Test
    fun `the balance is read once a day, alerts below the threshold, and survives a failure`() {
        val store = MapStore()
        val budget = SerperBudget(store)
        var reads = 0

        val alert = budget.dailyBalanceAlert(300) { reads++; 120 }
        assertEquals("serper-low-balance", alert?.kind)
        assertNull(budget.dailyBalanceAlert(300) { reads++; 120 })
        assertEquals(1, reads)
        assertEquals(120, budget.usage().balance)

        assertNull(SerperBudget(MapStore()).dailyBalanceAlert(300) { throw IllegalStateException("offline") })
        assertNull(SerperBudget(MapStore()).dailyBalanceAlert(300) { 5_000 })
    }
}
