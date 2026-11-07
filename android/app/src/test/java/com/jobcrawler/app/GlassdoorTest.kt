package com.jobcrawler.app

import com.jobcrawler.app.sources.Glassdoor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GlassdoorTest {
    private val egypt = Glassdoor.QUERIES.first { it.scope == Glassdoor.Scope.EGYPT }
    private val abroad = Glassdoor.QUERIES.first { it.scope == Glassdoor.Scope.ABROAD }

    private fun serper(vararg results: Triple<String, String, String>) = JSONObject().put(
        "organic",
        JSONArray(results.map { (link, title, snippet) -> JSONObject().put("link", link).put("title", title).put("snippet", snippet) }),
    ).toString()

    private fun result(url: String, title: String, snippet: String) = Glassdoor.Result(url, title, snippet, null)

    @Test
    fun `six searches a day, listings only`() {
        assertEquals(8, Glassdoor.QUERIES.size)
        assertEquals(3, Glassdoor.QUERIES.count { it.scope == Glassdoor.Scope.EGYPT })
        assertTrue(Glassdoor.QUERIES.all { "inurl:job-listing" in it.text })
        val body = JSONObject(Glassdoor.request(egypt))
        assertEquals("eg", body.getString("gl"))
        assertEquals("qdr:w", body.getString("tbs"))
    }

    @Test
    fun `Serper results keep single listings, not search pages`() {
        val body = serper(
            Triple("https://www.glassdoor.com/job-listing/backend-developer-acme-JV_IC2345_KO0,17_KE18,22.htm?jl=1009512345678", "Acme hiring Backend Developer Job in Cairo, Cairo | Glassdoor", "Laravel APIs."),
            Triple("https://www.glassdoor.com/Job/egypt-php-developer-jobs-SRCH_IL.0,5_IN69_KO6,19.htm", "PHP developer jobs in Egypt | Glassdoor", "250 jobs"),
            Triple("https://www.glassdoor.co.uk/job-listing/php-developer-beta-JV_KO0,13.htm?jl=1009600000001", "PHP Developer - Beta - London | Glassdoor", "Visa sponsorship available."),
        )
        val results = Glassdoor.parseSerper(body)
        assertEquals(2, results.size)
        assertEquals("1009512345678", Glassdoor.idOf(results[0].url))
        assertEquals("1009600000001", Glassdoor.idOf(results[1].url))
    }

    @Test
    fun `an out-of-credits answer stops the search with an alert`() {
        try {
            Glassdoor.parseSerper("""{"message":"Not enough credits","statusCode":400}""")
            fail("expected a block")
        } catch (error: BlockedException) {
            assertEquals("serper-refused", error.alert)
        }
    }

    @Test
    fun `both title shapes Google shows`() {
        val hiring = Glassdoor.splitTitle("Acme hiring Backend Developer Job in Cairo, Cairo | Glassdoor")!!
        assertEquals("Backend Developer", hiring.role)
        assertEquals("Acme", hiring.company)
        assertEquals("Cairo, Cairo", hiring.place)

        val dashed = Glassdoor.splitTitle("PHP Developer - Beta - London | Glassdoor")!!
        assertEquals("PHP Developer", dashed.role)
        assertEquals("Beta", dashed.company)
        assertEquals("London", dashed.place)
        assertNull(Glassdoor.splitTitle(" | Glassdoor"))
    }

    @Test
    fun `an Egypt listing keeps no market and passes the shared filters`() {
        val judged = Glassdoor.judge(
            result("https://www.glassdoor.com/job-listing/x?jl=1", "Acme hiring Backend Developer Job in Cairo, Cairo | Glassdoor", "Build Laravel APIs for our payments team."),
            egypt,
        )
        assertNull(judged.rejected)
        assertNull(judged.item.market)
        assertEquals("Laravel", judged.item.keyword)
        assertTrue(Filters.evaluate(judged.item).pass)
        // Not emailed on its own: Egypt jobs wait on the review screen.
        val profile = ApplySettings(gmail = "me@example.com", appPassword = "x", gulfGmail = "gulf@example.com", gulfAppPassword = "y")
        assertNull(Mailer.autoSender(judged.item, profile))
        assertEquals("me@example.com", Mailer.senderFor(judged.item, profile).gmail)
    }

    @Test
    fun `an Egypt search that returns a listing elsewhere drops it`() {
        val judged = Glassdoor.judge(result("https://www.glassdoor.com/job-listing/x?jl=2", "PHP Developer - Beta - London | Glassdoor", "PHP and Laravel."), egypt)
        assertEquals("not-egypt", judged.rejected)
    }

    @Test
    fun `abroad listings need a lane and the CV's stack`() {
        val visa = Glassdoor.judge(
            result("https://www.glassdoor.co.uk/job-listing/x?jl=3", "PHP Developer - Beta - London | Glassdoor", "Laravel and MySQL. Visa sponsorship available for the right candidate."),
            abroad,
        )
        assertNull(visa.rejected)
        assertEquals(Glassdoor.VISA_LANE, visa.item.market)
        assertTrue(Filters.evaluate(visa.item).pass)

        val remote = Glassdoor.judge(
            result("https://www.glassdoor.com/job-listing/x?jl=4", "Remote Backend Engineer (Node.js) - Gamma - Remote | Glassdoor", "Fully remote, we hire worldwide. Node.js and TypeScript."),
            abroad,
        )
        assertNull(remote.rejected)
        assertEquals(Glassdoor.REMOTE_LANE, remote.item.market)
        assertTrue(remote.item.remote)

        assertEquals(
            "no-lane",
            Glassdoor.judge(result("https://www.glassdoor.com/job-listing/x?jl=5", "Backend Developer - Delta - Austin, TX | Glassdoor", "PHP. Must be US-based."), abroad).rejected,
        )
        assertEquals(
            "stack",
            Glassdoor.judge(result("https://www.glassdoor.de/job-listing/x?jl=6", "Backend Engineer - Epsilon - Berlin | Glassdoor", "Java and Kotlin. Relocation package and Blue Card support."), abroad).rejected,
        )
    }

    @Test
    fun `abroad jobs go out from the second Gmail, on their own, with the lane shown`() {
        val item = Glassdoor.judge(
            result("https://www.glassdoor.co.uk/job-listing/x?jl=3", "PHP Developer - Beta - London | Glassdoor", "Laravel. Visa sponsorship available."),
            abroad,
        ).item
        val profile = ApplySettings(gmail = "me@example.com", appPassword = "x", gulfGmail = "gulf@example.com", gulfAppPassword = "y")
        assertEquals("gulf@example.com", Mailer.autoSender(item, profile)?.gmail)
        assertNull(Mailer.autoSender(item, profile.copy(abroadAutoEmail = false)))

        val text = Formatter.format(item)
        assertTrue(text, text.contains(Glassdoor.VISA_LANE))
        assertTrue(text, text.contains("Open on Glassdoor"))
        val note = CoverNote.text(item, profile, withCv = true)
        assertTrue(note, note.contains("would need visa sponsorship"))
        assertTrue(note, note.contains("on Glassdoor"))
        assertFalse(note, note.contains("careers page"))
    }
}
