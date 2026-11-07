package com.jobcrawler.app

import org.junit.Assert.assertTrue
import org.junit.Test

class FormatterTest {
    private fun item(market: String? = null) = Item(
        source = "wuzzuf", externalId = "1", title = "Laravel Developer", url = "https://wuzzuf.net/jobs/p/1",
        company = "Acme", location = "Cairo, Egypt", market = market, yearsMin = 2, yearsMax = 4, salary = "EGP 15k–20k/mo", score = 82,
    )

    @Test
    fun `a job carries its region tag and score`() {
        val text = Formatter.format(item())
        assertTrue(text, "#Egypt" in text)
        assertTrue(text, "⭐ 82/100" in text)
        assertTrue("#Gulf" in Formatter.format(item(market = Gulf.SAUDI)))
    }

    @Test
    fun `years and salary have their lines`() {
        val text = Formatter.format(item())
        assertTrue(text, "🧭 2–4 yrs" in text)
        assertTrue(text, "💰 EGP 15k–20k/mo" in text)
    }
}
