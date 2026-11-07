package com.jobcrawler.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteTest {
    @After
    fun clear() = Mutes.replace(emptyList())

    private fun item(title: String, company: String? = null) =
        Item(source = "wuzzuf", externalId = "1", title = title, url = "https://x.test/1", company = company, location = "Cairo, Egypt",
            excerpt = "Laravel PHP")

    @Test
    fun `a muted company is rejected before the title rule`() {
        Mutes.replace(listOf(Mutes.Entry(Mutes.Kind.COMPANY, Mutes.key("ACME Inc."), "ACME Inc.")))
        assertEquals("muted-company", Mutes.reasonFor(item("Backend Developer", "Acme, Inc")))
        val verdict = Filters.evaluate(item("Anything at all", "acme inc"))
        assertFalse(verdict.pass)
        assertEquals("muted-company", verdict.reason)
    }

    @Test
    fun `a muted word`() {
        Mutes.replace(listOf(Mutes.Entry(Mutes.Kind.WORD, "wordpress", "WordPress")))
        assertEquals("muted-title", Mutes.reasonFor(item("WordPress PHP Developer")))
        assertNull(Mutes.reasonFor(item("Laravel Developer")))
    }

    @Test
    fun `a word mute is a whole word`() {
        Mutes.replace(listOf(Mutes.Entry(Mutes.Kind.WORD, "go", "go")))
        assertNull(Mutes.reasonFor(item("Google Cloud Engineer")))
    }

    @Test
    fun `mutes passed in win over the loaded ones`() {
        Mutes.replace(listOf(Mutes.Entry(Mutes.Kind.WORD, "wordpress", "WordPress")))
        assertNull(Mutes.reasonFor(item("WordPress PHP Developer"), mutes = emptyList()))
        val company = listOf(Mutes.Entry(Mutes.Kind.COMPANY, Mutes.key("Acme"), "Acme"))
        assertEquals("muted-company", Mutes.reasonFor(item("Laravel Developer", "ACME"), mutes = company))
    }

    @Test
    fun `a reload while another worker filters never shows half a list`() {
        val word = Mutes.Entry(Mutes.Kind.WORD, "wordpress", "WordPress")
        val company = Mutes.Entry(Mutes.Kind.COMPANY, Mutes.key("Acme"), "Acme")
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread {
            while (!stop.get()) {
                Mutes.replace(listOf(word, company))
                Mutes.replace(emptyList())
            }
        }.apply { start() }
        try {
            repeat(20_000) {
                val seen = Mutes.all
                assertTrue("saw ${seen.size}", seen.isEmpty() || seen.size == 2)
            }
        } finally {
            stop.set(true)
            writer.join()
        }
    }

    @Test
    fun `title words leave out the generic ones`() {
        val words = Mutes.titleWords("Senior WordPress Developer (Remote)")
        assertTrue("wordpress" in words)
        assertFalse("developer" in words)
        assertFalse("senior" in words)
    }
}
