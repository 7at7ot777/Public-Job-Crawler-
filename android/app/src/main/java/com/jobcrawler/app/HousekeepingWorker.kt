package com.jobcrawler.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZonedDateTime

/**
 * What used to close every pass, now that each source runs on its own:
 * delivery retries, the review notice, pruning, the backup, the update
 * check; and what spans sources — the digest and the weekly summary.
 * Every 30 minutes, and two minutes after a source stores something new.
 * Each part is on its own, so one failing never skips the rest.
 */
class HousekeepingWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.init(applicationContext)
        val context = applicationContext
        val settings = Settings.load(context)
        val store = Store.shared(context)
        Net.init(context)
        val stopped = { isStopped }

        part("Retry") {
            if (!Net.isOnline()) return@part
            val sent = Pipeline(store, settings, stopped).retryPending()
            if (sent > 0) Log.info("Delivered $sent earlier item(s) on retry")
        }
        part("Review notice") { Applier(context, store).notifyReview() }
        part("Digest") { Housekeeping.digest(context, store, settings) }
        part("Weekly summary") { Housekeeping.weekly(context, store, settings) }

        // Hourly at most: the backup copies the whole database.
        if (Housekeeping.hourly(store, "housekeeping.hourly")) {
            part("Prune") { store.prune() }
            part("Backup") { if (Backup.folder(context) != null) Backup.save(context, store)?.let { Log.warn(it) } }
            part("Update check") { Updater.checkInBackground(context) }
        }
        return Result.success()
    }

    private fun part(name: String, work: () -> Unit) {
        if (isStopped) return
        runCatching(work).onFailure { Log.error("Housekeeping ($name) failed: ${it.javaClass.simpleName}: ${it.message}") }
    }
}

/** The parts of housekeeping that are more than a call: the digest and the weekly summary. */
object Housekeeping {
    /** True at most once an hour per [key]; the time is recorded when it says yes. */
    fun hourly(store: StateStore, key: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = store.getState(key)?.toLongOrNull()
        if (last != null && now - last in 0 until 3_600_000L) return false
        store.setState(key, now.toString())
        return true
    }

    /** The bundled weaker matches, every three hours while digest mode is on, and the leftovers once it is off. */
    fun digest(context: Context, store: Store, settings: Settings) {
        if (!settings.telegramReady) return
        val rows = store.digestRows()
        val now = System.currentTimeMillis()
        // Too long in the queue without making the top: let go, so it never arrives a day late.
        val (stale, fresh) = rows.partition { row ->
            val at = row.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now
            now - at > Digest.MAX_WAIT_MS
        }
        stale.forEach { store.markSkipped(it.id, "digest: not in the top ${Digest.TOP} within a day") }
        val last = store.getState(Digest.STATE)?.toLongOrNull()
        if (!Digest.isDue(now, last, fresh.size, settings.digest)) return

        val (text, ids) = Digest.render(fresh)
        if (ids.isEmpty()) return
        if (!SendPace.await()) return
        try {
            Telegram(settings.botToken, settings.chatId).send(text)
        } catch (error: TelegramPermanent) {
            ids.forEach { store.markFailed(it, error.message ?: "rejected", permanent = true) }
            Log.error("Digest rejected by Telegram: ${error.message}")
            return
        }
        // Rate limits and outages throw past here: the rows stay in the digest for the next tick.
        ids.forEach { store.markSent(it) }
        store.setState(Digest.STATE, now.toString())
        Log.info("Digest sent: ${ids.size} job(s)")
    }

    /** Friday's summary of the week, once, from the run history and the stored jobs. */
    fun weekly(context: Context, store: Store, settings: Settings) {
        val now = ZonedDateTime.now(Weekly.CAIRO)
        val lastSent = store.getState(Weekly.STATE)
        if (!Weekly.isDue(now, lastSent)) {
            // Missed by days: skip that week quietly rather than send it late.
            if (Weekly.isMissed(now, lastSent)) store.setState(Weekly.STATE, Weekly.slot(now).toString())
            return
        }
        val slot = Weekly.slot(now)
        val since = slot.minusDays(7).atTime(Weekly.HOUR, 0).atZone(Weekly.CAIRO).toInstant()
        val serper = com.jobcrawler.app.sources.SerperBudget(store)
        val data = Weekly.Data(
            from = slot.minusDays(7),
            to = slot.minusDays(1),
            facts = store.itemFacts(since),
            top = store.topScored(since, 5),
            runs = store.runs.since(since),
            applications = store.applications.counts(since),
            serperWeek = (0L..6L).sumOf { serper.used(slot.minusDays(it).toString()) },
            quiet = store.statesWithPrefix("").filterKeys { it.endsWith(".zeroStreak") }
                .mapNotNull { (key, value) -> value.toIntOrNull()?.takeIf { it >= Breakage.STREAK }?.let { key.removeSuffix(".zeroStreak") to it } }
                .toMap(),
        )
        val text = Weekly.render(data)
        if (settings.telegramReady) {
            if (!SendPace.await()) return
            Telegram(settings.botToken, settings.chatId).send(text)
        }
        store.setState(Weekly.STATE, slot.toString())
        Notices.weekly(context, Weekly.headline(data))
        Log.info("Weekly summary sent")
    }
}
