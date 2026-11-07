package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64

class ApplyTest {
    private val profile = ApplySettings(
        notify = true,
        name = "Test User",
        phone = "01000000000",
        pitch = "I build Laravel APIs.",
        neverEmail = listOf("blocked.com", "Evil Corp"),
        gmail = "",
        appPassword = "",
    )

    private fun job(emails: List<String>, company: String? = "Acme") = Item(
        source = "linkedin_jobs",
        externalId = "1",
        title = "Backend Developer",
        url = "https://www.linkedin.com/jobs/view/1",
        company = company,
        contactEmails = emails,
    )

    @Test
    fun `addresses written to dodge scrapers are normalised`() {
        assertEquals("hr@acme.com", Recipients.normalise("HR (at) acme (dot) com"))
        assertEquals("jobs@acme.co.eg", Recipients.normalise("jobs [at] acme.co.eg."))
        assertEquals("careers@acme.com", Recipients.normalise("careers at acme.com"))
        assertNull(Recipients.normalise("not an address"))
    }

    @Test
    fun `only inboxes for people, not blocked, not recently emailed`() {
        val recent = setOf("old@acme.com")
        val eligible = Recipients.eligible(
            job(listOf("noreply@acme.com", "support@acme.com", "hr@blocked.com", "x@team.blocked.com", "old@acme.com", "HR@acme.com", "hr@acme.com")),
            profile,
        ) { it in recent }
        assertEquals(listOf("hr@acme.com"), eligible)

        assertTrue(Recipients.eligible(job(listOf("hr@evil.com"), company = "Evil Corp Egypt"), profile) { false }.isEmpty())
    }

    @Test
    fun `the cover note names the role and where it was seen, and signs off`() {
        val text = CoverNote.text(job(emptyList()), profile, withCv = true, variant = 0)
        assertEquals(
            """
            Hello Acme hiring team,

            I'd like to apply for the Backend Developer position you posted on LinkedIn. I build Laravel APIs.

            My CV is attached, and I'd be glad to talk at a time that suits you.

            Best regards,
            Test User
            01000000000
            """.trimIndent(),
            text,
        )
        assertFalse(CoverNote.text(job(emptyList()), profile, withCv = false).contains("attach"))

        val post = job(emptyList()).copy(itemType = "post", company = "Mona Ali")
        assertTrue(CoverNote.text(post, profile, withCv = false, variant = 0).startsWith("Hello Hiring team,"))

        assertEquals("Backend Developer application", CoverNote.subject(job(emptyList())))
        assertEquals("Application for the role in your LinkedIn post", CoverNote.subject(post.copy(title = "We're hiring!")))
        assertEquals("Backend Developer application", CoverNote.subject(job(emptyList()).copy(source = "tanqeeb", board = "Bayt")))
    }

    private fun post(text: String) = job(emptyList()).copy(
        itemType = "post",
        company = "Mona Ali",
        title = text.lineSequence().first(),
        excerpt = text,
    )

    @Test
    fun `a post's subject names the role its text gives`() {
        assertEquals("Senior Backend Engineer application", CoverNote.subject(post("🚀 We're hiring!\nWe're looking for a Senior Backend Engineer to join us.")))
        assertEquals("Laravel Developer application", CoverNote.subject(post("We're hiring a Laravel developer in Nasr City.")))
        assertEquals("PHP Developer application", CoverNote.subject(post("Hiring now!\nPosition: php developers\nApply below")))
        assertEquals("Node.js Developer application", CoverNote.subject(post("Hiring Node.js Developer | Cairo | Hybrid")))
        assertEquals("Team Lead application", CoverNote.subject(post("Join us as a Team Lead (PHP)")))
        // Prose around a role is not a title.
        assertEquals(
            "Application for the role in your LinkedIn post",
            CoverNote.subject(post("Exciting news!\nThe role requires a strong backend developer mindset.")),
        )
        assertEquals("Application for the role in your LinkedIn post", CoverNote.subject(post("We're looking for someone to lead our team.")))
    }

    @Test
    fun `the subject a posting asks for wins`() {
        assertEquals("PHP Dev - Cairo", CoverNote.subject(post("Hiring a PHP developer.\nSubject: PHP Dev - Cairo\nSend to hr@acme.com")))
        assertEquals("Laravel Developer application", CoverNote.subject(post("Hiring a Laravel developer. Send your CV with Laravel in the subject.")))
        assertEquals("Golang - Backend Engineer application", CoverNote.subject(post("Hiring a Backend Engineer. Apply with your CV and put Golang in the subject line.")))
        assertEquals("Golang application", CoverNote.subject(post("We're hiring! Write \"Golang\" in the email subject.")))
        assertEquals("Backend Engineer application", CoverNote.subject(post("Hiring a Backend Engineer, write the job title in the subject.")))
        // A board job keeps its own title.
        assertEquals("Backend Developer application", CoverNote.subject(job(emptyList()).copy(excerpt = "We need a developer.")))
    }

