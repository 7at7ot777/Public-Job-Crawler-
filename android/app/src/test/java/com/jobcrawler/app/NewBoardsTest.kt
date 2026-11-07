package com.jobcrawler.app

import com.jobcrawler.app.sources.AbroadFit
import com.jobcrawler.app.sources.DrJob
import com.jobcrawler.app.sources.Jobgether
import com.jobcrawler.app.sources.RemoteFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Remote Rocketship (a remote feed), Jobgether and Dr.Job, against markup shaped like theirs. */
class NewBoardsTest {
    // ── Remote Rocketship ──

    private val rocketshipPage = """
        <html><body><div id="__next"></div>
        <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialTotalJobCount":231,"initialJobOpenings":[
          {"id":"20674135","created_at":"2026-11-03T14:22:49.414+00:00","roleTitle":"Laravel Developer",
           "twoLineJobDescriptionSummary":"Build REST APIs with PHP and Laravel for a fintech product. Fully remote team.",
           "url":"https://ats.rippling.com/acme/jobs/1","techStack":["PHP","Laravel","MySQL"],"employmentType":"full-time",
           "location":"Worldwide","locationCountries":null,"locationType":"remote","company":{"name":"Acme"}},
          {"id":"20674136","created_at":"2026-11-02T05:28:04.918+00:00","roleTitle":"Node.js Developer",
           "twoLineJobDescriptionSummary":"Node.js and TypeScript services.","url":"https://join.com/x/2",
           "techStack":["Node.js","TypeScript"],"location":"Egypt","locationCountries":["Egypt","Kenya"],"locationType":"remote",
           "company":{"name":"Beta"}},
          {"id":"20674137","created_at":"2026-11-01T08:00:00+00:00","roleTitle":"Backend Developer",
           "twoLineJobDescriptionSummary":"PHP backend for a Dubai retailer.","url":"https://join.com/x/3",
           "techStack":["PHP"],"location":"United Arab Emirates","locationCountries":["United Arab Emirates"],"locationType":"remote",
           "company":{"name":"Gamma"}},
          {"id":"20674138","roleTitle":"No link, skipped"}
        ]}}}</script></body></html>
    """.trimIndent()

    @Test
    fun `Remote Rocketship jobs are read from the page's Next data`() {
        val jobs = RemoteFeeds.remoteRocketship(rocketshipPage)
        assertEquals(listOf("20674135", "20674136", "20674137"), jobs.map { it.id })

        val first = jobs[0]
        assertEquals("rocketship", first.feed)
        assertEquals("Acme", first.company)
        assertEquals("https://ats.rippling.com/acme/jobs/1", first.url)
        assertEquals("Worldwide", first.restriction)
        assertEquals("2026-11-03T14:22:49.414Z", first.postedAt)
        assertTrue(first.remote)
        assertTrue(first.description.contains("Stack: PHP, Laravel, MySQL"))

        assertEquals("Egypt, Kenya", jobs[1].restriction)
    }

    @Test
    fun `a worldwide Rocketship Laravel job is kept, a UAE-only one is not`() {
        val (worldwide, egypt, uae) = RemoteFeeds.remoteRocketship(rocketshipPage)

        val kept = RemoteFeeds.judge(worldwide, RemoteFeeds.KEY)
        assertNull(kept.rejected)
        assertEquals(AbroadFit.REMOTE_LANE, kept.item.market)
        assertEquals("rocketship:20674135", kept.item.externalId)
        assertEquals("Remote Rocketship", kept.item.board)

        assertNull(RemoteFeeds.judge(egypt, RemoteFeeds.KEY).rejected)
        assertEquals("region-locked", RemoteFeeds.judge(uae, RemoteFeeds.KEY).rejected)
    }

    @Test
    fun `a page without Next data is no jobs, not an error`() {
        assertTrue(RemoteFeeds.remoteRocketship("<html>Just a moment…</html>").isEmpty())
    }

    // ── Jobgether ──

    private val jobgetherList = """
        {"jobs":[
          {"id":"6ae9e21b5f4c309bcf647032","title":"Backend Developer (PHP, Laravel)","company":"Acme",
           "url":"https://jobgether.com/offer/6ae9e21b5f4c309bcf647032-backend-developer","location":"Anywhere, EMEA",
           "remote":"Full Remote","contractType":"Full time","experience":"Mid-level (2-5 years)","jobFunctions":[],
           "postedAt":"2026-11-03T13:31:07.627Z"},
          {"id":"6ae9e21b5f4c309bcf647033","title":"Sales Manager","company":"Beta",
           "url":"https://jobgether.com/offer/6ae9e21b5f4c309bcf647033-sales","location":"Anywhere","remote":"Full Remote",
           "contractType":"Full time","postedAt":"2026-11-03T10:00:00.000Z"},
          {"id":"6ae9e21b5f4c309bcf647034","title":"Backend Developer Intern","company":"Gamma",
           "url":"https://jobgether.com/offer/6ae9e21b5f4c309bcf647034-intern","location":"Africa","remote":"Full Remote",
           "contractType":"Internships","postedAt":"2026-11-02T10:00:00.000Z"}
        ],"pagination":{"page":1,"limit":25,"hasMore":false}}
    """.trimIndent()

