package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun entry(id: String, name: String) = """
        <div class="flip-entry" id="entry-$id" tabindex="0" role="link">
          <div class="flip-entry-info">
            <a href="https://drive.google.com/file/d/$id/view?usp=drive_web" target="_blank">
              <div class="flip-entry-visual"><img src="x.png"></div>
              <div class="flip-entry-title">$name</div>
            </a>
          </div>
          <div class="flip-entry-last-modified"><div>Oct 28</div></div>
        </div>
    """

    @Test
    fun `the newest APK in a folder page comes first, other files ignored`() {
        val page = "<html><body><div class=\"flip-entries\">" +
            entry("1aaa_old-ID", "JobCrawler-1.0.9.apk") +
            entry("1bbb-newID", "JobCrawler-1.0.47.apk") +
            entry("1ccc", "notes.txt") +
            entry("1ddd", "JobCrawler-1.0.12.apk.part") +
            "</div></body></html>"

        val releases = Updater.parseFolder(page)
        assertEquals(listOf(47, 9), releases.map { it.versionCode })
        assertEquals("1bbb-newID", releases[0].fileId)
        assertEquals("1.0.47", releases[0].versionName)
    }

    @Test
    fun `a page without the folder layout is read from its file links`() {
        val page = """<a href="https://drive.google.com/file/d/1xyz/view">JobCrawler-1.0.50.apk</a>"""
        assertEquals(listOf(Updater.Release(50, "JobCrawler-1.0.50.apk", "1xyz")), Updater.parseFolder(page))
    }

    @Test
    fun `a sign-in page lists nothing`() {
        assertTrue(Updater.parseFolder("<html><body><form action=\"https://accounts.google.com/\"></form></body></html>").isEmpty())
    }
}
