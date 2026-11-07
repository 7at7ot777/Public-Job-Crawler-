package com.jobcrawler.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jobcrawler.app.sources.AbroadFit
import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.RemoteFeeds
import com.jobcrawler.app.sources.SponsorBoards
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Visa-sponsor companies' own boards, and Landing.jobs. */
class SponsorBoardsTest {
    private fun board(title: String, location: String, excerpt: String, workplace: String? = null) = Item(
        source = Ats.KEY,
        externalId = "greenhouse:monzo:1",
        title = title,
        url = "https://job-boards.greenhouse.io/monzo/jobs/1",
        company = "Monzo",
        location = location,
        excerpt = excerpt,
        workplace = workplace,
    )

    @Test
    fun `every board is one the company-board readers understand`() {
        assertEquals(SponsorBoards.BOARDS.size, SponsorBoards.BOARDS.mapNotNull { Ats.parseBoard(it) }.size)
        assertTrue(SponsorBoards.BOARDS.all { it.startsWith("greenhouse:") || it.startsWith("lever:") || it.startsWith("ashby:") })
    }

    @Test
    fun `Doctolib is read from Ashby, where it moved, not its dead Greenhouse board`() {
        assertTrue("ashby:doctolib:Doctolib" in SponsorBoards.BOARDS)
        assertFalse(SponsorBoards.BOARDS.any { it.startsWith("greenhouse:doctolib") })
        assertEquals(
            "https://api.ashbyhq.com/posting-api/job-board/doctolib",
            Ats.urlOf(Ats.parseBoard("ashby:doctolib:Doctolib")!!),
        )
    }

    @Test
    fun `no board is listed twice`() {
        val keys = SponsorBoards.BOARDS.map { it.substringBeforeLast(':').lowercase() }
        assertEquals(keys.size, keys.toSet().size)
        val companies = SponsorBoards.BOARDS.map { it.substringAfterLast(':').lowercase() }
        assertEquals(companies.size, companies.toSet().size)
    }

    @Test
    fun `Doctolib's Ashby feed, as it answers today, is read`() {
        val body = """{"apiVersion":"1","jobs":[{
            "id":"c1f9fb78-7ad2-450f-9da2-9e0c46fd07e6","title":"Backend Engineer (x/f/m)",
            "department":"Tech","team":"Engineering","employmentType":"FullTime",
            "location":"Paris","secondaryLocations":[],"isListed":true,"isRemote":true,"workplaceType":"Hybrid",
            "address":{"postalAddress":{"addressLocality":"Paris","addressRegion":"Île-de-France","addressCountry":"France"}},
            "publishedAt":"2026-09-30T10:14:45.257+00:00",
            "jobUrl":"https://jobs.ashbyhq.com/doctolib/c1f9fb78-7ad2-450f-9da2-9e0c46fd07e6",
            "applyUrl":"https://jobs.ashbyhq.com/doctolib/c1f9fb78-7ad2-450f-9da2-9e0c46fd07e6/application",
            "descriptionHtml":"<p>Ruby on Rails APIs.</p>","descriptionPlain":"Ruby on Rails APIs."}]}"""
        val job = Ats.parseBoardResponse(body, Ats.parseBoard("ashby:doctolib:Doctolib")!!)!!.single()
        assertEquals("ashby:doctolib:c1f9fb78-7ad2-450f-9da2-9e0c46fd07e6", job.externalId)
        assertEquals("Doctolib", job.company)
        assertEquals("Paris, France", job.location)
        assertEquals("Hybrid", job.workplace)
        assertEquals("2026-09-30T10:14:45.257Z", job.postedAt)
    }

    @Test
    fun `a board that answered nothing says so, not the last board's 200`() {
        val board = Ats.parseBoard("greenhouse:doctolib:Doctolib")!!
        val dead = SponsorBoards.noFeed(board, "", "HTTP 200, 265613 chars, application/json")
        assertTrue(dead.contains("greenhouse:doctolib answered nothing"))
        assertFalse(dead.contains("HTTP 200"))
        val odd = SponsorBoards.noFeed(board, "<html>", "HTTP 200, 6 chars, text/html")
        assertTrue(odd.contains("returned no feed (HTTP 200, 6 chars, text/html)"))
    }

