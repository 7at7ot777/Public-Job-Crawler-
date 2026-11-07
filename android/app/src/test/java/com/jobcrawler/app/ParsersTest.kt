package com.jobcrawler.app

import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.LinkedInJobs
import com.jobcrawler.app.sources.LinkedInPosts
import com.jobcrawler.app.sources.Recruitera
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import com.jobcrawler.app.sources.Wuzzuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test
    fun `LinkedIn cards`() {
        val html = """
            <li>
              <div class="base-card" data-entity-urn="urn:li:jobPosting:4012345678">
                <a class="base-card__full-link" href="https://eg.linkedin.com/jobs/view/php-developer-at-acme-4012345678?refId=abc">x</a>
                <h3 class="base-search-card__title"> PHP Developer </h3>
                <h4 class="base-search-card__subtitle"><a href="#">Acme</a></h4>
                <span class="job-search-card__location">Cairo, Egypt</span>
                <time datetime="2026-10-23">1 hour ago</time>
              </div>
            </li>
            <li></li>
        """.trimIndent()

        val items = LinkedInJobs.parseCards(html, "PHP Developer", "Egypt")
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("4012345678", item.externalId)
        assertEquals("PHP Developer", item.title)
        assertEquals("Acme", item.company)
        assertEquals("https://eg.linkedin.com/jobs/view/php-developer-at-acme-4012345678", item.url)
        assertEquals("2026-10-23T00:00:00Z", item.postedAt)
        assertEquals("Egypt", item.market)
    }

    @Test
    fun `LinkedIn detail`() {
        val html = """
            <div class="show-more-less-html__markup"> We use <b>Laravel</b>. </div>
            <li class="description__job-criteria-item">
              <h3 class="description__job-criteria-subheader">Seniority level</h3>
              <span class="description__job-criteria-text">Entry level</span>
            </li>
        """.trimIndent()

        val (description, criteria) = LinkedInJobs.parseDetail(html)
        assertEquals("We use Laravel.", description)
        assertEquals("Entry level", criteria["seniority level"])
    }

    @Test
    fun `Wuzzuf sitemap index`() {
        val index = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <sitemap><loc>https://wuzzuf.net/sitemap-job-1.xml</loc><lastmod>2026-10-01</lastmod></sitemap>
              <sitemap><loc>https://wuzzuf.net/sitemap-job-2.xml</loc><lastmod>2026-11-01</lastmod></sitemap>
              <sitemap><loc>https://wuzzuf.net/sitemap-saudi-job-1.xml</loc></sitemap>
              <sitemap><loc>https://wuzzuf.net/sitemap-saudi-job-3.xml</loc></sitemap>
              <sitemap><loc>https://wuzzuf.net/sitemap-qatar-job-1.xml</loc></sitemap>
              <sitemap><loc>https://wuzzuf.net/sitemap-job-4.xml.gz</loc></sitemap>
              <sitemap><loc>https://wuzzuf.net/sitemap-companies-1.xml</loc></sitemap>
            </sitemapindex>
        """.trimIndent()

        val listed = Wuzzuf.jobSitemaps(index)
        // Markets it does not know, gzip and non-job files are left out.
        assertEquals(
            listOf("sitemap-job-1.xml", "sitemap-job-2.xml", "sitemap-saudi-job-1.xml", "sitemap-saudi-job-3.xml"),
            listed.map { it.url.substringAfterLast('/') },
        )
        assertEquals("2026-11-01", listed[1].lastmod)

        // Egypt first; per market the latest change, else the highest number.
        val chosen = Wuzzuf.choose(listed)
        assertEquals(listOf("sitemap-job-2.xml", "sitemap-saudi-job-3.xml"), chosen.map { it.url.substringAfterLast('/') })
        assertEquals(listOf("Egypt", "Saudi Arabia"), chosen.map { it.market })

        // A challenge page or a missing index names none; the source then reads the known files.
        assertTrue(Wuzzuf.jobSitemaps("<html><title>Just a moment...</title></html>").isEmpty())
    }

    @Test
    fun `Wuzzuf sitemap`() {
        val xml = """
            <urlset>
              <url><loc> https://wuzzuf.net/jobs/p/abc123-php-developer-acme-cairo-egypt?a=1&amp;b=2 </loc><lastmod>2026-10-31T08:00:00+00:00</lastmod></url>
              <url><loc>https://wuzzuf.net/jobs/p/def456-accountant-acme-cairo-egypt</loc><lastmod>2026-10-31T08:00:00+00:00</lastmod></url>
            </urlset>
        """.trimIndent()
        assertEquals(
            listOf("https://wuzzuf.net/jobs/p/abc123-php-developer-acme-cairo-egypt?a=1&b=2", "https://wuzzuf.net/jobs/p/def456-accountant-acme-cairo-egypt"),
            Wuzzuf.locs(xml),
        )
        assertEquals("2026-10-31T08:00:00+00:00", Wuzzuf.lastmodOf(xml))
        assertNull(Wuzzuf.lastmodOf("<html></html>"))
        assertTrue(Wuzzuf.locs("<html></html>").isEmpty())
    }

    @Test
    fun `Wuzzuf page`() {
        val url = "https://wuzzuf.net/jobs/p/yqpqphojdzwj-php-developer-acme-cairo-egypt"
        val page = """
            <html><head>
            <title>PHP Developer job at Acme in Cairo, Egypt – Apply on Wuzzuf</title>
            <meta property="og:description" content="Laravel &amp; MySQL">
            </head><body>
            <h1 class="x">PHP Developer</h1>
            <span>2 days ago</span>
            <h2>Job Description</h2><div><p>Build APIs with Laravel.</p></div>
            <h2>Job Requirements</h2><div>3 years</div>
            </body></html>
        """.trimIndent()

        val now = java.time.Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()
        val item = Wuzzuf.parsePage(page, url, now)!!
        assertEquals("yqpqphojdzwj", item.externalId)
        assertEquals("PHP Developer", item.title)
        assertEquals("Acme", item.company)
        assertEquals("Cairo, Egypt", item.location)
        assertEquals("2026-10-21T12:00:00Z", item.postedAt)
        assertEquals("Build APIs with Laravel.", item.excerpt)
        assertNull(Wuzzuf.parsePage("<html></html>", url, now))
    }

    @Test
    fun `Tanqeeb search and page`() {
        val card = { id: String, title: String ->
            """
            <div class="search-job-card-top">
              <h2 class="search-job-title" title="$title">
                <a class="search-job-title-link" href="/jobs-in-egypt/all/jobs/$id.html">$title</a>
              </h2>
              <div class="search-job-workplace-location">On-site - Egypt - Cairo</div>
              <div class="search-job-source">Bayt</div>
              <div class="search-job-date">13 August 2026</div>
            </div>
            """
        }
        val items = Tanqeeb.parseSearch(card("021158212", "PHP Developer") + card("021158213", "Backend Developer"), "php")
        assertEquals(listOf("021158212", "021158213"), items.map { it.externalId })
        assertEquals("Egypt, Cairo", items[0].location)
        assertEquals("On-site", items[0].workplace)
        assertEquals("Bayt", items[0].board)
        assertEquals("https://egypt.tanqeeb.com/jobs-in-egypt/all/jobs/021158212.html", items[0].url)
        assertEquals("2026-08-13T00:00:00Z", items[0].postedAt)

        val (company, excerpt) = Tanqeeb.parsePage(
            """<div class="job-meta-company">Acme</div><div class="job-meta-location">Cairo</div>
               <h3>Job Details</h3><p>Laravel and Vue.</p>""",
        )
        assertEquals("Acme", company)
        assertEquals("Laravel and Vue.", excerpt)
        assertEquals("Node js Developer", Tanqeeb.searchTerm("Node.js Developer"))
        // Tanqeeb has no results for "Laravel Developer" but twenty for "Laravel".
        assertEquals("Laravel", Tanqeeb.broaderTerm("Laravel Developer"))
        assertEquals("Software", Tanqeeb.broaderTerm("Software engineer"))
        assertEquals(null, Tanqeeb.broaderTerm("Application Support"))
        assertEquals(null, Tanqeeb.broaderTerm("Developer"))
    }

    @Test
    fun `Workable response`() {
        val body = """
            {"totalSize": 2, "nextPageToken": "abc", "jobs": [
              {"id": "J1", "state": "published", "title": "Backend Engineer", "url": "https://jobs.workable.com/view/J1",
               "company": {"title": "Acme"}, "locations": ["Cairo, Cairo Governorate, Egypt", "TELECOMMUTE"],
               "location": {"countryName": "Egypt"}, "workplace": "hybrid", "created": "2026-10-22T08:00:00.000Z",
               "description": "<p>Node.js &amp; Laravel</p>"},
              {"id": "J2", "state": "closed", "title": "Old", "url": "https://jobs.workable.com/view/J2"}
            ]}
        """.trimIndent()

        val (items, next) = Workable.parse(body, "Backend", "Egypt")
        assertEquals("abc", next)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("Acme", item.company)
        assertEquals("Egypt", item.market)
        assertEquals("Hybrid", item.workplace)
        assertEquals("Cairo, Cairo Governorate, Egypt", item.location)
        assertEquals("2026-10-22T08:00:00Z", item.postedAt)
        assertTrue(item.excerpt!!.contains("Node.js & Laravel"))
        assertEquals(0, Workable.parse("<html>challenge</html>", null, "Egypt").first.size)
    }

    @Test
    fun `Recruitera sitemap, categories, listing and job`() {
        val sitemap = """
            <urlset><url><loc>https://app.recruitera.ai/careers/paymob</loc></url>
            <url><loc>https://app.recruitera.ai/careers/paymob/backend-engineer-x1</loc></url>
            <url><loc> https://app.recruitera.ai/careers/fawry/ </loc></url></urlset>
        """.trimIndent()
        assertEquals(listOf("paymob", "fawry"), Recruitera.parseSitemapSites(sitemap))

        val filters = """{"data": {"categories": [
            {"id": "c1", "name": "IT/Software Development"}, {"id": "c2", "name": "Sales"}]}}"""
        assertEquals(listOf("c1" to "IT/Software Development"), Recruitera.pickCategories(filters))

        val (jobs, next) = Recruitera.parseOpportunities(
            """{"data": [{"id": "j1", "slug": "backend-engineer-x1", "title": "Backend Engineer"}, {"id": "j2"}],
                "meta": {"next_cursor": null}}""",
        )
        assertEquals(listOf("j1"), jobs.map { it.optString("id") })
        assertNull(next)

        val detail = Recruitera.parseJobDetail(
            """{"data": {"id": "j1", "title": " Backend Engineer ", "available": true,
                "location_details": {"country": "eg", "city": "Cairo"}, "location": "Paymob Head Office",
                "work_model": "hybrid", "published_at": "2026-10-22T09:00:00.000000Z",
                "requirements": "<p>Laravel &amp; MySQL</p>", "description": "<p>About Paymob</p>"}}""",
        )!!
        assertEquals("Backend Engineer", detail.title)
        assertEquals("EG", detail.country)
        assertEquals("Cairo, Egypt", detail.location)
        assertEquals("2026-10-22T09:00:00Z", detail.publishedAt)
        assertEquals("Laravel & MySQL\nAbout Paymob", detail.excerpt)
        assertEquals("Paymob", Recruitera.parseCompanyName("""{"data": {"company": {"name": "Paymob"}}}"""))
        assertNull(Recruitera.parseJobDetail("<html></html>"))

        assertTrue(Recruitera.isJunkTitle("Legacy Data Agent mun6kji05zs"))
        assertTrue(Recruitera.isJunkTitle("Global Operations Officer 1790713328542"))
        assertTrue(Recruitera.isJunkTitle("Backend Engineer (Copy)"))
        assertFalse(Recruitera.isJunkTitle("Backend Engineer"))
        assertFalse(Recruitera.isJunkTitle("Laravel Developer L2"))
    }

    @Test
    fun `ATS feeds`() {
        val lever = Ats.parseBoard("lever:klivvr:Klivvr")!!
        val leverJobs = Ats.parseBoardResponse(
            """[{"id": "abc", "text": "Backend Engineer", "hostedUrl": "https://jobs.lever.co/klivvr/abc",
                 "categories": {"location": "Cairo, Egypt", "commitment": "Full-time"}, "workplaceType": "hybrid",
                 "createdAt": 1792656000000, "descriptionPlain": "Node.js"}]""",
            lever,
        )!!
        assertEquals("lever:klivvr:abc", leverJobs[0].externalId)
        assertEquals("Klivvr", leverJobs[0].company)
        assertEquals("Hybrid", leverJobs[0].workplace)
        assertEquals("2026-10-22T08:00:00Z", leverJobs[0].postedAt)

        val greenhouse = Ats.parseBoardResponse(
            """{"jobs": [{"id": 42, "title": "PHP Developer", "absolute_url": "https://boards.greenhouse.io/tamara/jobs/42",
                "location": {"name": "Cairo"}, "first_published": "2026-10-21T10:00:00-04:00",
                "content": "&lt;p&gt;Laravel &amp;amp; Vue&lt;/p&gt;"}]}""",
            Ats.parseBoard("greenhouse:tamara")!!,
        )!!
        assertEquals("Tamara", greenhouse[0].company)
        assertEquals("2026-10-21T14:00:00Z", greenhouse[0].postedAt)
        assertEquals("Laravel & Vue", greenhouse[0].excerpt)

        val rss = """<rss version="2.0"><channel><item>
            <title>Backend Developer</title><link>https://careers.axisapp.com/jobs/1-backend</link>
            <guid>1-backend</guid><pubDate>Sun, 04 Oct 2026 11:57:49 +0300</pubDate>
            <description><![CDATA[<p>Laravel</p>]]></description>
            <tt:locations><tt:location><tt:city>Cairo</tt:city><tt:country>Egypt</tt:country></tt:location></tt:locations>
            <remoteStatus>none</remoteStatus></item></channel></rss>"""
        val teamtailor = Ats.parseBoardResponse(rss, Ats.parseBoard("teamtailor:careers.axisapp.com:Axis")!!)!!
        assertEquals("Cairo, Egypt", teamtailor[0].location)
        assertEquals("2026-10-04T08:57:49Z", teamtailor[0].postedAt)
        assertEquals("Laravel", teamtailor[0].excerpt)

        val zohoPage = """<input type="hidden" id="jobs" value="[{&quot;id&quot;:&quot;9&quot;,&quot;Posting_Title&quot;:&quot;Node.js Developer&quot;,&quot;City&quot;:&quot;Cairo&quot;,&quot;Country&quot;:&quot;Egypt&quot;,&quot;Publish&quot;:true,&quot;Job_Description&quot;:&quot;&lt;p&gt;APIs &gt; UIs&lt;/p&gt;&quot;}]">"""
        val zoho = Ats.parseBoardResponse(zohoPage, Ats.parseBoard("zoho:qutip:QuTip")!!)!!
        assertEquals("https://qutip.zohorecruit.com/jobs/Careers/9", zoho[0].url)
        assertEquals("Cairo, Egypt", zoho[0].location)

        assertNull(Ats.parseBoardResponse("", lever))
        assertNull(Ats.parseBoardResponse("<html>Not found</html>", lever))
        assertNull(Ats.parseBoard("successfactors:x/y"))
    }

    @Test
    fun `Gulf ATS feeds - SmartRecruiters, Workday, Oracle HCM`() {
        val sr = Ats.parseBoard("smartrecruiters:ArchirodonGroup:Archirodon")!!
        assertEquals("https://api.smartrecruiters.com/v1/companies/ArchirodonGroup/postings?limit=100", Ats.urlOf(sr))
        assertNull(Ats.requestBody(sr))
        val srJobs = Ats.parseBoardResponse(
            """{"totalFound": 1, "content": [{"id": "744000160204429", "name": "Mechanical QC Engineer",
                "company": {"identifier": "ArchirodonGroup", "name": "Archirodon Group N.V"},
                "releasedDate": "2026-10-30T13:38:34.475Z",
                "location": {"city": "Abu Dhabi", "country": "ae", "remote": false, "hybrid": false,
                             "fullLocation": "Abu Dhabi, , United Arab Emirates"},
                "typeOfEmployment": {"label": "Full-time"}}]}""",
            sr,
        )!!
        assertEquals("smartrecruiters:ArchirodonGroup:744000160204429", srJobs[0].externalId)
        assertEquals("https://jobs.smartrecruiters.com/ArchirodonGroup/744000160204429", srJobs[0].url)
        assertEquals("Archirodon Group N.V", srJobs[0].company)
        assertEquals("Abu Dhabi, United Arab Emirates", srJobs[0].location)
        assertEquals("Full-time", srJobs[0].employmentType)
        assertEquals("2026-10-30T13:38:34.475Z", srJobs[0].postedAt)
        assertEquals(Gulf.UAE, Ats.placeInGulf(srJobs[0]).market)

        val wd = Ats.parseBoard("workday:wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi:Yokogawa")!!
        assertEquals("Yokogawa", wd.name)
        assertEquals("https://wd3.myworkdaysite.com/wday/cxs/yokogawa/yokogawa-career-site/jobs", Ats.urlOf(wd))
        assertTrue(Ats.requestBody(wd)!!.contains("\"searchText\":\"Saudi\""))
        val wdJobs = Ats.parseBoardResponse(
            """{"total": 1, "jobPostings": [{"title": "Service Engineer",
                "externalPath": "/job/Yanbu/Service-Engineer_R-9001", "locationsText": "Yanbu",
                "postedOn": "Posted 6 Days Ago", "bulletFields": ["R-9001"]}]}""",
            wd,
        )!!
        assertEquals("workday:wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi:R-9001", wdJobs[0].externalId)
        assertEquals(
            "https://wd3.myworkdaysite.com/recruiting/yokogawa/yokogawa-career-site/job/Yanbu/Service-Engineer_R-9001",
            wdJobs[0].url,
        )
        assertEquals("Yokogawa", wdJobs[0].company)
        assertEquals(Gulf.SAUDI, Ats.placeInGulf(wdJobs[0]).market)

        val now = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        assertEquals("2026-11-01T12:00:00Z", Ats.workdayPosted("Posted 6 Days Ago", now))
        assertEquals("2026-11-07T12:00:00Z", Ats.workdayPosted("Posted Today", now))
        assertEquals("2026-11-06T12:00:00Z", Ats.workdayPosted("Posted Yesterday", now))
        // "30+" is past the age cut, not undated.
        assertTrue(Ats.isOlderThan(Ats.workdayPosted("Posted 30+ Days Ago", now), 30, now))

        val ora = Ats.parseBoard("oracle:eghj.fa.em2.oraclecloud.com/CX_1:NHC")!!
        assertEquals(
            "https://eghj.fa.em2.oraclecloud.com/hcmRestApi/resources/latest/recruitingCEJobRequisitions" +
                "?onlyData=true&expand=requisitionList&finder=findReqs;siteNumber=CX_1,limit=50,sortBy=POSTING_DATES_DESC",
            Ats.urlOf(ora),
        )
        val oraJobs = Ats.parseBoardResponse(
            """{"items": [{"TotalJobsCount": 1, "requisitionList": [{"Id": "1234", "Title": "HRIS Specialist",
                "PostedDate": "2026-10-13", "PrimaryLocation": "Riyadh, Saudi Arabia",
                "WorkplaceTypeCode": "ORA_ON_SITE", "ShortDescriptionStr": "Oracle HCM"}]}]}""",
            ora,
        )!!
        assertEquals("oracle:eghj.fa.em2.oraclecloud.com/CX_1:1234", oraJobs[0].externalId)
        assertEquals("https://eghj.fa.em2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1/job/1234", oraJobs[0].url)
        assertEquals("NHC", oraJobs[0].company)
        assertEquals("On-site", oraJobs[0].workplace)
        assertEquals("2026-10-13T00:00:00Z", oraJobs[0].postedAt)
        assertEquals(Gulf.SAUDI, Ats.placeInGulf(oraJobs[0]).market)

        assertTrue("smartrecruiters:ArchirodonGroup:Archirodon" in Ats.BOARDS)
        assertTrue(Ats.BOARDS.all { Ats.parseBoard(it) != null })
    }

    @Test
    fun atsAgesDateOnlyPostingsByCalendarDayAndRunsTwiceADay() {
        val now = java.time.Instant.parse("2026-11-03T21:00:00Z").toEpochMilli()
        assertFalse(Ats.isOlderThan("2026-10-31T00:00:00Z", 3, now))
        assertTrue(Ats.isOlderThan("2026-10-30T00:00:00Z", 3, now))
        // With a time of day the cut stays exact.
        assertTrue(Ats.isOlderThan("2026-10-31T20:00:00Z", 3, now))
        assertFalse(Ats.isOlderThan("2026-10-31T22:00:00Z", 3, now))
        assertFalse(Ats.isOlderThan(null, 3, now))

        assertEquals(3, Ats.BOARD_MAX_AGE_DAYS["zoho:careers.migrationit.com"])
        assertTrue("zoho:careers.migrationit.com:MigrationIT" in Ats.BOARDS)
        assertEquals(720L, Pipeline.spec(Ats.KEY)?.everyMinutes)
    }

    @Test
    fun `Serper results and LinkedIn posts`() {
        val url = "https://www.linkedin.com/posts/mona-ali-1a2b3c4d_hiring-laravel-activity-7380000000000000000-AbCd?utm_source=x"
        val body = """{"organic": [
            {"title": "Mona Ali on LinkedIn: We're hiring a Laravel developer", "link": "$url",
             "snippet": "Send your CV", "date": "2 days ago"},
            {"title": "Acme | LinkedIn", "link": "https://www.linkedin.com/company/acme"}]}"""

        val results = LinkedInPosts.parseSerper(body)
        assertEquals(1, results.size)

        val item = LinkedInPosts.toItem(results[0], "Laravel", "Egypt")!!
        assertEquals("7380000000000000000", item.externalId)
        assertEquals("post", item.itemType)
        assertEquals("We're hiring a Laravel developer", item.title)
        assertEquals("Mona Ali", item.company)
        assertEquals(url.substringBefore('?'), item.url)
        assertNull(item.market)

        assertEquals("Mona Ali", LinkedInPosts.authorFromUrl(url))
        assertEquals("2025-10-03T22:05:13.769Z", LinkedInPosts.activityDate(url))

        try {
            LinkedInPosts.parseSerper("""{"message": "Not enough credits", "statusCode": 400}""")
            throw AssertionError("expected a block")
        } catch (expected: BlockedException) {
            assertTrue(expected.message!!.contains("Not enough credits"))
        }

        val post = LinkedInPosts.parsePost(
            """<html><head><meta property="og:title" content="Mona Ali on LinkedIn: We're hiring">
               <meta property="og:description" content="We're hiring a Laravel developer in Maadi."></head></html>""",
        )
        assertEquals("Mona Ali", post.author)
        assertEquals("We're hiring a Laravel developer in Maadi.", post.text)

        val message = Formatter.format(item.apply { excerpt = "We're hiring a Laravel developer\nSend your CV" })
        assertTrue(message.startsWith("📣 <b>We're hiring a Laravel developer</b>"))
        assertTrue(message.contains("👤 Mona Ali"))
    }
}
