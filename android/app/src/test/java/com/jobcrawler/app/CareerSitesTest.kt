package com.jobcrawler.app

import com.jobcrawler.app.sources.CareerSites
import com.jobcrawler.app.sources.CareerSites.Page
import com.jobcrawler.app.sources.CareerSites.Site
import com.jobcrawler.app.sources.Vertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareerSitesTest {
    private val acme = Site(
        "acme", "Acme Software", "https://acme.example/sitemap.xml",
        Regex("""^https://acme\.example/careers/([^/?#]+)/?$"""),
    )

    private fun page(url: String, site: Site = acme) = Page(url, "${site.id}:${CareerSites.idOf(site, url)}", site)

    @Test
    fun `job ids come from the pattern's group, or the path`() {
        assertEquals("backend-developer", CareerSites.idOf(acme, "https://acme.example/careers/Backend-Developer/"))
        assertNull(CareerSites.idOf(acme, "https://acme.example/about"))

        val noGroup = acme.copy(jobUrl = Regex("""^https://acme\.example/jobs/\d+$"""))
        assertEquals("jobs/42", CareerSites.idOf(noGroup, "https://acme.example/jobs/42"))
    }

    @Test
    fun `sitemap addresses are read and decoded`() {
        val xml = """<urlset><url><loc> https://acme.example/careers/a?x=1&amp;y=2 </loc></url><url><loc>https://acme.example/</loc></url></urlset>"""
        assertEquals(listOf("https://acme.example/careers/a?x=1&y=2", "https://acme.example/"), CareerSites.locs(xml))
    }

    @Test
    fun `a JobPosting in JSON-LD is read first, even inside an @graph`() {
        val html = """
            <html><head><title>Ignore me</title>
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[
              {"@type":"Organization","name":"Acme"},
              {"@type":"JobPosting","title":"Backend Developer (Laravel)","datePosted":"2026-10-20",
               "description":"<p>Build APIs with <b>Laravel</b>.</p><ul><li>3 years PHP</li></ul>",
               "hiringOrganization":{"@type":"Organization","name":"Acme Software"},
               "employmentType":["FULL_TIME"],
               "jobLocation":{"@type":"Place","address":{"addressLocality":"New Cairo","addressCountry":{"name":"Egypt"}}}}
            ]}</script></head><body><h1>Careers</h1></body></html>
        """.trimIndent()

        val item = CareerSites.parseGeneric(html, page("https://acme.example/careers/backend-developer"))
        assertNotNull(item)
        item!!
        assertEquals("careers", item.source)
        assertEquals("acme:backend-developer", item.externalId)
        assertEquals("Backend Developer (Laravel)", item.title)
        assertEquals("Acme Software", item.company)
        assertEquals("New Cairo, Egypt", item.location)
        assertEquals("Egypt", item.market)
        assertEquals("FULL_TIME", item.employmentType)
        assertTrue(item.postedAt!!.startsWith("2026-10-20"))
        assertTrue(item.excerpt!!.contains("Build APIs with Laravel"))
    }

    @Test
    fun `a posting past its own closing date is closed`() {
        val html = """<script type="application/ld+json">{"@type":"JobPosting","title":"PHP Developer","validThrough":"2020-01-01T00:00:00Z"}</script>"""
        assertNull(CareerSites.parseGeneric(html, page("https://acme.example/careers/php")))
    }

    @Test
    fun `without JSON-LD the heading and main text are used`() {
        val html = """
            <html><head><title>Node.js Developer | Acme Software</title></head>
            <body><nav>Home Jobs</nav><main><h1>Node.js Developer</h1><p>Work on NestJS services.</p>
            <script>var x = 1;</script></main><footer>© Acme</footer></body></html>
        """.trimIndent()

        val item = CareerSites.parseGeneric(html, page("https://acme.example/careers/node"))!!
        assertEquals("Node.js Developer", item.title)
        assertEquals("Acme Software", item.company)
        assertEquals("Cairo, Egypt", item.location)
        assertTrue(item.excerpt!!.contains("Work on NestJS services."))
        assertTrue(!item.excerpt!!.contains("var x"))

        val titleOnly = """<html><head><meta property="og:title" content="Laravel Developer - Acme Software"></head><body></body></html>"""
        assertEquals("Laravel Developer", CareerSites.parseGeneric(titleOnly, page("https://acme.example/careers/l"))!!.title)
    }

    @Test
    fun `VerteX pages give the title, level, place and description`() {
        val site = CareerSites.SITES.first { it.id == "vertex" }
        val url = "https://vertextech-eg.com/vacancies/full-stack-js-developer"
        assertEquals("full-stack-js-developer", CareerSites.idOf(site, url))

        val html = """
            <html><head><title>Full Stack JS Developer - VerteX Technologies</title>
            <meta name="description" content="We're looking for Full Stack JS Developer job position in Cairo, Smart Village. Apply for an open vacancy at VerteX Technologies!">
            </head><body>
            <div data-framer-name="Vacancy name" data-framer-component-type="RichTextContainer"><h1 class="framer-text">Full Stack JS Developer</h1></div>
            <div><div data-framer-component-type="RichTextContainer"><p class="framer-text">Middle</p></div>
            <div data-framer-name="Title" data-framer-component-type="RichTextContainer"><p class="framer-text">Node.js</p></div></div>
            <div data-framer-name="Vacancy description" data-framer-component-type="RichTextContainer"><p class="framer-text">We are an Egyptian company.</p><ul><li><p>3+ years with Node.js &amp; TypeScript.</p></li></ul></div><div class="framer-w" data-framer-name="Apply"><p>Apply now</p></div>
            </body></html>
        """.trimIndent()

        val item = Vertex.parse(html, Page(url, "vertex:full-stack-js-developer", site))!!
        assertEquals("careers", item.source)
        assertEquals("Full Stack JS Developer", item.title)
        assertEquals("VerteX Technologies", item.company)
        assertEquals("Middle", item.seniority)
        assertEquals("Cairo, Smart Village, Egypt", item.location)
        assertTrue(item.excerpt!!.startsWith("Middle · Node.js\n"))
        assertTrue(item.excerpt!!.contains("3+ years with Node.js & TypeScript."))
        assertTrue(!item.excerpt!!.contains("Apply now"))
        assertTrue(Filters.evaluate(item).pass)
    }
}
