package com.jobcrawler.app

import com.jobcrawler.app.sources.AbroadFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScoreTest {
    private val now = Instant.parse("2026-11-02T10:00:00Z")

    private fun item(title: String, excerpt: String = "", postedAt: String? = null, company: String? = "Acme", source: String = "wuzzuf") =
        Item(source = source, externalId = title, title = title, url = "https://x.test/$title", company = company,
            location = "Cairo, Egypt", excerpt = excerpt, postedAt = postedAt)

    private fun score(item: Item, learned: Score.Learned = Score.Learned()): Int {
        Extract.apply(item)
        return Score.of(item, now, learned)
    }

    @Test
    fun `a laravel title beats a generic one`() {
        assertTrue(score(item("Laravel Developer", "PHP MySQL")) > score(item("Software Engineer", "PHP MySQL")))
    }

    @Test
    fun `fresh beats stale`() {
        val fresh = item("Laravel Developer", postedAt = now.minusSeconds(3_600).toString())
        val stale = item("Laravel Developer", postedAt = now.minusSeconds(20 * 86_400).toString())
        assertTrue(score(fresh) > score(stale))
    }

    @Test
    fun `years in reach fit better`() {
        assertTrue(score(item("Laravel Developer", "2 years of experience")) > score(item("Laravel Developer", "6 years of experience")))
    }

    @Test
    fun `two downvotes on a company cost it, one does not`() {
        val job = item("Laravel Developer", company = "Acme")
        val base = score(job)
        val once = Score.learn(listOf(item("PHP Developer", company = "Acme")))
        val twice = Score.learn(listOf(item("PHP Developer", company = "Acme"), item("Node Developer", company = "ACME")))
        assertEquals(base, score(item("Laravel Developer", company = "Acme"), once))
        assertEquals(maxOf(0, base - 25), score(item("Laravel Developer", company = "Acme"), twice))
    }

    @Test
    fun `the title word penalty is capped at 20`() {
        val down = listOf(item("Magento Shopify Drupal Developer"), item("Magento Shopify Drupal Developer"))
            .map { it.copy(company = null) }
        val learned = Score.learn(down)
        val job = item("Magento Shopify Drupal Developer", company = null)
        val base = score(job.copy())
        assertEquals(maxOf(0, base - 20), score(job.copy(), learned))
    }

    @Test
    fun `abroad uses AbroadFit`() {
        val job = item("Laravel Developer", "Remote, open to Egypt. PHP, Laravel", source = "remote_feeds")
        val fit = AbroadFit.score(job)
        assertTrue(kotlin.math.abs(score(job) - fit) <= 15)
    }

    @Test
    fun `always between 0 and 100`() {
        for (title in listOf("Laravel PHP Node.js Backend Developer", "Cashier", "")) {
            val value = score(item(title.ifEmpty { "x" }, "Laravel PHP Node.js MySQL Redis Docker AWS remote junior"))
            assertTrue(value in 0..100)
        }
    }
}
