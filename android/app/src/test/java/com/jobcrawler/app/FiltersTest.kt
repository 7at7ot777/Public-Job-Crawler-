package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private fun job(
        title: String,
        excerpt: String? = "We build APIs with Laravel and PHP.",
        location: String? = "Cairo, Egypt",
        market: String? = "Egypt",
        seniority: String? = null,
    ) = Item(
        source = "linkedin_jobs",
        externalId = "1",
        title = title,
        url = "https://www.linkedin.com/jobs/view/1",
        company = "Acme",
        location = location,
        market = market,
        excerpt = excerpt,
        seniority = seniority,
    )

    @Test
    fun `a level range is open to its lower level, but a lead is still a lead`() {
        assertTrue(Filters.evaluate(job("Middle / Senior Full Stack PHP Developer")).pass)
        assertTrue(Filters.evaluate(job("Mid-Senior Laravel Developer")).pass)
        assertTrue(Filters.evaluate(job("Senior/Mid Backend Developer")).pass)
        assertTrue(Filters.evaluate(job("Junior to Senior PHP Developer")).pass)
        assertTrue(
            Filters.evaluate(job("Middle/Senior PHP Developer", excerpt = "We hire a Middle/Senior PHP developer for Laravel APIs.")).pass,
        )
        assertEquals("seniority-excluded", Filters.evaluate(job("Mid/Senior PHP Tech Lead")).reason)
        assertEquals("seniority-excluded", Filters.evaluate(job("Senior Middleware PHP Developer")).reason)
    }

    @Test
    fun `a junior Laravel job in Egypt passes`() {
        assertTrue(Filters.evaluate(job("Junior Laravel Developer")).pass)
    }

    @Test
    fun `Go jobs pass, and "go" as an everyday word does not count as Go`() {
        assertTrue(Filters.evaluate(job("Golang Developer", excerpt = "Microservices in Golang and PostgreSQL.")).pass)
        assertTrue(Filters.evaluate(job("Go Developer", excerpt = "You will write Go developer tooling.")).pass)
        assertEquals("title", Filters.evaluate(job("Go-Live Coordinator", excerpt = "Plan the go live.")).reason)
        assertEquals(
            "tech",
            Filters.evaluate(job("Software Engineer", excerpt = "Java services; help us go live this year.")).reason,
        )
    }

    @Test
    fun `term matching respects word boundaries`() {
        assertTrue(Filters.containsTerm("node.js developer", "node"))
        assertFalse(Filters.containsTerm("uses nodemon", "node"))
        // The text is folded once, as evaluate() does, before terms are matched.
        assertTrue(Filters.containsTerm(Filters.fold("We’re hiring"), "we're hiring"))
    }

    @Test
    fun `senior titles are rejected, senior colleagues in the body are not`() {
        assertEquals("seniority-excluded", Filters.evaluate(job("Senior PHP Developer")).reason)
        assertTrue(Filters.evaluate(job("PHP Developer", excerpt = "Laravel. You will report to a senior colleague.")).pass)
        assertEquals(
            "seniority-excluded-body",
            Filters.evaluate(job("PHP Developer", excerpt = "We need a senior backend developer for Laravel.")).reason,
        )
    }

    @Test
    fun `title and tech rules`() {
        assertEquals("title", Filters.evaluate(job("Accountant")).reason)
        assertEquals("tech", Filters.evaluate(job("Software Engineer", excerpt = "Salesforce Apex only.")).reason)
    }

    @Test
    fun `a named market settles location, a borderless one reads the text`() {
        assertTrue(Filters.evaluate(job("PHP Developer", location = "Remote", market = "Egypt")).pass)
        assertEquals(
            "location",
            Filters.evaluate(job("PHP Developer", location = "Berlin", market = null, excerpt = "Laravel role in Berlin")).reason,
        )
        assertTrue(Filters.evaluate(job("PHP Developer", location = "Maadi", market = null)).pass)
    }

    @Test
    fun `blocked countries and ineligible phrases`() {
        assertEquals("location-blocked", Filters.evaluate(job("PHP Developer", location = "Pune, India")).reason)
        assertEquals(
            "ineligible",
            Filters.evaluate(job("PHP Developer", excerpt = "Laravel. Must be authorized to work in the US.")).reason,
        )
        assertEquals("seniority-level", Filters.evaluate(job("PHP Developer", seniority = "Director")).reason)
    }

    @Test
    fun `Gulf residency, iqama and nationality wording means no`() {
        fun riyadh(excerpt: String) = Filters.evaluate(job("PHP Developer", location = "Riyadh", market = "Saudi Arabia", excerpt = excerpt)).reason
        assertEquals("ineligible", riyadh("Laravel role. Candidates must be currently in KSA."))
        assertEquals("ineligible", riyadh("Laravel role. Transferable Iqama is a must."))
        assertEquals("ineligible", riyadh("Laravel role. Saudization position, Saudis only."))
        assertEquals("ineligible", riyadh("Laravel role. GCC nationals only."))
        assertEquals("ineligible", riyadh("Laravel وظيفة للسعوديين فقط"))
        assertEquals("ineligible", riyadh("Laravel وظيفة، يشترط إقامة قابلة للنقل"))
        assertEquals("ineligible", riyadh("Laravel role. Must be based in Riyadh."))
        // Must already hold local residency / be present: not open to someone relocating from Egypt.
        assertEquals("ineligible", riyadh("Laravel role. Iqama is required."))
        assertEquals("ineligible", riyadh("Laravel role. Valid residence permit required."))
        assertEquals("ineligible", riyadh("Laravel role. Candidates must be locally available."))
        assertEquals("ineligible", riyadh("Laravel role. Must be in Saudi Arabia."))
        assertEquals("ineligible", riyadh("Laravel وظيفة، إقامة مطلوبة"))
        // What an employer offers is not a condition.
        assertNull(riyadh("Laravel role. Residence visa and iqama transfer fees covered."))
        // Open to relocation from Egypt still passes.
        assertNull(riyadh("Laravel role. Open to all nationalities; relocation package provided."))
    }

    @Test
    fun `a residency phrase pointing at Egypt is where the person is`() {
        assertNull(Filters.evaluate(job("PHP Developer", excerpt = "Laravel role. Must be based in Cairo.")).reason)
        assertNull(Filters.evaluate(job("PHP Developer", excerpt = "Laravel role. Candidates currently residing in Egypt.")).reason)
        // Egypt elsewhere in the text does not excuse a Gulf condition.
        assertEquals(
            "ineligible",
            Filters.evaluate(job("PHP Developer", excerpt = "Laravel role, Egypt team. Must be based in Dubai.")).reason,
        )
    }

    @Test
    fun `contacts are extracted`() {
        val verdict = Filters.evaluate(job("PHP Developer", excerpt = "Laravel. Send CV to hr@acme.com or 01012345678"))
        assertEquals(listOf("hr@acme.com"), verdict.emails)
        assertEquals(listOf("01012345678"), verdict.phones)
    }

    @Test
    fun `fingerprint ignores case and punctuation, and needs a company`() {
        val a = Store.fingerprintOf("Acme Inc.", "Backend Developer (PHP)")
        val b = Store.fingerprintOf("ACME inc", "backend developer - php")
        assertNotNull(a)
        assertEquals(a, b)
        assertNull(Store.fingerprintOf(null, "Backend Developer"))
    }

    @Test
    fun `message escapes HTML and links only https`() {
        val message = Formatter.format(job("PHP <Dev> & Co").copy(keyword = "PHP Developer"))
        assertTrue(message.contains("PHP &lt;Dev&gt; &amp; Co"))
        assertTrue(message.contains("<a href=\"https://www.linkedin.com/jobs/view/1\">Open on LinkedIn ↗</a>"))
    }

    @Test
    fun `dates`() {
        val now = java.time.Instant.parse("2026-10-23T12:00:00Z").toEpochMilli()
        assertEquals("2026-10-20T12:00:00Z", Dates.relativeToIso("Posted 3 days ago", now))
        assertEquals("2026-08-13T00:00:00Z", Dates.absoluteToIso("13 August 2026"))
        assertEquals("2026-10-23T04:00:00Z", Dates.toIso("8 hours ago", now))
        assertEquals("2026-08-13T00:00:00Z", Dates.toIso("13 August 2026", now))
        assertTrue(Dates.isOlderThan("2026-10-01T00:00:00Z", 7, now))
        assertFalse(Dates.isOlderThan(null, 7, now))
    }

    private fun post(text: String) = Item(
        source = "linkedin_posts",
        externalId = "7380000000000000000",
        itemType = "post",
        title = text.lineSequence().first(),
        url = "https://www.linkedin.com/posts/x",
        company = "Mona Ali",
        location = "Egypt",
        excerpt = text,
    )

    @Test
    fun `a hiring post passes and a job seeker's does not`() {
        assertTrue(Filters.evaluate(post("We're hiring a Laravel developer\nSend your CV to hr@acme.com")).pass)
        assertEquals("seeking-work", Filters.evaluate(post("I am looking for a Laravel role #OpenToWork")).reason)
        assertEquals("intent-unclear", Filters.evaluate(post("Laravel 12 is out: what changed")).reason)
    }

    @Test
    fun `a post is placed by its own words, not the search`() {
        assertEquals("location", Filters.evaluate(post("We are hiring a Laravel developer. Location: Riyadh")).reason)
        // Names nowhere: the search that found it stands.
        assertTrue(Filters.evaluate(post("We are hiring a Node.js developer, remote")).pass)
        assertTrue(Filters.evaluate(post("We are hiring a Laravel developer in Maadi, relocating from Riyadh")).pass)
    }

    @Test
    fun `a Saudi or UAE post has to name a place there`() {
        fun gulf(text: String) = post(text).copy(source = "gulf_posts", location = "Riyadh")
        assertNull(Filters.evaluate(gulf("We're hiring a Laravel developer in Riyadh\nSend your CV to hr@acme.sa")).reason)
        assertNull(Filters.evaluate(gulf("We are hiring a Node.js developer, Dubai office")).reason)
        // Unplaced, or placed in Egypt: the Egypt search covers it.
        assertEquals("location", Filters.evaluate(gulf("We are hiring a Node.js developer, remote")).reason)
        assertEquals("location", Filters.evaluate(gulf("We are hiring a Laravel developer in Maadi")).reason)
        // Roles kept for nationals are not ones a candidate in Egypt can take.
        assertEquals("ineligible", Filters.evaluate(gulf("We are hiring a Laravel developer in Riyadh, Saudi nationals only")).reason)
    }

    @Test
    fun `a Saudi or UAE job is placed by the search that found it`() {
        val riyadh = job(
            "Backend Developer",
            location = "Riyadh, Saudi Arabia",
            market = "Saudi Arabia",
            excerpt = "We build APIs with Laravel and PHP. Visa sponsorship provided.",
        ).copy(source = "gulf_jobs")
        assertTrue(Filters.evaluate(riyadh).pass)
        assertEquals("seniority-excluded", Filters.evaluate(riyadh.copy(title = "Senior Backend Developer")).reason)
    }

    @Test
    fun `a Gulf job has to say it takes someone relocating from Egypt`() {
        fun gulf(excerpt: String) =
            Filters.evaluate(job("Backend Developer", location = "Riyadh, Saudi Arabia", market = "Saudi Arabia", excerpt = excerpt)).reason
        // Silent on who it accepts: treated as local-only.
        assertEquals("gulf-local-only", gulf("Laravel and PHP backend role in Riyadh."))
        // Says it takes someone from abroad: kept.
        assertNull(gulf("Laravel and PHP role. Relocation package provided."))
        assertNull(gulf("Laravel and PHP role. Visa sponsorship for the right candidate."))
        assertNull(gulf("Laravel and PHP role. Open to all nationalities."))
        assertNull(gulf("Laravel role. استقدام من الخارج ونوفر الإقامة."))
        // The gate is Gulf-only: an Egypt job that says nothing still passes.
        assertTrue(Filters.evaluate(job("Backend Developer", excerpt = "Laravel and PHP role in Cairo.")).pass)
    }

    @Test
    fun `Recruitera's category stands in for the title and stack rules`() {
        val qa = Item(
            source = "recruitera", externalId = "r1", title = "QA Engineer", url = "https://app.recruitera.ai/careers/x/y",
            company = "Paymob", location = "Cairo, Egypt", market = "Egypt", excerpt = "Test our payment apps.",
        )
        assertTrue(Filters.evaluate(qa).pass)
        assertEquals("seniority-excluded", Filters.evaluate(qa.copy(title = "Senior QA Engineer")).reason)
        assertEquals("title", Filters.evaluate(qa.copy(source = "ats")).reason)
    }
}
