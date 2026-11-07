package com.jobcrawler.app

import com.jobcrawler.app.sources.CvStack
import com.jobcrawler.app.sources.Jobright
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobrightTest {

    /** One listed job, shaped as jobright's __NEXT_DATA__ ships it. */
    private fun job(
        id: String,
        title: String,
        summary: String,
        requirements: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        remote: Boolean = false,
        seniority: String? = "Mid Level",
        years: Int? = 2,
        type: String = "Full-time",
        location: String = "Austin, TX",
        company: String = "Acme",
    ): JSONObject {
        val result = JSONObject()
            .put("jobId", id)
            .put("jobTitle", title)
            .put("jobSummary", summary)
            .put("requirements", JSONArray(requirements))
            .put("coreResponsibilities", JSONArray())
            .put("recommendationTags", JSONArray(tags))
            .put("isRemote", remote)
            .put("workModel", if (remote) "Remote" else "Onsite")
            .put("jobLocation", location)
            .put("jobLocations", JSONArray(listOf(location)))
            .put("publishTime", "2026-11-01 14:01:05")
            .put("employmentType", type)
            .put("salaryDesc", "120K/yr")
        seniority?.let { result.put("jobSeniority", it) }
        years?.let { result.put("minYearsOfExperience", it) }
        return JSONObject()
            .put("jobResult", result)
            .put("companyResult", JSONObject().put("companyName", company))
    }

    private fun page(listKey: String, vararg jobs: JSONObject): String {
        val data = JSONObject().put(
            "props",
            JSONObject().put("pageProps", JSONObject().put(listKey, JSONArray(jobs.toList())).put("totalJobs", 5958)),
        )
        return """<html><head></head><body><div id="__next"></div>""" +
            """<script id="__NEXT_DATA__" type="application/json">$data</script></body></html>"""
    }

    private val visaPhp = job(
        "a1", "Backend Engineer (PHP)",
        summary = "Acme is hiring a backend engineer to build Laravel APIs for its payments platform.",
        requirements = listOf("2+ years with PHP and Laravel", "MySQL, Redis and REST API design", "Docker and AWS"),
        tags = listOf("H1B Sponsor Likely", "Growth Opportunities"),
    )
    private val remoteGo = job(
        "a2", "Golang Engineer",
        summary = "Fully remote role on a globally distributed team; we hire anywhere in the world.",
        requirements = listOf("Experience with Go and PostgreSQL", "Comfortable working with European time zones"),
        tags = listOf("No H1B"),
        remote = true,
        location = "United States",
    )
    private val usOnlyNode = job(
        "a3", "Full Stack Engineer",
        summary = "Remote within the United States. Node.js and React.",
        tags = listOf("No H1B"),
        remote = true,
        location = "United States",
    )
    private val clearance = job(
        "a4", "Software Engineer, Defense",
        summary = "Node.js services for defense customers.",
        tags = listOf("H1B Sponsor Likely", "Security Clearance Required"),
    )
    private val tooSenior = job(
        "a5", "Backend Engineer, Platform",
        summary = "Laravel and PHP at scale.",
        tags = listOf("H1B Sponsored"),
        seniority = "Senior Level",
        years = 7,
    )
    private val javaOnly = job(
        "a6", "Backend Engineer",
        summary = "Java and Spring Boot microservices; we go live weekly with a go-to-market team.",
        tags = listOf("H1B Sponsor Likely"),
    )
    private val intern = job(
        "a7", "Software Engineer Intern (Winter 2027)",
        summary = "Node.js internship.",
        tags = listOf("H1B Sponsor Likely"),
        years = null,
    )

    @Test
    fun `a category page keeps only jobs with a lane, the CV's stack and 1-3 years`() {
        val parsed = Jobright.parse(page("jobList", visaPhp, remoteGo, usOnlyNode, clearance, tooSenior, javaOnly, intern))
        val reasons = parsed.associate { it.item.externalId to it.rejected }

        assertEquals(7, parsed.size)
        assertNull(reasons["a1"])
        assertNull(reasons["a2"])
        assertEquals("no-lane", reasons["a3"])
        assertEquals("citizen-or-clearance", reasons["a4"])
        assertEquals("years", reasons["a5"])
        assertEquals("stack", reasons["a6"])
        assertEquals("internship", reasons["a7"])

        val visa = parsed.first { it.item.externalId == "a1" }
        assertEquals(Jobright.Lane.VISA, visa.lane)
        assertEquals("PHP, Laravel", visa.item.keyword)
        assertEquals("https://jobright.ai/jobs/info/a1", visa.item.url)
        assertEquals("Acme", visa.item.company)
        assertEquals("2026-11-01T14:01:05Z", visa.item.postedAt)
        assertEquals(Jobright.Lane.VISA.label, visa.item.market)
        assertTrue(visa.item.excerpt!!.startsWith(Jobright.Lane.VISA.label))

        // "No H1B" does not matter when the job is open to the world anyway.
        val remote = parsed.first { it.item.externalId == "a2" }
        assertEquals(Jobright.Lane.REMOTE, remote.lane)
        assertEquals("Go", remote.item.keyword)
        assertTrue(remote.item.remote)
    }

    @Test
    fun `every location jobright has a page for is searched`() {
        assertEquals(4, Jobright.PAGES.size)
        assertEquals(Jobright.CATEGORIES.size * Jobright.CITIES.size, Jobright.CITY_PAGES.size)
        assertEquals(33, Jobright.CITY_PAGES.size)
        assertTrue(Jobright.CITY_PAGES.contains("https://jobright.ai/jobs/golang-engineer-in-chicago-il"))
        assertEquals(Jobright.CITY_PAGES.size, Jobright.CITY_PAGES.toSet().size)
    }

    @Test
    fun `the remote page ships its jobs as defaultData`() {
        val parsed = Jobright.parse(page("defaultData", remoteGo))
        assertEquals(1, parsed.size)
        assertNull(parsed[0].rejected)
    }

    @Test
    fun `a page without its data is no jobs`() {
        assertEquals(0, Jobright.parse("<html>Just a moment...</html>").size)
        assertEquals(0, Jobright.parse("""<script id="__NEXT_DATA__">{not json</script>""").size)
    }

    @Test
    fun `best CV fit first`() {
        val parsed = Jobright.parse(page("jobList", remoteGo, visaPhp)).filter { it.rejected == null }
        assertEquals(listOf("a1", "a2"), Jobright.ranked(parsed).map { it.externalId })
    }

    @Test
    fun `Go the language, not go the verb`() {
        fun stack(text: String) = CvStack.of("Backend Engineer\n$text")
        assertEquals(listOf("Go"), stack("Services written in Go, deployed on Kubernetes"))
        assertEquals(listOf("Go"), stack("Experience with Go or Rust"))
        assertEquals(emptyList<String>(), stack("We go live weekly and own go-to-market"))
        assertEquals(emptyList<String>(), stack("Go beyond the spec."))
        assertEquals(listOf("Node.js", "TypeScript"), stack("NodeJS and TypeScript"))
    }

    @Test
    fun `a remote job for US residents only is no lane, a sponsored one is still the visa lane`() {
        val usOnly = Filters.fold("remote, must be us-based. emea experience a plus")
        assertNull(Jobright.laneOf(emptyList(), usOnly, remote = true))
        assertEquals(Jobright.Lane.VISA, Jobright.laneOf(listOf("h1b sponsored"), usOnly, remote = true))
        assertEquals(Jobright.Lane.VISA, Jobright.laneOf(emptyList(), Filters.fold("Relocation package provided"), remote = false))
        assertNull(Jobright.laneOf(listOf("no h1b"), Filters.fold("Relocation package provided"), remote = false))
    }

    @Test
    fun `jobright prints UTC`() {
        assertEquals("2026-11-01T14:01:05Z", Jobright.posted("2026-11-01 14:01:05"))
        assertNull(Jobright.posted(""))
        assertNull(Jobright.posted("2 weeks ago"))
    }

    @Test
    fun `a kept job passes the shared filters, and an address in it is picked up`() {
        val withMail = job(
            "a8", "Backend Developer",
            summary = "Laravel APIs. Visa sponsorship available. Send your CV to careers@acme.io",
        )
        val parsed = Jobright.parse(page("jobList", visaPhp, remoteGo, withMail)).filter { it.rejected == null }
        assertEquals(3, parsed.size)
        for (job in parsed) assertTrue(job.item.title, Filters.evaluate(job.item).pass)
        assertEquals(listOf("careers@acme.io"), Filters.evaluate(parsed.first { it.item.externalId == "a8" }.item).emails)
    }

    @Test
    fun `the cover note says where the person is and what the job would take`() {
        val parsed = Jobright.parse(page("jobList", visaPhp, remoteGo)).associateBy { it.item.externalId }
        val profile = ApplySettings(name = "Mohamed", phone = "", gmail = "me@example.com", appPassword = "x")

        val visaNote = CoverNote.text(parsed.getValue("a1").item, profile, withCv = true)
        assertTrue(visaNote, visaNote.contains("would need visa sponsorship"))
        val remoteNote = CoverNote.text(parsed.getValue("a2").item, profile, withCv = true)
        assertTrue(remoteNote, remoteNote.contains("European working hours"))
        assertNull(CoverNote.situation(Item(source = "wuzzuf", externalId = "1", title = "PHP Developer", url = "https://x")))
    }

    @Test
    fun `jobright emails go from the second Gmail, behind their own switch`() {
        val profile = ApplySettings(gmail = "me@example.com", appPassword = "x", gulfGmail = "gulf@example.com", gulfAppPassword = "y")
        assertEquals("gulf@example.com", Mailer.autoSender(abroadJob(Jobright.KEY), profile)?.gmail)
        // By hand too, and the main account when no second one is set.
        assertEquals("gulf@example.com", Mailer.senderFor(abroadJob(Jobright.KEY), profile).gmail)
        assertEquals("me@example.com", Mailer.senderFor(abroadJob(Jobright.KEY), profile.copy(gulfGmail = "")).gmail)
        assertEquals("me@example.com", Mailer.senderFor(abroadJob("wuzzuf"), profile).gmail)
        assertNull(Mailer.autoSender(abroadJob(Jobright.KEY), profile.copy(abroadAutoEmail = false)))
        assertEquals("gulf@example.com", Mailer.autoSender(abroadJob("gulf_jobs"), profile)?.gmail)
        assertNull(Mailer.autoSender(abroadJob("wuzzuf"), profile))
    }

    @Test
    fun `the channel message names the lane`() {
        val item = Jobright.parse(page("jobList", visaPhp))[0].item
        val text = Formatter.format(item)
        assertTrue(text, text.contains(Jobright.Lane.VISA.label))
        assertTrue(text, text.contains("Open on Jobright"))
        assertNotNull(text)
    }

    private fun abroadJob(source: String) = Item(source = source, externalId = "1", title = "Backend Engineer", url = "https://x")
}
