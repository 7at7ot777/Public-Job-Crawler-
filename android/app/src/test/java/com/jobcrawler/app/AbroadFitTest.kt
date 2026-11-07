package com.jobcrawler.app

import com.jobcrawler.app.sources.AbroadFit
import com.jobcrawler.app.sources.AbroadJobs
import com.jobcrawler.app.sources.Glassdoor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Europe, Canada, Australia: remote open to Egypt or a sponsored visa, English, the CV's stack and level. */
class AbroadFitTest {
    private fun card(title: String, location: String, id: String = "1") = Item(
        source = AbroadJobs.KEY,
        externalId = id,
        title = title,
        url = "https://www.linkedin.com/jobs/view/$id",
        company = "Acme",
        location = location,
    )

    private val berlinBody = "We build payment APIs with PHP and Laravel, MySQL, Redis and Docker. " +
        "2+ years of experience. Visa sponsorship and relocation package provided. Our team works in English."

    @Test
    fun `a Berlin Laravel job with visa sponsorship is kept, high`() {
        val judged = AbroadJobs.judge(card("Laravel Developer", "Berlin, Germany"), berlinBody, emptyMap(), remote = false)
        assertNull(judged.rejected)
        assertEquals(AbroadFit.VISA_LANE, judged.item.market)
        assertEquals("PHP, Laravel", judged.item.keyword)
        assertTrue(AbroadFit.score(judged.item) >= 80)
        assertTrue(Filters.evaluate(judged.item).pass)
        assertEquals("🇪🇺 Europe", AbroadFit.regionOf(judged.item.location))
    }

    @Test
    fun `a remote EMEA job is the remote lane`() {
        val judged = AbroadJobs.judge(
            card("Backend Engineer (Node.js)", "European Union"),
            "Fully remote role open to candidates in EMEA, UTC+2 friendly. Node.js, NestJS, TypeScript, PostgreSQL, Docker, AWS. 3+ years of experience.",
            emptyMap(),
            remote = true,
        )
        assertNull(judged.rejected)
        assertEquals(AbroadFit.REMOTE_LANE, judged.item.market)
        assertTrue(AbroadFit.score(judged.item) >= AbroadFit.MIN_SCORE)
    }

    @Test
    fun `Canada with LMIA support is kept, and the note names Canada`() {
        val judged = AbroadJobs.judge(
            card("PHP Developer", "Toronto, Ontario, Canada"),
            "Laravel and PHP backend role. LMIA support for international candidates. 2 years of experience.",
            emptyMap(),
            remote = false,
        )
        assertNull(judged.rejected)
        assertEquals("🇨🇦 Canada", AbroadFit.regionOf(judged.item.location))
        assertTrue(CoverNote.situation(judged.item)!!.contains("relocate to Canada"))
    }

    @Test
    fun `another language, a German text or a region lock is out`() {
        assertEquals("language", AbroadFit.blocked("Laravel developer. German (C1) is required."))
        assertEquals("language", AbroadFit.blocked("Laravel developer. Fluent English and Dutch."))
        assertEquals("language", AbroadFit.blocked("PHP. Sehr gute Deutschkenntnisse."))
        assertNull(AbroadFit.blocked("Laravel developer. Fluent German is a plus."))
        assertNull(AbroadFit.blocked(berlinBody))

        val german = "Wir suchen einen Laravel Entwickler für unser Team in Berlin. Du arbeitest mit PHP und Laravel " +
            "und baust APIs für unsere Kunden. Wir bieten dir ein Visum und Unterstützung bei der Relocation. Die Arbeit ist " +
            "spannend und das Team ist nett und wir freuen uns auf dich und deine Bewerbung mit uns."
        assertEquals("not-english", AbroadFit.blocked(german))

        assertEquals("region-locked", AbroadFit.blocked("Remote Laravel developer, UK only."))
        assertEquals("region-locked", AbroadFit.blocked("Sydney. You must be an Australian citizen or permanent resident."))
        // The company's own place is not a lock.
        assertNull(AbroadFit.blocked("We are a UK-based fintech; our office is located in London. Visa sponsorship available."))
    }

