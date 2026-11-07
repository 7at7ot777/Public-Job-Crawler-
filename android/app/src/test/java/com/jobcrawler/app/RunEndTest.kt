package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A run's end is one of three, and the log line and the run row agree with it. */
class RunEndTest {
    @Test
    fun `a run with nothing wrong is clean`() {
        val stat = Pipeline.SourceStat("wuzzuf", parsed = 3)
        assertEquals(Pipeline.SourceStat.End.Clean, stat.end)
        assertTrue(stat.clean)
        assertEquals("wuzzuf: parsed 3, matched 0, new 0, sent 0", stat.toString())
    }

    @Test
    fun `a refusal is blocked and keeps its reason`() {
        val stat = Pipeline.SourceStat("linkedin_jobs").apply { block("HTTP 429") }
        assertEquals(Pipeline.SourceStat.End.Blocked("HTTP 429"), stat.end)
        assertFalse(stat.clean)
        assertTrue(stat.toString().endsWith(", STOPPED: HTTP 429"))
        val row = RunHistory.of(stat)
        assertTrue(row.blocked)
        assertEquals("HTTP 429", row.error)
    }

    @Test
    fun `a crash is failed, not blocked`() {
        val stat = Pipeline.SourceStat("ats").apply { fail("IOException: reset") }
        assertEquals(Pipeline.SourceStat.End.Failed("IOException: reset"), stat.end)
        assertTrue(stat.toString().endsWith(", ERROR: IOException: reset"))
        assertFalse(RunHistory.of(stat).blocked)
    }
}