    @Test
    fun `the wording varies between jobs but never for one job`() {
        val notes = (0 until 81).map { CoverNote.text(job(emptyList()), profile, withCv = true, variant = it) }.toSet()
        assertEquals(81, notes.size)
        val one = job(emptyList())
        assertEquals(CoverNote.text(one, profile, withCv = true), CoverNote.text(one.copy(), profile, withCv = true))
    }

    @Test
    fun `a tapped Send skips no-reply and blocked addresses, but not one written to before`() {
        assertEquals("hr@acme.com", Mailer.addressFor(job(listOf("noreply@acme.com", "HR (at) acme (dot) com")), profile))
        assertNull(Mailer.addressFor(job(listOf("no-reply@acme.com", "jobs@blocked.com")), profile))
        assertNull(Mailer.addressFor(job(listOf("hr@evil.com"), company = "Evil Corp"), profile))
    }

    @Test
    fun `only a sent, unusable or permanently refused job leaves the review list`() {
        assertTrue(Mailer.settles(Mailer.Result.Sent("hr@acme.com")))
        assertTrue(Mailer.settles(Mailer.Result.NoAddress))
        assertTrue(Mailer.settles(Mailer.Result.Failed("550 no such user", permanent = true)))
        assertFalse(Mailer.settles(Mailer.Result.Failed("timeout", permanent = false)))
        assertFalse(Mailer.settles(Mailer.Result.Failed("535 bad login", permanent = true, auth = true)))
        assertFalse(Mailer.settles(Mailer.Result.NoCv))
    }

    @Test
    fun `the MIME message carries the body and the PDF`() {
        val message = Mime.build(
            from = "me@gmail.com",
            fromName = "Test User",
            to = "hr@acme.com",
            subject = "Application: مطور Backend",
            body = "Hello\nThere",
            attachment = Mime.Attachment("CV.pdf", "%PDF-1.4 test".toByteArray()),
            boundary = "B",
            date = ZonedDateTime.of(2026, 10, 24, 9, 0, 0, 0, ZoneOffset.UTC),
        )

        assertTrue(message.contains("From: Test User <me@gmail.com>\r\n"))
        assertTrue(message.contains("Subject: =?UTF-8?B?"))
        assertTrue(message.contains("Content-Type: multipart/mixed; boundary=\"B\""))
        assertTrue(message.contains(Base64.getMimeEncoder().encodeToString("Hello\r\nThere".toByteArray())))
        assertTrue(message.contains(Base64.getMimeEncoder().encodeToString("%PDF-1.4 test".toByteArray())))
        assertTrue(message.trimEnd().endsWith("--B--"))

        assertEquals("..hidden\r\nok", Mime.dotStuff(".hidden\r\nok"))
    }

    @Test
    fun `Saudi and UAE emails go from their own account only when it is complete`() {
        val main = profile.copy(gmail = "me@gmail.com", appPassword = "abcd")
        assertEquals("me@gmail.com", main.gulfSender.gmail)
        assertEquals("me@gmail.com", main.copy(gulfGmail = "gulf@gmail.com").gulfSender.gmail)

        val second = main.copy(gulfGmail = "gulf@gmail.com", gulfAppPassword = "wxyz").gulfSender
        assertEquals("gulf@gmail.com", second.gmail)
        assertEquals("wxyz", second.appPassword)
        // Only the account changes: the note signs off the same.
        assertEquals(main.name, second.name)
    }

    @Test
    fun `a post search is scoped to its place's country`() {
        assertEquals("sa", com.jobcrawler.app.sources.LinkedInPosts.countryOf("Saudi Arabia"))
        assertEquals("sa", com.jobcrawler.app.sources.LinkedInPosts.countryOf("Riyadh"))
        assertEquals("ae", com.jobcrawler.app.sources.LinkedInPosts.countryOf("UAE"))
        assertEquals("ae", com.jobcrawler.app.sources.LinkedInPosts.countryOf("Dubai"))
        assertEquals("eg", com.jobcrawler.app.sources.LinkedInPosts.countryOf("New Cairo"))
    }
}
