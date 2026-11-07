package com.jobcrawler.app

import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.LinkedInJobs
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The shared parser fixtures (fixtures/<parser>/<case>.*, at the repo root):
 * the same markup and expected fields the Node app's test/fixtures.test.js
 * checks, so the two ports cannot drift apart unnoticed.
 */
class FixturesTest {
    private val root: File =
        System.getProperty("fixtures")?.let(::File) ?: File(System.getProperty("user.dir"), "../../fixtures")

    private fun parse(parser: String, input: String, args: JSONObject): List<Item> = when (parser) {
        "linkedin_cards" -> LinkedInJobs.parseCards(input, args.optString("keyword"), args.optString("market"))
        "workable" -> Workable.parse(input, args.optString("keyword"), args.optString("market")).first
        "ats_lever", "ats_greenhouse" -> Ats.parseBoardResponse(input, Ats.parseBoard(args.getString("board"))!!) ?: emptyList()
        "tanqeeb_search" -> Tanqeeb.parseSearch(input, args.optString("keyword"))
        else -> throw AssertionError("no Kotlin parser registered for $parser")
    }

    private fun field(item: Item, name: String): String? = when (name) {
        "externalId" -> item.externalId
        "title" -> item.title
        "url" -> item.url
        "company" -> item.company
        "location" -> item.location
        "workplace" -> item.workplace
        "board" -> item.board
        "excerpt" -> item.excerpt
        "postedAt" -> item.postedAt
        else -> throw AssertionError("fixture field $name is not compared")
    }

    /** Dates compare as instants: "…00Z" and "…00.000Z" are the same moment. */
    private fun same(name: String, actual: String?, expected: String?): Boolean =
        if (name == "postedAt" && actual != null && expected != null) Instant.parse(actual) == Instant.parse(expected)
        else actual == expected

    @Test
    fun `every fixture parses to its expected fields`() {
        assertTrue("no fixtures at ${root.absolutePath}", root.isDirectory)
        var cases = 0
        for (dir in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            for (expectedFile in dir.listFiles()!!.filter { it.name.endsWith(".expected.json") }.sortedBy { it.name }) {
                val name = expectedFile.name.removeSuffix(".expected.json")
                val input = dir.listFiles()!!.first { it.name.startsWith("$name.") && it != expectedFile }.readText()
                val expected = JSONObject(expectedFile.readText())
                val skipped = expected.optJSONObject("skip")?.optJSONObject("kotlin")?.keySet() ?: emptySet()
                val items = parse(dir.name, input, expected.optJSONObject("args") ?: JSONObject())
                val wanted = expected.getJSONArray("items")
                assertEquals("${dir.name}/$name item count", wanted.length(), items.size)
                for (i in 0 until wanted.length()) {
                    val want = wanted.getJSONObject(i)
                    for (key in want.keySet()) {
                        if (key in skipped) continue
                        val value = if (want.isNull(key)) null else want.getString(key)
                        val actual = field(items[i], key)
                        assertTrue("${dir.name}/$name item $i $key: got $actual, want $value", same(key, actual, value))
                    }
                }
                cases++
            }
        }
        assertTrue("no fixture cases found", cases > 0)
    }
}
