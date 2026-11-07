package com.jobcrawler.app

import com.jobcrawler.app.sources.LinkedInJobs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkedInRotationTest {
    private val key = LinkedInJobs.KEY
    private val places = listOf(
        "Egypt", "Cairo, Egypt", "Giza, Egypt", "Maadi, Cairo, Egypt", "Heliopolis, Cairo, Egypt", "Obour City, Egypt",
        "Alexandria, Egypt", "Sheraton, Cairo, Egypt", "Mohandessin, Giza, Egypt", "Dokki, Giza, Egypt",
    )

    /** One clean run of the rotation: the place it searched. */
    private fun run(state: MapState, now: Long): String {
        val turn = LinkedInJobs.rotationTurn(state, key, places.size)!!
        LinkedInJobs.recordClean(state, key, listOf(places[turn]), turn, places.size, now)
        return places[turn]
    }

    @Test
    fun cyclesThroughEveryPlaceThenStartsAgain() {
        val state = MapState()
        val searched = (0..places.size).map { run(state, now = it * 5 * 60_000L) }
        assertEquals(places + places.first(), searched)
    }

    @Test
    fun theDefaultPlacesAreTheEgyptOnes() {
        assertEquals(places, Criteria[Criteria.Kind.SEARCH_PLACES])
    }

    @Test
    fun onePlaceHasNothingToRotate() {
        assertNull(LinkedInJobs.rotationTurn(MapState(), key, 1))
    }

    @Test
    fun aRunThatWasNotCleanSearchesTheSamePlaceAgain() {
        val state = MapState()
        run(state, now = 0)
        // A blocked run records nothing, so the next one has the same turn.
        assertEquals(1, LinkedInJobs.rotationTurn(state, key, places.size))
        assertEquals(1, LinkedInJobs.rotationTurn(state, key, places.size))
    }

    @Test
    fun aShorterListWrapsTheSavedTurn() {
        val state = MapState()
        repeat(5) { run(state, now = 0) }
        assertEquals(2, LinkedInJobs.rotationTurn(state, key, 3))
    }

    @Test
    fun eachPlaceLooksBackToItsOwnLastCleanSearch() {
        val state = MapState()
        val minute = 60_000L
        // Six runs, five minutes apart: Egypt at 0, Obour at 25.
        for (i in 0 until 6) run(state, now = i * 5 * minute)

        val now = 30 * minute
        val egypt = LinkedInJobs.lastOk(state, key, "Egypt")
        assertEquals(0L, egypt)
        // 30 minutes since Egypt, plus ten, is inside the one-hour floor.
        assertEquals("r3600", LinkedInJobs.windowSince(egypt, now))

        // A cycle twice that long (12 places, or a rest after a 429) reaches back further.
        assertEquals("r${(60 + 10) * 60}", LinkedInJobs.windowSince(egypt, 60 * minute))
    }

    @Test
    fun aPlaceNeverSearchedStartsFromTheSourcesLastCleanRun() {
        val state = MapState()
        LinkedInJobs.recordClean(state, key, listOf("Egypt"), turn = null, count = 1, now = 1_000L)
        assertEquals(1_000L, LinkedInJobs.lastOk(state, key, "Obour City, Egypt"))
        assertEquals(1_000L, LinkedInJobs.lastOk(state, key, null))
    }
}
