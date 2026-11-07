package com.jobcrawler.app

import com.jobcrawler.app.sources.LinkedInPosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CriteriaTest {
    @Test
    fun `lists nobody edited are the app's defaults`() {
        assertEquals(Filters.DEFAULT_TECH, Filters.tech)
        assertEquals(Filters.DEFAULT_EGYPT_PLACES, Filters.egyptPlaces)
        // Every Egypt place: LinkedIn jobs searches one a run, in turn, so more
        // places make the cycle longer rather than each run heavier.
        assertEquals(Defaults.locations, Criteria[Criteria.Kind.SEARCH_PLACES])
        assertEquals("Egypt", Criteria[Criteria.Kind.SEARCH_PLACES].first())
        assertEquals(LinkedInPosts.KEYWORDS, Criteria[Criteria.Kind.POST_KEYWORDS])
        assertFalse(Criteria.isEdited(Criteria.Kind.TECH))
    }

    @Test
    fun `one entry per line, so a place may hold a comma`() {
        assertEquals(
            listOf("Cairo, Egypt", "Giza, Egypt", "Alexandria"),
            Criteria.parse("  Cairo, Egypt\n\nGiza, Egypt\ncairo, egypt\n Alexandria "),
        )
        assertNull(Criteria.parse(" \n \n"))
    }

    @Test
    fun `every list is shown in exactly one section`() {
        // OFF (2026-11-05): the lists of sources that are off are hidden, not shown.
        assertEquals(Criteria.Kind.entries.toSet(), (Criteria.search + Criteria.filters + Criteria.hidden).toSet())
        assertEquals(Criteria.Kind.entries.size, Criteria.search.size + Criteria.filters.size + Criteria.hidden.size)
    }
}