    private fun offerPage(countries: List<String>) = """
        <html><head>
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[]}</script>
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting","title":"Backend Developer (PHP, Laravel)",
          "description":"<p>This a Full Remote job, the offer is available from: EMEA</p><p>You will build APIs with PHP, Laravel and MySQL. 2+ years of experience.</p>",
          "hiringOrganization":{"@type":"Organization","name":"Acme Ltd"},"datePosted":"2026-11-03T13:31:07.627Z",
          "employmentType":["FULL_TIME"],"jobLocationType":"TELECOMMUTE",
          "applicantLocationRequirements":[${countries.joinToString(",") { """{"@type":"Country","name":"$it"}""" }}]}</script>
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun `Jobgether's search asks for jobs open to Egypt, newest first, with the docs' own names`() {
        val params = Jobgether.params("Laravel Developer")
        assertEquals("Laravel Developer", params["keyword"])
        assertEquals("egypt", params["locations"])
        assertEquals("date", params["sort"])
        assertEquals(listOf("Laravel Developer", "PHP"), Jobgether.queries(listOf("Laravel Developer", " PHP ", "laravel developer", "")))
        assertEquals(Jobgether.DEFAULT_QUERIES, Jobgether.queries(emptyList()))
    }

    @Test
    fun `Jobgether's list is read, and titles and internships are settled before opening a page`() {
        val cards = Jobgether.parseList(jobgetherList)
        assertEquals(3, cards.size)
        assertEquals("Anywhere, EMEA", cards[0].location)
        assertEquals("2026-11-03T13:31:07.627Z", cards[0].postedAt)

        assertNull(Jobgether.earlyReject(cards[0]))
        assertEquals("title", Jobgether.earlyReject(cards[1]))
        assertEquals("internship", Jobgether.earlyReject(cards[2]))
    }

    @Test
    fun `a Jobgether job whose page lets Egypt apply is kept in the remote lane`() {
        val card = Jobgether.parseList(jobgetherList).first()
        val offer = Jobgether.parseOffer(offerPage(listOf("AE", "EG", "SA")))
        assertEquals(setOf("AE", "EG", "SA"), offer.countries)
        assertEquals("Acme Ltd", offer.company)
        assertTrue(offer.description.contains("PHP, Laravel and MySQL"))

        val judged = RemoteFeeds.judge(Jobgether.asJob(card, offer), Jobgether.KEY)
        assertNull(judged.rejected)
        assertEquals(AbroadFit.REMOTE_LANE, judged.item.market)
        assertEquals("jobgether:6ae9e21b5f4c309bcf647032", judged.item.externalId)
        assertEquals("Jobgether", judged.item.board)
        assertEquals("Acme Ltd", judged.item.company)
    }

    @Test
    fun `a Jobgether page that leaves Egypt out says so, and a page with no JSON-LD is empty`() {
        val offer = Jobgether.parseOffer(offerPage(listOf("DE", "FR")))
        assertFalse("EG" in offer.countries)
        assertTrue(offer.countries.isNotEmpty())

        val none = Jobgether.parseOffer("<html><body>Not found</body></html>")
        assertTrue(none.countries.isEmpty())
        assertEquals("", none.description)
    }

    @Test
    fun `Jobgether is a worldwide source`() {
        assertEquals(Region.WORLDWIDE, Region.home(Jobgether.KEY))
    }

    // ── Dr.Job ──

    private fun card(id: String, title: String, place: String, age: String, base: String = "https://www.drjobpro.com/egypt") = """
        <div class="card-item job-card cursor-pointer" ng-click="fetchJobDetailCard('$id', ${'$'}event, '$base/jobs/x-$id')">
        <a href="$base/jobs/${title.lowercase().replace(' ', '-')}-cairo-acme-$id" class="job-card-stretched-link" target="_self">
        <h2 class="job-name">$title</h2>
        <div class="card-img-title-wrapper"><div class="card-title-wrapper">
            <p class="company-name">Acme</p>
            <div class="location"><img src="pin.svg" alt="profile" /><span>$place</span></div>
        </div></div>
        <div class="card-description-wrapper"><p class="jobseeker_jobdescription_text job-desc-attr">Build PHP APIs with Laravel...</p></div>
        </a>
        <div class="card-btns-wrapper"><div class="left-group">
            <div class="posted-date">
                <span>$age</span>
            </div>
            <div class="full-onsite-btn"><span class="job-type full-time">Full Time</span></div>
        </div><button type="button" class="details-btn">Details</button></div>
        </div>
    """.trimIndent()

    @Test
    fun `keywords become Dr Job's own page names`() {
        assertEquals("node-js-developer", DrJob.slug("Node.js Developer"))
        assertEquals("laravel-developer", DrJob.slug("  Laravel   Developer "))
        assertEquals("c-developer", DrJob.slug("C++ Developer"))
        assertNull(DrJob.slug("  ++ "))
    }

    @Test
    fun `Dr Job cards carry the company, place, age and type`() {
        val now = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()
        val page = "<html><body><section>header</section>" +
            card("MVZW6DME27M83AG", "Php Backend Developer", "Cairo - Egypt", "15 hours ago") +
            card("MVYGQJ8L274N8H9", "Laravel Developer", "Giza - Egypt", "30+ days ago") +
            "<footer>end</footer></body></html>"
        val items = DrJob.parseList(page, "PHP Developer")
        assertEquals(listOf("MVZW6DME27M83AG", "MVYGQJ8L274N8H9"), items.map { it.externalId })

        val first = items[0]
        assertEquals(DrJob.KEY, first.source)
        assertEquals("Php Backend Developer", first.title)
        assertEquals("https://www.drjobpro.com/egypt/jobs/php-backend-developer-cairo-acme-MVZW6DME27M83AG", first.url)
        assertEquals("Acme", first.company)
        assertEquals("Cairo, Egypt", first.location)
        assertEquals("Full Time", first.employmentType)
        assertEquals("Egypt", first.market)
        assertEquals("Dr.Job", first.board)
        assertNotNull(first.postedAt)

        // "30+ days ago" is old, not an unknown age that would be kept.
        assertEquals(Instant.ofEpochMilli(now - 30 * 86_400_000L).toString(), DrJob.age("30+ days ago", now))
        assertTrue(Dates.isOlderThan(DrJob.age("30+ days ago", now), 7, now))
        assertFalse(Dates.isOlderThan(DrJob.age("15 hours ago", now), 7, now))
    }

    @Test
    fun `a UAE card from drjobs ae carries the UAE market`() {
        val uae = DrJob.GULF.single { it.market == Gulf.UAE }
        val item = DrJob.parseList(card("MVZW5Y4Y01PBWWA", "Backend Developer", "Dubai - UAE", "4 hours ago", "https://drjobs.ae"), "Backend Developer", uae).single()
        assertEquals("https://drjobs.ae/jobs/backend-developer-cairo-acme-MVZW5Y4Y01PBWWA", item.url)
        assertEquals(Gulf.UAE, item.market)
        assertEquals(Region.GULF, Region.of(item))
    }

    @Test
    fun `Dr Job searches Egypt, and Saudi and the UAE only when asked`() {
        assertEquals(listOf(DrJob.EGYPT), DrJob.sites(false))
        assertEquals(listOf("Egypt", Gulf.SAUDI, Gulf.UAE), DrJob.sites(true).map { it.market })
        assertEquals(setOf(Region.EGYPT), Region.reach(DrJob.KEY, gulf = false))
        assertEquals(setOf(Region.EGYPT, Region.GULF), Region.reach(DrJob.KEY, gulf = true))
    }

    @Test
    fun `a Dr Job page gives the whole ad from its JobPosting`() {
        val page = """
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"WebPage","name":"x"}</script>
            <script type="application/ld+json">{"@context":"https://schema.org/","@type":"JobPosting","title":"Python Software Developer Remote",
              "description":"<p><strong>Job Title:</strong> Senior Software Engineer</p><p>Python and Django APIs.</p>",
              "datePosted":"2026-10-26","employmentType":"FULL_TIME",
              "hiringOrganization":{"@type":"Organization","name":"YO AI Labs"},
              "jobLocation":{"@type":"Place","address":{"@type":"PostalAddress","addressLocality":"Cairo","addressCountry":"EG"}}}</script>
        """.trimIndent()
        val detail = DrJob.parseDetail(page)!!
        assertEquals("YO AI Labs", detail.company)
        assertEquals("2026-10-26T00:00:00Z", detail.postedAt)
        assertEquals("FULL_TIME", detail.employmentType)
        assertTrue(detail.description!!.contains("Python and Django APIs."))

        assertNull(DrJob.parseDetail("<html>no data</html>"))
    }

    @Test
    fun `both new sources are on by default and scheduled`() {
        assertTrue(DrJob.KEY in Defaults.sources)
        assertNotNull(Pipeline.spec(DrJob.KEY))
        // Jobgether is back on with the worldwide remote sources.
        assertTrue(Jobgether.KEY in Defaults.sources)
        assertNotNull(Pipeline.spec(Jobgether.KEY))
        assertTrue(Jobgether.KEY !in Defaults.retiredSources)
    }
}
