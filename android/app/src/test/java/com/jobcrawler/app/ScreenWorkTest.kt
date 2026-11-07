package com.jobcrawler.app

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Results of background work reach a live screen, and never one that is closed. */
@RunWith(RobolectricTestRunner::class)
class ScreenWorkTest {
    @Test
    fun `a live screen gets the result`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var shown = false
        activity.onUi { shown = true }
        ShadowLooper.idleMainLooper()
        assertTrue(shown)
    }

    @Test
    fun `a destroyed screen does not`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        controller.pause().stop().destroy()
        var shown = false
        activity.onUi { shown = true }
        ShadowLooper.idleMainLooper()
        assertFalse(shown)
    }

    @Test
    fun `work runs off the main thread`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val main = Thread.currentThread()
        val done = CountDownLatch(1)
        var ranOn: Thread? = null
        activity.io {
            ranOn = Thread.currentThread()
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(ranOn === main)
        assertEquals("screen-io", ranOn!!.name)
    }
}
