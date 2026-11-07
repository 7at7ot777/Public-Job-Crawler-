package com.jobcrawler.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the home screen shows, built without the screen. */
@RunWith(RobolectricTestRunner::class)
class HomeStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
    }

    @After
    fun tearDown() {
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
    }

    @Test
    fun `a fresh install shows nothing stored and nothing scheduled`() {
        val state = HomeState.load(context, Store.shared(context))
        assertEquals(0, state.stored)
        assertEquals(0, state.waiting)
        assertFalse(state.scheduled)
        assertFalse(state.running)
        assertEquals(emptyMap<String, Int>(), state.applied)
    }

    @Test
    fun `a stored job is counted`() {
        val store = Store.shared(context)
        store.insertIfNew(Item(source = "wuzzuf", externalId = "1", title = "Backend Developer", url = "https://example.com/1", company = "Acme"))
        assertEquals(1, HomeState.load(context, store).stored)
    }

    @Test
    fun `the slow and paid sets come from the registry`() {
        // The paid Serper sources are back on.
        assertEquals(setOf("linkedin_posts", "glassdoor", "gulf_posts"), HomeState.PAID_SOURCES)
        // The 3-hour slow lane: the worldwide SLOW sources and LinkedIn Saudi & UAE. Recruitera is every 6 h, on its own.
        assertEquals(
            setOf("relocateme", "remote_feeds", "jobgether", "sponsor_boards", "gulf_jobs", "ai_data"),
            HomeState.SLOW_SOURCES,
        )
    }
}
