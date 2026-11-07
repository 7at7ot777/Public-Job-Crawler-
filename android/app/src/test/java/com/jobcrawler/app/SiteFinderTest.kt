package com.jobcrawler.app

import com.jobcrawler.app.sources.CareerSites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteFinderTest {
    @Test
    fun `links are normalised, and anything else is refused`() {
        assertEquals("https://acme.com/careers", SiteFinder.normalize(" acme.com/careers "))
        assertEquals("http://acme.com", SiteFinder.normalize("http://acme.com"))
        assertNull(SiteFinder.normalize("not a link"))
        assertNull(SiteFinder.normalize("localhost"))
    }

    @Test
    fun `sitemaps are read from robots txt`() {
        val robots = "User-agent: *\nDisallow: /admin\nSitemap: https://acme.com/sitemap_index.xml\n  sitemap: https://acme.com/jobs.xml"
        assertEquals(listOf("https://acme.com/sitemap_index.xml", "https://acme.com/jobs.xml"), SiteFinder.sitemapsIn(robots))
    }

    private val locs = listOf(
        "https://acme.com/",
        "https://acme.com/about",
        "https://acme.com/careers",
        "https://acme.com/careers/backend-developer",
        "https://acme.com/careers/php-engineer/",
        "https://acme.com/blog/hello",
        "https://acme.com/blog/world",
        "https://acme.com/blog/again",
        "https://www.acme.com/en/vacancies/qa",
    )

    @Test
    fun `the pasted path wins, else the jobs word with the most pages`() {
        assertEquals("careers", SiteFinder.jobPrefix("/careers", locs))
        // The blog has more pages, but "blog" is not a jobs word.
        assertEquals("careers", SiteFinder.jobPrefix("/", locs))
        assertEquals("en/vacancies", SiteFinder.jobPrefix("/en/vacancies", locs))
        assertNull(SiteFinder.jobPrefix("/", listOf("https://acme.com/blog/a", "https://acme.com/about")))
    }

    @Test
    fun `the pattern matches job pages and nothing around them`() {
        val site = SavedSite("acme", "Acme", "https://acme.com/sitemap.xml", SiteFinder.patternFor("acme.com", "careers")).toSite()
        assertEquals("backend-developer", CareerSites.idOf(site, "https://acme.com/careers/backend-developer"))
        assertEquals("php-engineer", CareerSites.idOf(site, "https://www.acme.com/careers/php-engineer/"))
        assertNull(CareerSites.idOf(site, "https://acme.com/careers"))
        assertNull(CareerSites.idOf(site, "https://acme.com/careers/x/apply"))
        assertNull(CareerSites.idOf(site, "https://evil-acme.com/careers/x"))
    }

    @Test
    fun `ids and names come from the site`() {
        assertEquals("acme", SiteFinder.idFor("jobs.acme.com.eg"))
        assertEquals("ibm", SiteFinder.idFor("ibm.com"))
        assertEquals("vertextech-eg", SiteFinder.idFor("vertextech-eg.com"))
        assertEquals("acme-2", UserSites.freeId("acme", listOf("acme", "vertex")))

        assertEquals("Acme Software", SiteFinder.companyName("""<meta property="og:site_name" content="Acme Software">""", "acme.com"))
        assertEquals("Acme", SiteFinder.companyName("<title>Open roles | Acme Careers</title>", "acme.com"))
        assertEquals("Acme", SiteFinder.companyName("", "acme.com"))
    }

    @Test
    fun `saved sites survive a round trip, and a broken one is dropped`() {
        val sites = listOf(
            SavedSite("acme", "Acme", "https://acme.com/sitemap.xml", SiteFinder.patternFor("acme.com", "careers"), "Maadi, Cairo, Egypt"),
            SavedSite("beta", "Beta", "https://beta.io/sitemap.xml", "^https://beta\\.io/jobs/(\\d+)$", sitemapFilter = "job"),
        )
        assertEquals(sites, UserSites.decode(UserSites.encode(sites)))

        val broken = """[{"id":"x","company":"X","sitemap":"https://x.com/s.xml","pattern":"(unclosed"},{"id":"y"}]"""
        assertTrue(UserSites.decode(broken).isEmpty())
        assertTrue(UserSites.decode("not json").isEmpty())
        assertFalse(UserSites.decode(UserSites.encode(sites)).first().toSite().jobUrl.pattern.isEmpty())
    }
}
