package com.jobcrawler.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source registry (Pipeline.SOURCES) is the one place a source's facts
 * live. These pin what the hand-kept tables said before they were derived
 * from it, so a new source cannot silently land in the wrong lane or region.
 */
class RegistryTest {
    private val settings = Settings(keywords = listOf("Laravel"), sources = Defaults.sources.toSet(), intervalMinutes = 30)

    @Test
    fun `keys are unique and each spec builds the source it names`() {
        val keys = Pipeline.SOURCES.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (spec in Pipeline.SOURCES) assertEquals(spec.key, spec.create(settings).key)
    }

    @Test
    fun `every source has a name in Settings`() {
        val missing = Pipeline.SOURCES.map { it.key }.filter { it !in SettingsActivity.SOURCE_LABELS }
        assertTrue("no label for $missing", missing.isEmpty())
    }

    @Test
    fun `the lanes are what they were`() {
        // OFF (2026-11-05): LinkedIn posts and the Serper sources are retired.
        // OFF (2026-11-05): LinkedIn worldwide too.
        assertEquals(setOf("linkedin_jobs"), Pipeline.keysOn(Pipeline.Lane.LINKEDIN))
        assertEquals(emptySet<String>(), Pipeline.keysOn(Pipeline.Lane.SERPER))
        assertEquals(listOf("LinkedIn"), Lanes.names("linkedin_jobs"))
        assertEquals(emptyList<String>(), Lanes.names("linkedin_worldwide"))
        assertEquals(emptyList<String>(), Lanes.names("linkedin_posts"))
        assertEquals(emptyList<String>(), Lanes.names("wuzzuf"))
    }

    @Test
    fun `regions are what they were, retired keys included`() {
        val worldwide = setOf("jobright", "relocateme", "abroad_jobs", "remote_feeds", "hn_hiring", "job_apis", "sponsor_boards", "linkedin_worldwide", "jobgether")
        val gulf = setOf("gulf_jobs", "gulf_posts")
        val freelance = setOf("mostaql", "khamsat")
        assertEquals(worldwide, Pipeline.keysIn(Region.WORLDWIDE))
        assertEquals(gulf, Pipeline.keysIn(Region.GULF))
        assertEquals(freelance, Pipeline.keysIn(Region.FREELANCE))
        // Gigs are neither emailed as abroad nor auto-emailed as Gulf jobs.
        assertEquals(worldwide, Mailer.ABROAD_SOURCES)
        assertEquals(gulf, Mailer.AUTO_SOURCES)
        for (key in Pipeline.SOURCES.map { it.key } + Pipeline.RETIRED.keys) {
            val expected = when (key) {
                // ai_data is filed under Worldwide for Settings, though its
                // registry home stays Egypt so it is not emailed as abroad.
                "ai_data" -> Region.WORLDWIDE
                in gulf -> Region.GULF
                in worldwide -> Region.WORLDWIDE
                in freelance -> Region.FREELANCE
                else -> Region.EGYPT
            }
            assertEquals(key, expected, Region.home(key))
        }
        assertEquals(Region.EGYPT, Region.home("no_such_source"))
    }

    @Test
    fun `retired sources are the registry's`() {
        // The worldwide remote sources (except LinkedIn) are back on, so only these stay retired.
        assertEquals(setOf("abroad_jobs", "gulf_jobs", "linkedin_posts", "glassdoor", "gulf_posts", "linkedin_worldwide", "ai_data"),
            Defaults.retiredSources,
        )
    }
}
