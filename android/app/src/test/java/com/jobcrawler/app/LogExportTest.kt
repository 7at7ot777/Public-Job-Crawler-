package com.jobcrawler.app

import com.jobcrawler.app.sources.RemoteFeeds
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** The Log screen's Send: the file, its caption, and the lines that explain a run. */
class LogExportTest {
    private val log = """
        11-02 08:47:15 I hn_hiring: parsed 0, matched 0, new 0, sent 0
        11-02 08:47:11 W LinkedIn throttled (HTTP 429): resting until 09:11
        11-02 08:47:10 E Auto-email failed: boom
        11-02 08:47:10 I Run started: hn_hiring only, run by hand
    """.trimIndent()

    @Test
    fun `problems only keeps warnings, errors and the run headers`() {
        val problems = LogExport.problems(log).lines()
        assertEquals(3, problems.size)
        assertTrue(problems[0].contains(" W "))
        assertTrue(problems[1].contains(" E "))
        assertTrue(problems[2].contains("Run started"))
    }

    @Test
    fun `file name and caption`() {
        assertEquals("crawler-log-20261102-0847.txt", LogExport.fileName(LocalDateTime.of(2026, 11, 2, 8, 47)))
        assertEquals("Job Crawler 1.0.111 log · 4 lines · all", LogExport.caption("1.0.111", log, onlyProblems = false))
    }

    @Test
    fun `the log goes to Telegram as escaped messages under the limit`() {
        val messages = LogExport.messages("1.0.111", log + "\n11-02 08:47:16 W a <b> & c", onlyProblems = false)
        assertEquals(2, messages.size)
        assertEquals("<b>Job Crawler 1.0.111 log · 5 lines · all</b>", messages[0])
        assertTrue(messages[1].startsWith("<pre>11-02 08:47:15 I hn_hiring"))
        assertTrue(messages[1].contains("a &lt;b&gt; &amp; c"))
        assertTrue(messages[1].endsWith("</pre>"))
    }

    @Test
    fun `a long log is split by line and capped`() {
        val line = "11-02 08:47:15 I " + "x".repeat(983)
        val big = List(40) { line }.joinToString("\n")
        val messages = LogExport.messages("1", big, onlyProblems = false, maxMessages = 3)
        assertEquals(4, messages.size)
        assertTrue(messages.drop(1).all { it.length <= 4096 })
        assertTrue(messages[0].contains("28 older lines left out"))
    }

    @Test
    fun `a snapshot goes to Telegram as a document with chat and caption`() {
        val request = Telegram.documentRequest("TOKEN", "123", "crawler-log.txt", "hello".toByteArray(), "caption")
        assertEquals("https://api.telegram.org/botTOKEN/sendDocument", request.url.toString())
        val body = request.body as MultipartBody
        assertEquals(3, body.size)
        val text = Buffer().also { body.writeTo(it) }.readUtf8()
        assertTrue(text.contains("name=\"chat_id\""))
        assertTrue(text.contains("123"))
        assertTrue(text.contains("filename=\"crawler-log.txt\""))
        assertTrue(text.contains("hello"))
    }

    @Test
    fun `a feed run says how many it had seen before`() {
        val line = RemoteFeeds.summary("Remote feeds", RemoteFeeds.Companion.Counts(527, 507, 0, 20), 0, mapOf("title" to 20))
        assertEquals("Remote feeds: 527 listed, 507 seen before, 0 too old, 20 judged, 0 kept | dropped {title=20}", line)
    }
}
