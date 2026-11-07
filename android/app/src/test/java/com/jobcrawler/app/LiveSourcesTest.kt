package com.jobcrawler.app

import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.DrJob
import com.jobcrawler.app.sources.Jobgether
import com.jobcrawler.app.sources.LinkedInJobs
import com.jobcrawler.app.sources.Recruitera
import com.jobcrawler.app.sources.RemoteFeeds
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import com.jobcrawler.app.sources.Wuzzuf
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits the real sites, to check the parsers still match their markup.
 * Skipped unless LIVE=1:  LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveSourcesTest*'
 */
class LiveSourcesTest {
    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("LIVE") == "1")

    @Test
    fun linkedin() {
        val client = GuestClient("LinkedIn")
        val html = client.get(
            "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search",
            mapOf("keywords" to "PHP Developer", "location" to "Egypt", "f_TPR" to "r604800"),
        )
        val cards = LinkedInJobs.parseCards(html, "PHP Developer", "Egypt")
        println("linkedin: ${client.lastResponse}, ${cards.size} cards, first: ${cards.firstOrNull()}")
        cards.firstOrNull()?.let {
            val (description, criteria) = LinkedInJobs.parseDetail(
                client.get("https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/${it.externalId}"),
            )
            println("linkedin detail: ${description?.take(120)} $criteria")
        }
    }

    @Test
    fun workable() {
        val client = GuestClient("Workable", headers = mapOf("Accept" to "application/json"))
        val body = client.get("https://jobs.workable.com/api/v1/jobs", mapOf("query" to "Backend", "location" to "Egypt", "day_range" to 30))
        val (items, next) = Workable.parse(body, "Backend", "Egypt")
        println("workable: ${client.lastResponse}, ${items.size} jobs, next=$next, first: ${items.firstOrNull()?.copy(excerpt = null)}")
    }

    @Test
    fun tanqeeb() {
        val client = GuestClient("Tanqeeb")
        val html = client.get("https://egypt.tanqeeb.com/jobs/search", mapOf("keywords" to "php", "page" to 1, "order_by" to "most_recent"))
        val items = Tanqeeb.parseSearch(html, "php")
        println("tanqeeb: ${client.lastResponse}, ${items.size} cards, first: ${items.firstOrNull()}")
        items.firstOrNull()?.let { println("tanqeeb page: ${Tanqeeb.parsePage(client.get(it.url)).let { (c, e) -> "$c / ${e?.take(120)}" }}") }
    }

    @Test
    fun drjob() {
        val client = GuestClient("Dr.Job")
        for (site in DrJob.sites(gulf = true)) {
            val items = DrJob.parseList(client.get("${site.base}/php-developer-jobs"), "PHP Developer", site)
            println("drjob ${site.market}: ${client.lastResponse}, ${items.size} cards, first: ${items.firstOrNull()?.copy(excerpt = null)}")
        }
        val first = DrJob.parseList(client.get("${DrJob.EGYPT.base}/backend-developer-jobs"), "Backend Developer").firstOrNull()
        first?.let { println("drjob page: ${DrJob.parseDetail(client.get(it.url))?.let { d -> d.copy(description = d.description?.take(120)) }}") }
    }

    @Test
    fun jobgether() {
        val client = GuestClient("Jobgether", headers = mapOf("Accept" to "application/json"))
        val cards = Jobgether.parseList(client.get(Jobgether.API_URL, Jobgether.params("Backend Developer")))
        println("jobgether: ${client.lastResponse}, ${cards.size} jobs, first: ${cards.firstOrNull()}")
        cards.firstOrNull()?.let {
            val offer = Jobgether.parseOffer(client.get(it.url))
            println("jobgether offer: EG=${"EG" in offer.countries} (${offer.countries.size} countries), ${offer.description.take(120)}")
        }
    }

    @Test
    fun remoteRocketship() {
        val client = GuestClient("Remote Rocketship")
        for (feed in RemoteFeeds.FEEDS.filter { it.id == "rocketship" }) {
            val jobs = feed.parse(client.get(feed.url))
            println("rocketship ${feed.url}: ${client.lastResponse}, ${jobs.size} jobs, first: ${jobs.firstOrNull()?.copy(description = "…")}")
        }
    }

    @Test
    fun recruitera() {
        val client = GuestClient("Recruitera", headers = mapOf("Accept" to "application/json"))
        val sites = Recruitera.parseSitemapSites(client.get("https://app.recruitera.ai/sitemap.xml"))
        println("recruitera: ${client.lastResponse}, ${sites.size} sites, first: ${sites.take(5)}")
        val categories = Recruitera.pickCategories(client.get("https://app.recruitera.ai/api/public/v1/paymob/opportunities/filters"))
        println("recruitera paymob tech categories: $categories")
    }

    @Test
    fun ats() {
        val client = GuestClient("ATS", timeoutMs = 30_000)
        for (entry in listOf("lever:klivvr:Klivvr", "greenhouse:tamara", "teamtailor:dubizzle:Dubizzle", "zoho:qutip:QuTip")) {
            val board = Ats.parseBoard(entry)!!
            val jobs = Ats.parseBoardResponse(client.get(Ats.urlOf(board)), board)
            println("ats $entry: ${client.lastResponse}, ${jobs?.size} jobs, first: ${jobs?.firstOrNull()?.copy(excerpt = null)}")
        }
    }

    @Test
    fun wuzzuf() {
        val client = GuestClient("Wuzzuf", timeoutMs = 30_000)
        val xml = client.get("https://wuzzuf.net/sitemap-job-1.xml")
        val url = Regex("<loc>([^<]*/jobs/p/[^<]*php[^<]*)</loc>").find(xml)?.groupValues?.get(1)
        println("wuzzuf sitemap: ${client.lastResponse}, php url: $url")
        url?.let { println("wuzzuf page: ${Wuzzuf.parsePage(client.get(it), it)?.copy(excerpt = null)}") }
    }
}
