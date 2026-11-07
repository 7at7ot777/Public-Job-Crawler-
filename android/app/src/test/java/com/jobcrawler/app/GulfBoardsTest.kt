package com.jobcrawler.app

import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.Glassdoor
import com.jobcrawler.app.sources.Recruitera
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saudi/UAE on the boards and ATS sources, behind Settings.boardsGulf. */
class GulfBoardsTest {
    private val profile = ApplySettings(gmail = "me@example.com", appPassword = "x", gulfGmail = "gulf@example.com", gulfAppPassword = "y")

    @Test
    fun `places are read as their Gulf country`() {
        assertEquals(Gulf.SAUDI, Gulf.countryOf("Riyadh, Saudi Arabia"))
        assertEquals(Gulf.SAUDI, Gulf.countryOf("Jeddah"))
        assertEquals(Gulf.UAE, Gulf.countryOf("Dubai, United Arab Emirates"))
        assertEquals(Gulf.UAE, Gulf.countryOf("Abu Dhabi"))
        // Workday and Oracle boards name the city alone.
        assertEquals(Gulf.SAUDI, Gulf.countryOf("Al-Khobar"))
        assertEquals(Gulf.SAUDI, Gulf.countryOf("Yanbu"))
        assertEquals(Gulf.SAUDI, Gulf.countryOf("Jubail"))
        assertEquals(Gulf.UAE, Gulf.countryOf("Fujairah"))
        assertNull(Gulf.countryOf("Madinaty, Cairo"))
        assertNull(Gulf.countryOf("Cairo, Egypt"))
        assertNull(Gulf.countryOf(null))
        assertTrue(Gulf.isGulf("Saudi Arabia"))
        assertFalse(Gulf.isGulf("Egypt"))
    }

    @Test
    fun `the switch adds the Gulf searches, Egypt always first`() {
        assertEquals(listOf("Egypt"), Workable.locations(false))
        assertEquals(listOf("Egypt", Gulf.SAUDI, Gulf.UAE), Workable.locations(true))
        // Saudi/UAE every three hours: between those runs, Egypt alone.
        assertEquals(listOf("Egypt"), Workable.locations(true, gulfDue = false))
        assertEquals(listOf(Tanqeeb.EGYPT), Tanqeeb.sites(false))
        assertEquals(listOf("Egypt", Gulf.SAUDI, Gulf.UAE), Tanqeeb.sites(true).map { it.market })
        assertEquals(setOf("EG"), Recruitera.countries(false))
        assertEquals(setOf("EG", "SA", "AE"), Recruitera.countries(true))
        assertEquals(Gulf.UAE, Recruitera.marketOf("AE"))
        assertEquals(8, Glassdoor.queries(false).size)
        assertEquals(10, Glassdoor.queries(true).size)
        assertEquals(listOf("sa", "ae"), Glassdoor.queries(true).filter { it.scope == Glassdoor.Scope.GULF }.map { it.country })
    }

    @Test
    fun `a Saudi Tanqeeb card links to the Saudi site and carries its market`() {
        val page = """
            <div class="search-job-card-top">
              <h2 class="search-job-title" title="PHP Developer">
                <a class="search-job-title-link" href="/jobs-in-middle-east/all/jobs/021364404.html">PHP Developer</a>
              </h2>
              <div class="search-job-workplace-location">On-site - Saudi Arabia - Riyadh</div>
            </div>
        """
        val item = Tanqeeb.parseSearch(page, "php", Tanqeeb.GULF[0]).single()
        assertEquals("https://saudi.tanqeeb.com/jobs-in-middle-east/all/jobs/021364404.html", item.url)
        assertEquals(Gulf.SAUDI, item.market)
        assertTrue(Filters.evaluate(item.copy(company = "Acme", excerpt = "We build APIs with Laravel and PHP. Visa sponsorship provided.")).pass)
    }

    @Test
    fun `a Workable job in the searched Gulf country is placed there`() {
        val body = """
            {"jobs": [{"id": "J9", "state": "published", "title": "Backend Engineer", "url": "https://jobs.workable.com/view/J9",
              "company": {"title": "Acme"}, "locations": ["Dubai, Dubai, United Arab Emirates"],
              "location": {"countryName": "United Arab Emirates"}, "workplace": "on_site", "created": "2026-10-30T08:00:00.000Z"}]}
        """.trimIndent()
        assertEquals(Gulf.UAE, Workable.parse(body, "Backend", Gulf.UAE).first.single().market)
    }

    @Test
    fun `an ATS job in Riyadh passes the location rule only once placed`() {
        val job = Item(source = Ats.KEY, externalId = "lever:acme:1", title = "Backend Developer", url = "https://x", company = "Acme", location = "Riyadh, Saudi Arabia", excerpt = "We build APIs with Laravel and PHP. Visa sponsorship provided.")
        assertEquals("location", Filters.evaluate(job).reason)
        val placed = Ats.placeInGulf(job)
        assertEquals(Gulf.SAUDI, placed.market)
        assertTrue(Filters.evaluate(placed).pass)
        // Elsewhere is left alone.
        assertNull(Ats.placeInGulf(job.copy(location = "London, UK")).market)
    }

    @Test
    fun `a Glassdoor listing in the UAE is posted like Egypt's, not emailed as abroad`() {
        val uae = Glassdoor.GULF_QUERIES.first { it.country == "ae" }
        val judged = Glassdoor.judge(
            Glassdoor.Result("https://www.glassdoor.com/job-listing/x?jl=7", "Acme hiring Backend Developer Job in Dubai | Glassdoor", "Laravel and MySQL.", null),
            uae,
        )
        assertNull(judged.rejected)
        assertEquals(Gulf.UAE, judged.item.market)
        assertFalse(Mailer.isAbroad(judged.item))
        assertNull(Mailer.autoSender(judged.item, profile))

        val text = Formatter.format(judged.item)
        assertTrue(text, text.contains("🇦🇪 United Arab Emirates"))
        assertTrue(CoverNote.text(judged.item, profile, withCv = true).contains("relocate to the UAE"))

        val london = Glassdoor.judge(
            Glassdoor.Result("https://www.glassdoor.co.uk/job-listing/x?jl=8", "PHP Developer - Beta - London | Glassdoor", "Laravel.", null),
            uae,
        )
        assertEquals("not-gulf", london.rejected)
    }
}
