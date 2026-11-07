package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionTest {
    private fun item(source: String, market: String? = null, location: String? = null) =
        Item(source = source, externalId = "1", title = "Backend Developer", url = "https://example.com/1", market = market, location = location)

    @Test
    fun `an Egypt board job is Egypt`() {
        assertEquals(Region.EGYPT, Region.of(item("wuzzuf", location = "Cairo, Egypt")))
    }

    @Test
    fun `a gulf market wins on a worldwide board`() {
        assertEquals(Region.GULF, Region.of(item("jobright", market = Gulf.SAUDI)))
    }

    @Test
    fun `an abroad lane market is worldwide`() {
        assertEquals(Region.WORLDWIDE, Region.of(item("linkedin_jobs", market = "🌍 Remote, open to Egypt")))
        assertEquals(Region.WORLDWIDE, Region.of(item("remote_feeds")))
    }

    @Test
    fun `a company board naming Dubai is the Gulf, Cairo is Egypt`() {
        assertEquals(Region.GULF, Region.of(item("ats", location = "Dubai, United Arab Emirates")))
        assertEquals(Region.EGYPT, Region.of(item("ats", location = "Cairo")))
    }

    @Test
    fun `the gulf posts source is the Gulf`() {
        assertEquals(Region.GULF, Region.of(item("gulf_posts")))
        assertEquals(Region.GULF, Region.home("gulf_posts"))
    }

    @Test
    fun `boards reach the gulf only while boardsGulf is on`() {
        assertEquals(setOf(Region.EGYPT), Region.reach("wuzzuf", gulf = false))
        assertEquals(setOf(Region.EGYPT, Region.GULF), Region.reach("wuzzuf", gulf = true))
        assertEquals(setOf(Region.WORLDWIDE), Region.reach("jobright", gulf = true))
        assertEquals(setOf(Region.EGYPT), Region.reach("linkedin_jobs", gulf = true))
    }

    @Test
    fun `ai data is filed under worldwide, its Egypt jobs stay Egypt`() {
        assertEquals(Region.WORLDWIDE, Region.home("ai_data"))
        assertEquals(setOf(Region.WORLDWIDE, Region.EGYPT), Region.reach("ai_data", gulf = false))
        assertEquals(Region.WORLDWIDE, Region.of(item("ai_data", location = "Anywhere")))
        assertEquals(Region.WORLDWIDE, Region.of(item("ai_data")))
        assertEquals(Region.EGYPT, Region.of(item("ai_data", location = "Cairo, Egypt")))
    }

    @Test
    fun `parse reads stored names and nothing else`() {
        assertEquals(Region.GULF, Region.parse("GULF"))
        assertNull(Region.parse("Mars"))
        assertNull(Region.parse(null))
    }
}
