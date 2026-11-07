package com.jobcrawler.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric is wired up: a real SQLite file and a real Context on the JVM. */
@RunWith(RobolectricTestRunner::class)
class RobolectricSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun close() = Store.closeShared()

    @Test
    fun aFreshDatabaseIsEmptyAndOnTheCurrentSchema() {
        val store = Store.shared(context)
        assertTrue(store.isEmpty())
        assertEquals(4, store.readableDatabase.version)
        store.setState("smoke", "1")
        assertEquals("1", store.getState("smoke"))
    }
}