    @Test
    fun `remote jobs limited to the US or Canada are not open to Egypt`() {
        // The hole this closes: "remote, US and Canada only" used to pass because it was neither
        // US-only nor a known region lock, yet "globally distributed team" satisfied the open gate.
        assertEquals("region-locked", AbroadFit.blocked("Laravel developer. Globally distributed team. Remote, US and Canada only."))
        assertEquals("region-locked", AbroadFit.blocked("Remote PHP role, open to candidates in the US or Canada."))
        assertEquals("region-locked", AbroadFit.blocked("Fully remote Laravel role. North America only."))
        assertEquals("region-locked", AbroadFit.blocked("Remote Node.js role for North American candidates."))
        assertEquals("region-locked", AbroadFit.blocked("Remote PHP developer. Canada only."))
        // Genuinely open roles still pass.
        assertNull(AbroadFit.blocked("Laravel developer. Remote, work from anywhere in the world."))
        assertNull(AbroadFit.blocked("Remote Node.js role open to EMEA and Africa."))
        // Customer geography ("across North America") is not a candidate lock on an open role.
        assertNull(AbroadFit.blocked("Our customers are across North America. Remote role open to anyone, worldwide."))
    }

    @Test
    fun `too senior, too many years or another stack is out`() {
        assertFalse(AbroadJobs.titleFits(card("Senior Laravel Developer", "London")))
        assertTrue(AbroadJobs.titleFits(card("Laravel Developer", "London")))
        val years = AbroadJobs.judge(card("Laravel Developer", "London, UK"), "PHP Laravel. 6+ years of experience. Visa sponsorship.", emptyMap(), false)
        assertEquals("years", years.rejected)
        val java = AbroadJobs.judge(card("Backend Developer", "London, UK"), "Java Spring Boot. Visa sponsorship available.", emptyMap(), false)
        assertEquals("stack", java.rejected)
        val noLane = AbroadJobs.judge(card("Laravel Developer", "London, UK"), "PHP and Laravel, on site in London.", emptyMap(), false)
        assertEquals("no-lane", noLane.rejected)
    }

    @Test
    fun `only the best ten fits go out, best first`() {
        val good = (1..12).map { i ->
            card("Laravel Developer", "Berlin, Germany", "g$i").copy(market = AbroadFit.VISA_LANE, excerpt = berlinBody + " Docker ".repeat(i % 3))
        }
        val weak = card("Software Engineer", "Berlin, Germany", "w").copy(market = AbroadFit.VISA_LANE, excerpt = "Visa.")
        val best = AbroadFit.best(good + weak)
        assertEquals(AbroadFit.MAX_PER_RUN, best.size)
        assertFalse(weak in best)
        assertEquals(best.map { AbroadFit.score(it) }.sortedDescending(), best.map { AbroadFit.score(it) })
    }

    @Test
    fun `the remote search asks LinkedIn for remote jobs, the visa one does not`() {
        assertEquals("2", AbroadJobs.params("Laravel", "Canada", remote = true)["f_WT"])
        assertNull(AbroadJobs.params("Laravel visa sponsorship", "Canada", remote = false)["f_WT"])
        assertEquals(listOf("European Union", "United Kingdom", "Canada", "Australia"), AbroadJobs.DEFAULT_PLACES)
    }

    @Test
    fun `abroad jobs go out from the second Gmail with their fit shown, Egypt jobs as before`() {
        val item = AbroadJobs.judge(card("Laravel Developer", "Berlin, Germany"), berlinBody, emptyMap(), false).item
        assertTrue(Mailer.isAbroad(item))
        val profile = ApplySettings(gmail = "me@example.com", appPassword = "x", gulfGmail = "abroad@example.com", gulfAppPassword = "y")
        assertEquals("abroad@example.com", Mailer.autoSender(item, profile)?.gmail)
        val text = Formatter.format(item)
        assertTrue(text, text.contains("🎯 CV fit"))
        assertTrue(text, text.contains("🇪🇺 Europe"))

        val egypt = item.copy(source = "linkedin_jobs", market = "Egypt")
        assertFalse(Mailer.isAbroad(egypt))
        assertFalse(Formatter.format(egypt).contains("CV fit"))
    }

    @Test
    fun `Glassdoor searches Canada and Australia too`() {
        assertEquals(listOf("ca", "au"), Glassdoor.QUERIES.filter { it.country in setOf("ca", "au") }.map { it.country })
    }
}
