package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WorkManager stores these names on the phone: renaming one orphans work an older build queued. */
class WorkNamesTest {
    private fun all(key: String) = listOf(
        WorkNames.periodic(key), WorkNames.now(key), WorkNames.resume(key),
        WorkNames.sourceRun(key), WorkNames.sourceResume(key),
        WorkNames.next(key, "a"), WorkNames.next(key, "b"),
        WorkNames.HOUSEKEEPING, WorkNames.HOUSEKEEP_SOON,
    )

    @Test
    fun `the names are what installed builds already use`() {
        assertEquals(
            listOf(
                "crawl-src-wuzzuf", "crawl-now-wuzzuf", "crawl-resume-wuzzuf",
                "crawl-source-wuzzuf", "crawl-source-resume-wuzzuf",
                "crawl-next-wuzzuf-a", "crawl-next-wuzzuf-b",
                "housekeeping", "housekeeping-soon",
            ),
            all("wuzzuf"),
        )
        assertEquals(listOf("crawl-periodic", "crawl-now", "crawl-resume"), WorkNames.LEGACY)
    }

    @Test
    fun `no two sources or kinds share a name, and none is a legacy one`() {
        val names = Pipeline.SOURCES.flatMap { all(it.key) - setOf(WorkNames.HOUSEKEEPING, WorkNames.HOUSEKEEP_SOON) }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none { it in WorkNames.LEGACY })
    }

    @Test
    fun `chained runs take turns between the two slots`() {
        assertEquals("a", WorkNames.nextSlot(null))
        assertEquals("a", WorkNames.nextSlot("x"))
        assertEquals("b", WorkNames.nextSlot("a"))
        assertEquals("a", WorkNames.nextSlot("b"))
    }
}
