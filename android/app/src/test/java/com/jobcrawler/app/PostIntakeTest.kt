package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostIntakeTest {
    private val link = "https://www.linkedin.com/posts/mona-adel-1a2b3c_hiring-laravel-activity-7389012345678901234-AbCd"

    private val post = "We're hiring a Laravel developer in Nasr City.\n" +
        "2+ years with PHP and Laravel, REST APIs, MySQL.\n" +
        "Send your CV to careers@acme.com with Laravel in the subject."

    private fun page(body: String?) = """
        <html><head>
        <meta property="og:title" content="Mona Adel on LinkedIn: We're hiring">
        ${body?.let { "<meta property=\"og:description\" content=\"$it\">" } ?: ""}
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun `the share sheet's link is found, without its tracking`() {
        val shared = "Check out this post: $link?utm_source=share&utm_medium=member_android"
        assertEquals(link, PostIntake.findLink(shared))
        assertEquals(
            "https://www.linkedin.com/feed/update/urn:li:activity:7389012345678901234",
            PostIntake.findLink("https://www.linkedin.com/feed/update/urn:li:activity:7389012345678901234/"),
        )
        assertNull(PostIntake.findLink("https://www.linkedin.com/in/someone"))
    }

    @Test
    fun `a share line is not taken for the post's text`() {
        assertNull(PostIntake.textOf("Check out this post: $link"))
        assertEquals(post, PostIntake.textOf("$link\n\n$post"))
        assertEquals(post, PostIntake.textOf(post))
    }

    @Test
    fun `a pasted post gives its address without opening anything`() {
        val outcome = PostIntake.read(post) { error("nothing to open") }
        val item = (outcome as PostIntake.Outcome.Read).item
        assertEquals(listOf("careers@acme.com"), item.contactEmails)
        assertEquals("post", item.itemType)
        assertEquals("We're hiring a Laravel developer in Nasr City.", item.title)
        assertTrue(item.externalId.startsWith("hand-"))
    }

    @Test
    fun `a public post is read from its link`() {
        val outcome = PostIntake.read(link) { page(post.replace("\n", " ")) }
        val item = (outcome as PostIntake.Outcome.Read).item
        assertEquals("7389012345678901234", item.externalId)
        assertEquals("Mona Adel", item.company)
        assertEquals(listOf("careers@acme.com"), item.contactEmails)
        assertEquals(link, item.url)
    }

    @Test
    fun `the sign-in page asks for the text, and the text then wins`() {
        val walled = PostIntake.read(link) { page(null) }
        assertEquals("Mona Adel", (walled as PostIntake.Outcome.Walled).author)

        val outcome = PostIntake.read("$link\n$post") { page(null) }
        val item = (outcome as PostIntake.Outcome.Read).item
        assertEquals("7389012345678901234", item.externalId)
        assertEquals(listOf("careers@acme.com"), item.contactEmails)
    }

    @Test
    fun `a cut-down preview and the pasted text are both read`() {
        val outcome = PostIntake.read("$link\n$post") { page("Hiring now, also hr@acme.com …") }
        val item = (outcome as PostIntake.Outcome.Read).item
        assertEquals(listOf("careers@acme.com", "hr@acme.com"), item.contactEmails)
    }

    @Test
    fun `the email to edit is drafted from the post`() {
        val item = PostIntake.toItem(link, post, "Mona Adel")
        val profile = ApplySettings(name = "Test", phone = "", pitch = "I build Laravel APIs.", gmail = "", appPassword = "")
        val draft = Mailer.draft(item, profile)!!
        assertEquals("careers@acme.com", draft.to)
        assertEquals("Laravel Developer application", draft.subject)
        assertTrue(draft.body.contains("the Laravel Developer role in your LinkedIn post"))
        assertTrue(draft.body.contains("I build Laravel APIs."))
        assertTrue(draft.body.contains("CV"))
    }

    @Test
    fun `neither a link nor a post`() {
        assertEquals(PostIntake.Outcome.NotAPost, PostIntake.read("hello") { error("nothing to open") })
    }
}
