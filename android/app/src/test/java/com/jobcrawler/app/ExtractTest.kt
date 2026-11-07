package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractTest {
    @Test
    fun `an english range`() {
        assertEquals(Extract.Years(3, 5), Extract.years("We need 3-5 years of experience with Laravel"))
    }

    @Test
    fun `a plus form`() {
        assertEquals(Extract.Years(3, null), Extract.years("3+ yrs backend experience"))
    }

    @Test
    fun `a lead-in form`() {
        assertEquals(Extract.Years(4, null), Extract.years("Minimum 4 years in backend development"))
        assertEquals(Extract.Years(2, null), Extract.years("Experience: 2 years"))
    }

    @Test
    fun `arabic phrasings`() {
        assertEquals(Extract.Years(3, null), Extract.years("مطلوب مطور خبرة 3 سنوات"))
        assertEquals(Extract.Years(2, 4), Extract.years("خبرة من ٢ إلى ٤ سنوات"))
        assertEquals(Extract.Years(2, 2), Extract.years("سنتين خبرة في Laravel"))
        assertEquals(Extract.Years(5, null), Extract.years("5 سنوات خبرة"))
    }

    @Test
    fun `a company's age is not experience`() {
        assertNull(Extract.years("Founded 10 years ago, we build payments"))
        assertNull(Extract.years(null))
    }

    @Test
    fun `the lowest of several counts`() {
        assertEquals(2, Extract.years("2+ years of experience with PHP, 5+ years of experience preferred")?.min)
    }

    @Test
    fun `an egp monthly range`() {
        assertEquals("EGP 15k–20k/mo", Extract.salary("Salary: 15,000 - 20,000 EGP per month"))
    }

    @Test
    fun `sar, aed and usd`() {
        assertEquals("SAR 12k", Extract.salary("Package SAR 12k plus housing"))
        assertEquals("AED 8k", Extract.salary("الراتب 8000 درهم"))
        assertEquals("USD 90k–110k/yr", Extract.salary("Pay: \$90k–110k/year"))
    }

    @Test
    fun `no number, no salary`() {
        assertNull(Extract.salary("Competitive salary and benefits"))
        assertNull(Extract.salary("Lunch for \$5"))
        assertNull(Extract.salary("For SALE 2000 units"))
    }

    @Test
    fun `apply fills the item`() {
        val item = Item(source = "wuzzuf", externalId = "1", title = "PHP Developer", url = "https://x.test/1",
            excerpt = "2-4 years of experience. Salary 18,000 EGP monthly")
        Extract.apply(item)
        assertEquals(2, item.yearsMin)
        assertEquals(4, item.yearsMax)
        assertEquals("EGP 18k/mo", item.salary)
    }
}