    @Test
    fun `a London backend job at a known sponsor is the visa lane, though the posting never says visa`() {
        val job = SponsorBoards.asJob(board("Backend Engineer", "London, UK", "We build APIs in Go and PHP. 3+ years of experience."), "Monzo")
        val judged = RemoteFeeds.judge(job, SponsorBoards.KEY)
        assertNull(judged.rejected)
        assertEquals(AbroadFit.VISA_LANE, judged.item.market)
        assertEquals("Company board, known visa sponsor", judged.item.board)
        assertTrue(judged.item.excerpt!!.contains("Known visa sponsor"))
    }

    @Test
    fun `a remote US-only job at a known sponsor is still out`() {
        val job = SponsorBoards.asJob(board("Backend Engineer", "Remote - US", "Go and PHP APIs. 3 years of experience.", "Remote"), "Monzo")
        assertEquals("region", RemoteFeeds.judge(job, SponsorBoards.KEY).rejected)
    }

    @Test
    fun `Landing jobs gives the place, the company and a paid relocation`() {
        val job = RemoteFeeds.landingJobs(
            """[{"id":19066,"title":"Backend Developer (PHP)","url":"https://landing.jobs/at/acme-labs/backend-developer-in-lisbon-2026",
                "locations":[{"city":"Lisbon","country_code":"PT"}],"tags":["PHP","Laravel","MySQL"],"remote":false,"relocation_paid":true,
                "role_description":"<p>Build our APIs.</p>","main_requirements":"<p>2+ years of experience with Laravel.</p>",
                "published_at":"2026-11-01T09:38:38.127Z"}]""",
        ).single()
        assertEquals("Lisbon, Portugal", job.location)
        assertEquals("Acme labs", job.company)
        assertTrue(job.description.contains("Relocation package provided"))
        val judged = RemoteFeeds.judge(job, RemoteFeeds.KEY)
        assertNull(judged.rejected)
        assertEquals(AbroadFit.VISA_LANE, judged.item.market)
    }
}

/** A whole run against stand-in boards: one dead board is a warning, the rest still count. */
@RunWith(RobolectricTestRunner::class)
class SponsorBoardsFetchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store

    @Before
    fun open() {
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
        store = Store.shared(context)
    }

    @After
    fun close() {
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
    }

    private val recent = Instant.now().minus(2, ChronoUnit.DAYS).toString()

    private fun ashby(id: String) = """{"jobs":[{"id":"$id","title":"Backend Engineer","location":"London",
        "address":{"postalAddress":{"addressLocality":"London","addressCountry":"United Kingdom"}},
        "isListed":true,"workplaceType":"Hybrid","publishedAt":"$recent",
        "jobUrl":"https://jobs.ashbyhq.com/acme/$id",
        "descriptionPlain":"We build APIs in Go and PHP. 3+ years of experience."}]}"""

    @Test
    fun `a 404 board, a broken one and an unreachable one are skipped, the live board is still judged`() {
        val asked = mutableListOf<String>()
        val source = SponsorBoards(
            boards = listOf(
                "greenhouse:gone:Gone",
                "lever:broken:Broken",
                "greenhouse:unreachable:Unreachable",
                "ashby:acme:Acme",
            ),
            read = { url ->
                asked += url
                when {
                    "/gone/" in url -> "" // GuestClient's answer to a 404
                    "/broken" in url -> "<html>Not a feed</html>"
                    "/unreachable/" in url -> throw BlockedException("connection reset", transport = true)
                    else -> ashby("1")
                }
            },
        )
        val kept = source.fetch(emptyList(), store)
        assertEquals(4, asked.size)
        val job = kept.singleOrNull()
        assertNotNull("the live board's job was kept: $kept", job)
        assertEquals("Acme", job!!.company)
        assertEquals(AbroadFit.VISA_LANE, job.market)
    }

    @Test
    fun `a block that is not one board's transport stops the run and hands back what it had`() {
        val source = SponsorBoards(
            boards = listOf("ashby:acme:Acme", "ashby:throttled:Throttled", "ashby:later:Later"),
            read = { url ->
                if ("/throttled" in url) throw BlockedException("Sponsor boards blocked the request (HTTP 429)")
                ashby(url.substringAfterLast('/'))
            },
        )
        val error = runCatching { source.fetch(emptyList(), store) }.exceptionOrNull() as BlockedException
        assertEquals(1, error.partialItems.size)
    }
}
