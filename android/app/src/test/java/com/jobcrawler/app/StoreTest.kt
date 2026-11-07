package com.jobcrawler.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The dedup and delivery state, against a real SQLite file. */
@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store

    @Before
    fun open() {
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
        store = Store.shared(context)
    }

    @After
    fun close() {
        Store.closeShared()
        context.deleteDatabase(Store.NAME)
    }

    private fun item(source: String, id: String, company: String? = "Acme", title: String = "Backend Developer") =
        Item(source = source, externalId = id, title = title, url = "https://example.com/$id", company = company)

    @Test
    fun `the same posting from the same board is one row`() {
        val (first, created) = store.insertIfNew(item("wuzzuf", "1"))
        assertTrue(created)
        val (again, createdAgain) = store.insertIfNew(item("wuzzuf", "1"))
        assertFalse(createdAgain)
        assertEquals(first!!.id, again!!.id)
    }

    @Test
    fun `the same job from a second board is caught by its fingerprint`() {
        val (first, _) = store.insertIfNew(item("wuzzuf", "1", company = "Acme Inc."))
        val (second, created) = store.insertIfNew(item("tanqeeb", "x", company = "acme inc"))
        assertFalse(created)
        assertEquals(first!!.id, second!!.id)
        // Without a company there is no fingerprint: titles alone collide.
        assertTrue(store.insertIfNew(item("tanqeeb", "y", company = null)).second)
    }

    @Test
    fun `a failed send is retried after its wait, a permanent one never`() {
        val row = store.insertIfNew(item("wuzzuf", "1")).first!!
        val parked = store.insertIfNew(item("wuzzuf", "2", company = "Other")).first!!
        store.markFailed(row.id, "429")
        store.markFailed(parked.id, "chat not found", permanent = true)
        // Fresh failures wait RETRY_AFTER_MINUTES; age them past it.
        store.writableDatabase.execSQL("UPDATE items SET updated_at = ?", arrayOf(Instant.EPOCH.toString()))
        assertEquals(listOf(row.id), store.getRetryable(10).map { it.id })
        store.markSent(row.id)
        assertEquals(Status.SENT, store.findById(row.id)!!.status)
        assertTrue(store.getRetryable(10).isEmpty())
    }

    @Test
    fun `prune drops old delivered rows but keeps pending ones`() {
        val sent = store.insertIfNew(item("wuzzuf", "1")).first!!
        val pending = store.insertIfNew(item("wuzzuf", "2", company = "Other")).first!!
        store.markSent(sent.id)
        store.checked.mark("wuzzuf", "9")
        store.writableDatabase.execSQL("UPDATE items SET created_at = ?", arrayOf(Instant.EPOCH.toString()))
        store.writableDatabase.execSQL("UPDATE checked_urls SET checked_at = ?", arrayOf(Instant.EPOCH.toString()))
        store.prune(days = 90)
        assertNull(store.findById(sent.id))
        assertEquals(pending.id, store.findById(pending.id)!!.id)
        assertTrue(store.checked.ids("wuzzuf").isEmpty())
    }

    @Test
    fun `applications, mutes and runs live in their own tables`() {
        val job = item("wuzzuf", "1")
        assertNull(store.applications.emailOutcome(job))
        store.applications.record(job, "email", "hr@acme.eg")
        assertEquals("email" to "hr@acme.eg", store.applications.emailOutcome(job))
        assertEquals(mapOf("email" to 1), store.applications.counts())

        store.mutes.add("company", "acme", "Acme")
        assertEquals(listOf("acme"), store.mutes.all().map { it.value })
        store.mutes.remove("company", "acme")
        assertTrue(store.mutes.all().isEmpty())

        store.runs.record(RunRow(source = "wuzzuf", ranAt = Instant.now(), parsed = 3, matched = 2, stored = 1, sent = 1))
        assertEquals(listOf(3), store.runs.since(Instant.EPOCH, "wuzzuf").map { it.parsed })
    }

    @Test
    fun `after a restore closes the shared store, the next one reopens the file`() {
        store.setState("k", "v")
        Store.closeShared()
        val reopened = Store.shared(context)
        assertNotSame(store, reopened)
        assertEquals("v", reopened.getState("k"))
    }
}
