package com.jobcrawler.app

import com.jobcrawler.app.sources.CvStack
import com.jobcrawler.app.sources.RelocateMe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelocateMeTest {

    private fun card(path: String, title: String, company: String, country: String, city: String) = """
        <div class="jobs-list__job">
            <div class="job__info">
                <div class="job__country text4-medium"><svg width="20"></svg><p>$country</p></div>
                <div class="job__company text4-medium"><svg width="20"></svg><p>$company</p></div>
            </div>
            <div class="job__title">
                <a href="$path">
                    <b>$title</b>
                    in
                        $city
                </a>
            </div>
            <p class="job__preview">Short preview...</p>
        </div>
    """

    private val listPage = "<html><body><div class=\"jobs-list\">" +
        card("/remote/remote/the-global-move/1700-curated-visa-sponsorship-and-remote-tech-jobs-paid-option-10080", "1700+ curated visa jobs", "The Global Move", "Remote", "Remote") +
        card("/netherlands/amsterdam/picnic/software-engineer-consumer-products-10256", "Software Engineer - Consumer Products", "Picnic", "Netherlands", "Amsterdam") +
        card("/spain/san-sebastian/multiverse-computing/backend-developer-10400", "Backend Developer &amp; API", "Multiverse Computing", "Spain", "San Sebastian") +
        "</div></body></html>"

    /** As the live page ships it: raw line breaks inside the JSON strings, and the description as escaped HTML. */
    private fun detail(title: String, description: String, date: String = "2026-10-20", type: String = "FULL_TIME") = """
        <html><head>
        <script type="application/ld+json">
            { "@context": "http://schema.org", "@type": "BreadcrumbList", "itemListElement": [] }
        </script>
        <script type="application/ld+json">
        {
            "@context": "https://schema.org/",
            "@type": "JobPosting",
            "title": "$title",
            "description": "$description",
            "identifier": { "@type": "PropertyValue", "name": "PayPay", "value": "10205" },
            "datePosted": "$date",
            "employmentType": "$type",
            "hiringOrganization": { "@type": "Organization", "name": "PayPay" },
            "jobLocation": { "@type": "Place", "address": { "@type": "PostalAddress", "addressLocality": "Tokyo", "addressCountry": "Japan" } }
        }
        </script></head><body></body></html>
    """

    @Test
    fun `list cards, with the board's own advert marked`() {
        val cards = RelocateMe.parseList(listPage)
        assertEquals(listOf("10080", "10256", "10400"), cards.map { it.id })
        assertTrue(cards[0].sponsored)
        assertTrue(cards[0].remote)

        val picnic = cards[1]
        assertFalse(picnic.sponsored)
        assertEquals("Software Engineer - Consumer Products", picnic.title)
        assertEquals("Picnic", picnic.company)
        assertEquals("Netherlands", picnic.country)
        assertEquals("Amsterdam", picnic.city)
        assertEquals("https://relocate.me/netherlands/amsterdam/picnic/software-engineer-consumer-products-10256", picnic.url)

        assertEquals("Backend Developer & API", cards[2].title)
        assertEquals("San Sebastian", cards[2].city)
        assertEquals(0, RelocateMe.parseList("<html>nothing here</html>").size)
    }

    @Test
    fun `a job page that fits the CV`() {
        val card = RelocateMe.parseList(listPage)[2]
        val page = detail(
            "Backend Engineer | Relocation Offered ",
            "&lt;p&gt;We build payments APIs in Go and PHP.&lt;/p&gt;\n&lt;ul&gt;&lt;li&gt;3+ years of backend experience&lt;/li&gt;\n" +
                "&lt;li&gt;MySQL and Redis&lt;/li&gt;&lt;li&gt;VISA sponsor + Relocation support&lt;/li&gt;&lt;/ul&gt;",
        )
        val job = RelocateMe.parseDetail(page, card)

        assertNull(job.rejected)
        val item = job.item
        assertEquals("Backend Engineer", item.title)
        assertEquals("PayPay", item.company)
        assertEquals("Tokyo, Japan", item.location)
        assertEquals("✈️ Relocation to Japan, visa paid", item.market)
        assertEquals("2026-10-20T00:00:00Z", item.postedAt)
        assertEquals("Full time", item.employmentType)
        assertEquals("PHP, Go", item.keyword)
        assertTrue(item.excerpt!!, item.excerpt!!.contains("3+ years of backend experience"))
        assertFalse(item.excerpt!!, item.excerpt!!.contains("&lt;"))

        // The shared filters still pass it, and the cover note names the country.
        assertTrue(Filters.evaluate(item).pass)
        assertEquals("Japan", RelocateMe.countryOf(item.market))
        val note = CoverNote.text(item, ApplySettings(name = "M", phone = "", gmail = "me@example.com", appPassword = "x"), withCv = true)
        assertTrue(note, note.contains("relocate to Japan"))
    }

    @Test
    fun `a job page outside the CV, or out of reach`() {
        val card = RelocateMe.parseList(listPage)[1]
        assertEquals("stack", RelocateMe.parseDetail(detail("Software Engineer", "Kotlin and Java on Android."), card).rejected)
        assertEquals("years", RelocateMe.parseDetail(detail("Software Engineer", "Node.js; 6+ years of experience."), card).rejected)
        assertEquals("part-time", RelocateMe.parseDetail(detail("Software Engineer", "Node.js", type = "PART_TIME"), card).rejected)
        assertEquals("no-posting", RelocateMe.parseDetail("<html>gone</html>", card).rejected)
    }

    @Test
    fun `years of experience read from a description`() {
        assertEquals(3, CvStack.minYears("3+ years of experience with PHP"))
        assertEquals(2, CvStack.minYears("2-4 years of professional backend experience; 10 years of company history"))
        assertEquals(5, CvStack.minYears("5 years' experience"))
        assertNull(CvStack.minYears("We were founded 12 years ago."))
    }

    @Test
    fun `jobs abroad go from the second Gmail behind one switch`() {
        val profile = ApplySettings(gmail = "me@example.com", appPassword = "x", gulfGmail = "gulf@example.com", gulfAppPassword = "y")
        assertEquals("gulf@example.com", Mailer.autoSender(abroadJob(RelocateMe.KEY), profile)?.gmail)
        // By hand too, and the main account when no second one is set.
        assertEquals("gulf@example.com", Mailer.senderFor(abroadJob(RelocateMe.KEY), profile).gmail)
        assertEquals("me@example.com", Mailer.senderFor(abroadJob(RelocateMe.KEY), profile.copy(gulfGmail = "")).gmail)
        assertEquals("me@example.com", Mailer.senderFor(abroadJob("wuzzuf"), profile).gmail)
        assertNull(Mailer.autoSender(abroadJob(RelocateMe.KEY), profile.copy(abroadAutoEmail = false)))
        assertEquals("Relocate.me", Mailer.autoLabel(RelocateMe.KEY))
    }

    @Test
    fun `the channel message names the destination`() {
        val card = RelocateMe.parseList(listPage)[2]
        val item = RelocateMe.parseDetail(detail("Backend Engineer", "PHP APIs"), card).item
        val text = Formatter.format(item)
        assertTrue(text, text.contains("Relocation to Japan"))
        assertTrue(text, text.contains("Open on Relocate.me"))
    }

    private fun abroadJob(source: String) = Item(source = source, externalId = "1", title = "Backend Engineer", url = "https://x")
}
