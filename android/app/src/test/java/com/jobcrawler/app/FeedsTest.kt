package com.jobcrawler.app

import com.jobcrawler.app.sources.AbroadFit
import com.jobcrawler.app.sources.HnHiring
import com.jobcrawler.app.sources.JobApis
import com.jobcrawler.app.sources.RemoteFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free remote feeds, Hacker News' Who is hiring, and the keyed job APIs. */
class FeedsTest {
    private fun kept(job: RemoteFeeds.Job, source: String = RemoteFeeds.KEY) = RemoteFeeds.judge(job, source)

    @Test
    fun `who may apply, by the feed's own word`() {
        assertTrue(AbroadFit.openToEgypt(null))
        assertTrue(AbroadFit.openToEgypt(""))
        assertTrue(AbroadFit.openToEgypt("Worldwide"))
        assertTrue(AbroadFit.openToEgypt("Anywhere in the World"))
        assertTrue(AbroadFit.openToEgypt("EMEA"))
        assertTrue(AbroadFit.openToEgypt("UTC+2"))
        assertFalse(AbroadFit.openToEgypt("USA"))
        assertFalse(AbroadFit.openToEgypt("Europe, LATAM, APAC, the U.S., Canada"))
    }

    @Test
    fun `the Group B feeds have their own switch, the rest always run`() {
        assertEquals(
            listOf("himalayas", "jobicy", "remotive", "wwr", "remoteok", "rocketship"),
            RemoteFeeds.TOGGLEABLE.map { it.first },
        )
        assertEquals(RemoteFeeds.FEEDS, RemoteFeeds.active(emptySet()))

        val active = RemoteFeeds.active(setOf("remoteok", "wwr"))
        assertEquals(RemoteFeeds.FEEDS.size - 2, active.size)
        assertFalse(active.any { it.name == "RemoteOK" || it.name == "We Work Remotely" })
        // Untoggleable feeds stay, both Landing.jobs pages included.
        assertEquals(2, active.count { it.name == "Landing.jobs" })
        assertTrue(active.any { it.name == "Working Nomads" } && active.any { it.name == "Arbeitnow" })
        // One switch covers all three Remote Rocketship pages.
        assertEquals(3, active.count { it.id == "rocketship" })
        assertTrue(RemoteFeeds.active(setOf("rocketship")).none { it.id == "rocketship" })

        // Switching everything off still leaves the feeds without a switch.
        val all = RemoteFeeds.TOGGLEABLE.map { it.first }.toSet()
        assertTrue(RemoteFeeds.active(all).all { it.id == null })
    }

    @Test
    fun `a feed's switch id is the feed name its jobs carry`() {
        val samples = mapOf(
            "himalayas" to """{"jobs":[{"guid":"https://himalayas.app/j/1","title":"Backend Developer"}]}""",
            "jobicy" to """{"jobs":[{"id":"1","jobTitle":"Backend Developer","url":"https://jobicy.com/j/1"}]}""",
            "remotive" to """{"jobs":[{"id":"1","title":"Backend Developer","url":"https://remotive.com/j/1"}]}""",
            "wwr" to "<rss><item><title>Acme: Backend Developer</title><link>https://weworkremotely.com/j/1</link></item></rss>",
            "remoteok" to """[{"legal":"terms"},{"id":"1","position":"Backend Developer","url":"https://remoteok.com/j/1"}]""",
            "rocketship" to """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialJobOpenings":[{"id":"1","roleTitle":"Backend Developer","url":"https://ats.example/j/1"}]}}}</script>""",
        )
        for (feed in RemoteFeeds.FEEDS.filter { it.id != null }) {
            val jobs = feed.parse(samples.getValue(feed.id!!))
            assertEquals(feed.name, 1, jobs.size)
            assertEquals(feed.name, feed.id, jobs.single().feed)
        }
    }

    @Test
    fun `Remotive worldwide Laravel job is kept in the remote lane`() {
        val body = """{"jobs":[{"id":1,"url":"https://remotive.com/remote-jobs/1","title":"Laravel Developer","company_name":"Acme",
            "candidate_required_location":"Worldwide","publication_date":"2026-10-30T10:00:00",
            "description":"<p>We build APIs with PHP and Laravel, MySQL and Docker. 2+ years of experience. Fully remote.</p>"}]}"""
        val job = RemoteFeeds.remotive(body).single()
        assertEquals("2026-10-30T10:00:00Z", job.postedAt)
        val judged = kept(job)
        assertNull(judged.rejected)
        assertEquals(AbroadFit.REMOTE_LANE, judged.item.market)
        assertEquals("remotive:1", judged.item.externalId)
        assertEquals("Remotive", judged.item.board)
    }

