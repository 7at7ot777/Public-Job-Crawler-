package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesTest {
    // What `git log --format=%cI%x1f%h%x1f%B%x1e` prints: one record per commit.
    private fun commit(date: String, hash: String, message: String) = "$date\u001f$hash\u001f$message\n\u001e\n"

    @Test
    fun `commits become updates, newest first, without the prefix or trailers`() {
        val log = commit("2026-10-26T10:00:00+02:00", "aaa1111", "Android app: a Jobs screen beside Telegram") +
            commit(
                "2026-10-28T09:30:00+02:00", "bbb2222",
                """
                Android app: edit the email before sending a CV

                The Add a post screen now shows the drafted email as fields: To,
                Subject and Message.

                - To is checked for its shape
                - a bad one is reported

                Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
                """.trimIndent(),
            )

        val changes = Changes.parse(log)

        assertEquals(listOf("bbb2222", "aaa1111"), changes.map { it.hash })
        assertEquals("Edit the email before sending a CV", changes[0].title)
        assertEquals(
            "The Add a post screen now shows the drafted email as fields: To, Subject and Message.\n\n" +
                "• To is checked for its shape\n\n• a bad one is reported",
            changes[0].details,
        )
        assertEquals("A Jobs screen beside Telegram", changes[1].title)
        assertEquals("", changes[1].details)
    }

    @Test
    fun `build fixes and broken records are left out`() {
        val log = commit("2026-10-24T08:00:00+02:00", "ccc3333", "Android app: fix the build: no smart cast") +
            "not a record\u001e\n" +
            commit("2026-10-24T09:00:00+02:00", "ddd4444", "Date LinkedIn posts by their activity id")

        val changes = Changes.parse(log)

        assertEquals(listOf("ddd4444"), changes.map { it.hash })
        assertEquals("Date LinkedIn posts by their activity id", changes[0].title)
    }

    @Test
    fun `an empty log is an empty list`() {
        assertTrue(Changes.parse("").isEmpty())
    }
}
