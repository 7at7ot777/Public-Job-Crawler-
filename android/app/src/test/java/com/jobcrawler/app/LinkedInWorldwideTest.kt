package com.jobcrawler.app

import com.jobcrawler.app.sources.LinkedInWorldwide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedInWorldwideTest {
    private val card = Item(
        source = LinkedInWorldwide.KEY,
        externalId = "4012345678",
        title = "Backend Developer (Laravel)",
        url = "https://www.linkedin.com/jobs/view/4012345678",
        company = "Acme",
        location = "Worldwide",
        keyword = "Backend Developer",
    )

    private val criteria = mapOf("employment type" to "Full-time", "seniority level" to "Entry level")

    private fun description(extra: String) =
        "We are looking for a backend developer to join our team and work on our Laravel and PHP services. " +
            "You will build and maintain APIs with the rest of the engineering team. $extra"

    private fun reason(extra: String) = LinkedInWorldwide.judge(card, description(extra), criteria).rejected

    @Test
    fun anOpenRemoteJobIsKeptAndFiledAsWorldwide() {
        val judged = LinkedInWorldwide.judge(card, description("This is a fully remote role: work from anywhere."), criteria)
        assertEquals(null, judged.rejected)
        assertEquals(LinkedInWorldwide.LANE, judged.item.market)
        assertTrue(judged.item.remote)
        assertEquals(Region.WORLDWIDE, Region.of(judged.item))
        // A 🌍 market is settled, so the shared location rule lets it through.
        assertTrue(Filters.locationAllowed(judged.item))
    }

    @Test
    fun aRegionThatIncludesEgyptCounts() {
        assertEquals(null, reason("Remote within EMEA."))
        assertEquals(null, reason("Candidates in the Middle East and Africa are welcome."))
    }

    @Test
    fun restrictionsAreDropped() {
        assertEquals("us-only", reason("Remote, US only. Work from anywhere in the country."))
        assertEquals("us-only", reason("You must be based in the US."))
        assertEquals("region-locked", reason("Open to EU residents only, worldwide team."))
        assertEquals("ineligible", reason("You must be authorized to work in Germany. Worldwide team."))
    }

    @Test
    fun noSignalInTheDescriptionIsDropped() {
        // The card's "Worldwide" is only what was searched, so it does not count.
        assertEquals("not-open-worldwide", reason("Remote, with a monthly visit to our New York office."))
    }

    @Test
    fun anotherLanguageIsDropped() {
        assertEquals("language", reason("Fluent German is required. Work from anywhere."))
    }

    @Test
    fun noDescriptionAndPartTimeAreDropped() {
        assertEquals("no-description", LinkedInWorldwide.judge(card, null, criteria).rejected)
        val partTime = mapOf("employment type" to "Part-time")
        assertEquals("part-time", LinkedInWorldwide.judge(card, description("Work from anywhere."), partTime).rejected)
    }

    @Test
    fun theSearchIsRemoteWorldwide() {
        val params = LinkedInWorldwide.params("Backend Developer", "r3600")
        assertEquals("Worldwide", params["location"])
        assertEquals("2", params["f_WT"])
    }

    @Test
    fun theWorldwideBudgetIsItsOwn() {
        val store = MapState()
        val now = 1_000_000_000_000L
        repeat(LinkedInRest.WORLDWIDE_HOURLY_CAP) { assertTrue(LinkedInRest.takeWorldwide(store, now + it)) }
        assertFalse(LinkedInRest.takeWorldwide(store, now + 100))
        // Egypt's hourly count is untouched: its full share is still there.
        repeat(LinkedInRest.HOURLY_CAP) { assertTrue(LinkedInRest.take(store, now + it)) }
        assertFalse(LinkedInRest.take(store, now + 200))
        // A new hour, a new worldwide share.
        assertTrue(LinkedInRest.takeWorldwide(store, now + 3_600_000L))
    }
}