    @Test
    fun `Himalayas locks a US-only job and opens a UTC+2 one`() {
        val body = """{"jobs":[
            {"title":"Backend Developer (Node.js)","companyName":"Acme","guid":"https://himalayas.app/companies/acme/jobs/a",
             "locationRestrictions":["United States"],"timezoneRestrictions":[],"description":"Node.js and NestJS.","pubDate":1793500000},
            {"title":"Backend Developer (Node.js)","companyName":"Beta","guid":"https://himalayas.app/companies/beta/jobs/b",
             "locationRestrictions":[],"timezoneRestrictions":[2,3],"description":"Remote. Node.js, NestJS, TypeScript. 3+ years of experience.","pubDate":1793500000}
        ]}"""
        val (us, cairo) = RemoteFeeds.himalayas(body)
        assertEquals("region-locked", kept(us).rejected)
        assertEquals("UTC+2", cairo.restriction)
        assertNull(kept(cairo).rejected)
    }

    @Test
    fun `Jobicy, We Work Remotely, RemoteOK and Working Nomads parse`() {
        val jobicy = RemoteFeeds.jobicy(
            """{"jobs":[{"id":7,"url":"https://jobicy.com/jobs/7","jobTitle":"PHP Developer","companyName":"Acme","jobGeo":"EMEA",
               "jobDescription":"<p>Laravel and PHP, remote across EMEA. 2 years of experience.</p>","pubDate":"2026-11-01T10:00:00+00:00"},
              {"id":8,"url":"https://jobicy.com/jobs/8","jobTitle":"PHP Developer","companyName":"Acme","jobGeo":"USA",
               "jobDescription":"<p>Laravel.</p>","pubDate":"2026-11-01T10:00:00+00:00"}]}""",
        )
        assertNull(kept(jobicy[0]).rejected)
        assertEquals("region-locked", kept(jobicy[1]).rejected)

        val wwr = RemoteFeeds.weWorkRemotely(
            """<rss><channel><item><title>Acme: Laravel Developer</title><region>Anywhere in the World</region>
               <link>https://weworkremotely.com/remote-jobs/acme-laravel-developer</link><pubDate>Sun, 01 Nov 2026 10:00:00 +0000</pubDate>
               <description>&lt;p&gt;PHP and Laravel APIs, remote. 2 years of experience.&lt;/p&gt;</description></item></channel></rss>""",
        ).single()
        assertEquals("Acme", wwr.company)
        assertEquals("Laravel Developer", wwr.title)
        assertEquals("2026-11-01T10:00:00Z", wwr.postedAt)
        assertNull(kept(wwr).rejected)

        val remoteOk = RemoteFeeds.remoteOk(
            """[{"legal":"terms"},{"id":"5","position":"Golang Developer","company":"Acme","location":"Worldwide",
                "description":"Backend in Golang, remote worldwide. 3 years of experience.","url":"https://remoteok.com/remote-jobs/5","date":"2026-11-01T00:00:00+00:00"}]""",
        )
        assertEquals(1, remoteOk.size)

        val nomads = RemoteFeeds.workingNomads(
            """[{"url":"https://www.workingnomads.com/job/go/1981017/","title":"PHP Developer","company_name":"Lemon.io",
                "location":"Europe, LATAM, APAC, the U.S., Canada","description":"Laravel.","pub_date":"2026-10-29T09:50:51-04:00"}]""",
        ).single()
        assertEquals("1981017", nomads.id)
        assertEquals("region-locked", kept(nomads).rejected)
    }

    @Test
    fun `Arbeitnow is on-site in Germany and needs a visa to pass`() {
        val job = RemoteFeeds.arbeitnow(
            """{"data":[{"slug":"backend-php-berlin-1","title":"Backend Developer (PHP)","company_name":"Acme","location":"Berlin",
                "remote":false,"url":"https://www.arbeitnow.com/jobs/1","description":"<p>PHP and Laravel, in the office.</p>","created_at":1793500000}]}""",
        ).single()
        assertEquals("Berlin, Germany", job.location)
        assertFalse(job.remote)
        assertEquals("no-lane", kept(job).rejected)
    }

    @Test
    fun `Hacker News reads the hiring thread, top-level ads only, VISA in the header`() {
        assertEquals(
            "1",
            HnHiring.latestThread("""{"hits":[{"objectID":"2","title":"Ask HN: Who wants to be hired? (November 2026)"},{"objectID":"1","title":"Ask HN: Who is hiring? (November 2026)"}]}"""),
        )
        val ads = HnHiring.ads(
            """{"hits":[
                {"objectID":"10","parent_id":1,"created_at":"2026-11-01T16:00:00Z",
                 "comment_text":"Acme | Backend Engineer (PHP/Laravel) | London, UK | ONSITE | VISA<p>We build payment APIs with PHP and Laravel, MySQL. 2+ years of experience."},
                {"objectID":"11","parent_id":10,"created_at":"2026-11-01T17:00:00Z","comment_text":"Is this still open?"}
            ]}""",
            "1",
        )
        val ad = ads.single()
        assertEquals("Acme", ad.company)
        assertEquals("Backend Engineer (PHP/Laravel)", ad.title)
        assertEquals("https://news.ycombinator.com/item?id=10", ad.url)
        val judged = kept(ad, HnHiring.KEY)
        assertNull(judged.rejected)
        assertEquals(AbroadFit.VISA_LANE, judged.item.market)
    }

    @Test
    fun `Adzuna, Jooble and Careerjet answers become jobs`() {
        val adzuna = JobApis.adzuna(
            """{"results":[{"id":"123","title":"<strong>Laravel</strong> Developer","company":{"display_name":"Acme"},
                "location":{"display_name":"Sydney, New South Wales"},"redirect_url":"https://www.adzuna.com.au/land/ad/123",
                "description":"PHP Laravel role, visa sponsorship available. 2 years of experience.","created":"2026-11-01T08:00:00Z"}]}""",
            "Australia",
        ).single()
        assertEquals("Laravel Developer", adzuna.title)
        assertEquals("Sydney, New South Wales, Australia", adzuna.location)
        val sydney = kept(adzuna, JobApis.KEY)
        assertNull(sydney.rejected)
        assertEquals("🇦🇺 Australia", AbroadFit.regionOf(sydney.item.location))

        val jooble = JobApis.jooble(
            """{"jobs":[{"id":-456,"title":"Node.js Developer","location":"Toronto, ON","company":"Acme","link":"https://jooble.org/desc/456",
                "snippet":"Node.js and NestJS. Remote, open to EMEA. 3+ years of experience.","updated":"2026-11-01T08:00:00.0000000"}]}""",
        ).single()
        assertTrue(jooble.remote)
        assertEquals("2026-11-01T08:00:00Z", jooble.postedAt)
        assertNull(JobApis.joobleDate(""))
        assertNull(kept(jooble, JobApis.KEY).rejected)

        val careerjet = JobApis.careerjet(
            """{"type":"JOBS","jobs":[{"title":"PHP Developer","company":"Acme","locations":"Dublin","url":"https://jobviewtrack.com/x",
                "description":"Laravel and PHP. Visa sponsorship provided. 2 years of experience.","date":"Sun, 01 Nov 2026 08:00:00 GMT"}]}""",
        ).single()
        assertEquals("2026-11-01T08:00:00Z", careerjet.postedAt)
        // Careerjet's own example: no space after the comma, and a weekday that does not match the date.
        assertEquals("2025-11-15T19:13:43Z", JobApis.careerjetDate("Wed,15 Nov 2025 19:13:43 GMT"))
        assertEquals("United Kingdom", JobApis.CAREERJET_LOCALES["en_GB"])
        assertNull(JobApis.careerjetParams("Laravel visa sponsorship", "en_GB")["affid"])
        assertNull(kept(careerjet, JobApis.KEY).rejected)
    }

    @Test
    fun `the job APIs wait for their keys`() {
        assertFalse(JobApis.Keys("", "", "", "").any)
        assertFalse(JobApis.Keys("id", "", "", "").adzuna)
        assertTrue(JobApis.Keys("id", "key", "", "").any)
        assertEquals(8, JobApis.QUERIES.size)
        assertEquals("Laravel visa sponsorship", JobApis.QUERIES.first())
    }
}
