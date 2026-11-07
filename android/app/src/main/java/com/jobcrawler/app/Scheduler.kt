package com.jobcrawler.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.jobcrawler.app.sources.LinkedInJobs

/**
 * Every unique-work name the schedule uses, in one place. WorkManager keeps
 * them on the phone, so a name changed here orphans work an older build
 * queued: WorkNamesTest pins them.
 */
internal object WorkNames {
    /** Each source's own schedule. */
    fun periodic(key: String) = "crawl-src-$key"
    /** Run now, per source. */
    fun now(key: String) = "crawl-now-$key"
    /** A run cut short by an outage, per source. */
    fun resume(key: String) = "crawl-resume-$key"
    /** A run whose requests went unanswered though the phone was online, retried after a delay. */
    fun retry(key: String) = "crawl-retry-$key"
    /** One source, run by hand, and its resume. */
    fun sourceRun(key: String) = "crawl-source-$key"
    fun sourceResume(key: String) = "crawl-source-resume-$key"
    const val HOUSEKEEPING = "housekeeping"
    const val HOUSEKEEP_SOON = "housekeeping-soon"

    /**
     * The runs between periodic ticks: `crawl-next-<key>-a` and `-b`, taking
     * turns. Two names because a worker cannot replace its own unique work
     * without cancelling itself; each chained run queues the other name.
     */
    val NEXT_SLOTS = listOf("a", "b")
    fun next(key: String, slot: String) = "crawl-next-$key-$slot"

    /** The slot a chained run queues after one queued under [from]; a run not from a chain starts at the first. */
    fun nextSlot(from: String?): String = if (from in NEXT_SLOTS) NEXT_SLOTS.first { it != from } else NEXT_SLOTS.first()

    /** What the one-worker build used; cancelled on upgrade. */
    val LEGACY = listOf("crawl-periodic", "crawl-now", "crawl-resume")
}

/**
 * Every source's WorkManager schedule: its periodic work, the runs chained
 * in between, runs by hand, resumes after an outage, and housekeeping.
 */
object Scheduler {
    /** Every crawl, of any kind. */
    private const val TAG_ALL = "crawl"
    private const val TAG_PERIODIC = "crawl-periodic"
    private const val TAG_RESUME = "crawl-resume"
    /** Runs asked for by hand, as Home shows them; `crawl-source:<key>` names the source. */
    private const val SOURCE_TAG = "crawl-source"


    /** The input key naming the source a run is for. */
    const val SOURCE = "source"
    /** Input: run it whether or not it is due or switched on (a run by hand). */
    const val FORCE = "force"
    /** With a run by hand of LinkedIn Jobs: search the last this many hours. */
    const val HOURS = "hours"
    /** Input on a chained run: which of the two [WorkNames.next] names it was queued under. */
    const val CHAIN = "chain"

    /*
     * No NetworkType.CONNECTED constraint on the runs: WorkManager stops a
     * running worker the instant the constraint drops, and a Wi-Fi ↔ mobile
     * data switch drops it for a second. The worker waits out the gap itself
     * (Net). Only the resume runs carry it, and only to start: they are
     * enqueued while offline and should wait, however long, for a network.
     */
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private const val SCHEDULE_VERSION = 3

    /** A source's minutes between runs: the person's choice, else its own, else the schedule's; never under 15. */
    fun intervalFor(key: String, settings: Settings, defaultMinutes: Long = settings.intervalMinutes): Long =
        (settings.sourceIntervals[key] ?: Pipeline.spec(key)?.everyMinutes ?: defaultMinutes).coerceAtLeast(15)

    /**
     * Sources that run more often than WorkManager's 15-minute floor, by key,
     * in minutes. Their periodic work stays (it restarts a broken chain); the
     * runs in between are one-time work queued as each run ends.
     */
    private val CHAINED = mapOf(LinkedInJobs.KEY to LinkedInJobs.EVERY_MINUTES)

    /**
     * Queue [key]'s next run [CHAINED] minutes from now. A chained run ([from]
     * names its slot) queues the other slot. Any other run — the periodic
     * tick, a resume — starts a chain only when none is pending, so the ticks
     * do not keep pushing it back. Nothing while the schedule is paused, the
     * source is off, or the person gave it an interval of their own.
     */
    fun chainNext(context: Context, key: String, from: String?) {
        val minutes = CHAINED[key] ?: return
        val settings = Settings.load(context)
        if (key !in settings.sources || !canRun(key) || settings.sourceIntervals[key] != null) return
        if (!isScheduled(context)) return

        val manager = WorkManager.getInstance(context)
        val slot = if (from in WorkNames.NEXT_SLOTS) {
            WorkNames.nextSlot(from)
        } else {
            val pending = WorkNames.NEXT_SLOTS.any { name ->
                manager.getWorkInfosForUniqueWork(WorkNames.next(key, name)).get().any { !it.state.isFinished }
            }
            if (pending) return
            WorkNames.nextSlot(null)
        }

        val request = OneTimeWorkRequestBuilder<CrawlWorker>()
            .setInputData(Data.Builder().putAll(input(key, force = false)).putString(CHAIN, slot).build())
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .addTag(TAG_ALL)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(WorkNames.next(key, slot), ExistingWorkPolicy.REPLACE, request)
    }

    /** Whether a source can do anything at all: the paid ones need their keys built in. */
    fun canRun(key: String): Boolean = Pipeline.spec(key)?.canRun() ?: true

    /**
     * Each switched-on source on its own schedule ([intervalMinutes] for the
     * sources that follow Settings), and housekeeping; switched-off ones are
     * cancelled. UPDATE keeps a running worker and its timing.
     */
    fun start(context: Context, intervalMinutes: Long) {
        val settings = Settings.load(context)
        val manager = WorkManager.getInstance(context)
        for (spec in Pipeline.SOURCES) {
            if (spec.key in settings.sources && canRun(spec.key)) {
                val minutes = intervalFor(spec.key, settings, intervalMinutes)
                val request = PeriodicWorkRequestBuilder<CrawlWorker>(minutes, TimeUnit.MINUTES)
                    .setInputData(input(spec.key, force = false))
                    .addTag(TAG_ALL)
                    .addTag(TAG_PERIODIC)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                    .build()
                manager.enqueueUniquePeriodicWork(WorkNames.periodic(spec.key), ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                manager.cancelUniqueWork(WorkNames.periodic(spec.key))
                cancelChain(manager, spec.key)
            }
        }
        val housekeeping = PeriodicWorkRequestBuilder<HousekeepingWorker>(30, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(WorkNames.HOUSEKEEPING, ExistingPeriodicWorkPolicy.UPDATE, housekeeping)
    }

    /**
     * From one worker for every source to one per source, once: the old
     * schedule is cancelled, sources that already have rows are marked as
     * seeded (they must not seed again and drop a run's jobs), and the new
     * schedules start if the old one was on. Blocks: call it off the main thread.
     */
    fun upgrade(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences("status", Context.MODE_PRIVATE)
        if (!force && prefs.getInt("scheduleVersion", 1) >= SCHEDULE_VERSION) return
        val manager = WorkManager.getInstance(context)
        val wasOn = force || isScheduled(context) ||
            manager.getWorkInfosForUniqueWork(WorkNames.LEGACY[0]).get().any { !it.state.isFinished }
        /*
         * The old build seeded once, for the whole database. Anything stored
         * means every source has had its first run: none seeds again, or a
         * source that simply never matched would drop its next run's jobs.
         */
        val store = Store.shared(context)
        if (!store.isEmpty()) {
            for (spec in Pipeline.SOURCES) {
                if (store.getState(Pipeline.seededKey(spec.key)) == null) store.setState(Pipeline.seededKey(spec.key), "1")
            }
        }
        for (name in WorkNames.LEGACY) manager.cancelUniqueWork(name)
        if (wasOn) start(context, Settings.load(context).intervalMinutes)
        prefs.edit().putInt("scheduleVersion", SCHEDULE_VERSION).apply()
    }

    fun stop(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG_PERIODIC)
        manager.cancelAllWorkByTag(TAG_RESUME)
        for (spec in Pipeline.SOURCES) {
            manager.cancelUniqueWork(WorkNames.periodic(spec.key))
            cancelChain(manager, spec.key)
        }
        manager.cancelUniqueWork(WorkNames.HOUSEKEEPING)
        for (name in WorkNames.LEGACY) manager.cancelUniqueWork(name)
        // Runs asked for by hand are left alone: Pause stops the schedule, not a tap.
    }

    private fun cancelChain(manager: WorkManager, key: String) {
        if (key !in CHAINED) return
        for (name in WorkNames.NEXT_SLOTS) manager.cancelUniqueWork(WorkNames.next(key, name))
    }

    /**
     * A source that found no network hands over here: one run of it, started
     * by WorkManager as soon as the phone is online again. KEEP, so an outage
     * spanning several ticks still queues just one.
     */
    fun resumeWhenOnline(context: Context, key: String, hours: Int? = null) {
        val manual = WorkManager.getInstance(context).getWorkInfosByTag("$SOURCE_TAG:$key").get().any { !it.state.isFinished }
        val (name, request) = if (manual) {
            WorkNames.sourceResume(key) to sourceRequest(key, online, hours)
        } else {
            WorkNames.resume(key) to OneTimeWorkRequestBuilder<CrawlWorker>()
                .setInputData(input(key, force = false))
                .addTag(TAG_ALL)
                .addTag(TAG_RESUME)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                .build()
        }
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * A source whose requests got no answer although the phone was online:
     * one run of it [RETRY_MINUTES] from now, once there is a network. KEEP,
     * so a run that fails again while one is queued does not push it back.
     */
    fun retrySoon(context: Context, key: String) {
        val request = OneTimeWorkRequestBuilder<CrawlWorker>()
            .setInputData(input(key, force = false))
            .addTag(TAG_ALL)
            .addTag(TAG_RESUME)
            .setInitialDelay(RETRY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WorkNames.retry(key), ExistingWorkPolicy.KEEP, request)
    }

    /** How long after an unanswered run the retry starts. */
    const val RETRY_MINUTES = 10L

    /**
     * One source, once, whether or not it is due: a daily or three-hourly
     * source whose turn passed while the phone was offline. Offline, it waits
     * for the network like any run. KEEP, so tapping twice queues one.
     *
     * [hours] (LinkedIn Jobs only) searches the last that many hours instead
     * of the time since its last run; scheduled runs never carry it.
     */
    fun runSource(context: Context, key: String, hours: Int? = null) {
        WorkManager.getInstance(context).enqueueUniqueWork(WorkNames.sourceRun(key), ExistingWorkPolicy.KEEP, sourceRequest(key, hours = hours))
    }

    private fun input(key: String, force: Boolean, hours: Int? = null) =
        Data.Builder().putString(SOURCE, key).putBoolean(FORCE, force).apply { if (hours != null) putInt(HOURS, hours) }.build()

    private fun sourceRequest(key: String, constraints: Constraints? = null, hours: Int? = null) =
        OneTimeWorkRequestBuilder<CrawlWorker>()
            .setInputData(input(key, force = true, hours = hours))
            .addTag(TAG_ALL)
            .addTag(SOURCE_TAG)
            .addTag("$SOURCE_TAG:$key")
            // Retried while the source is running already, or every slot is taken (see CrawlWorker.doWork).
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .apply { if (constraints != null) setConstraints(constraints) }
            .build()

    /** The sources run by hand that are queued or under way, by state. */
    fun sourceRuns(context: Context): Map<String, WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosByTag(SOURCE_TAG).get()
            .filter { !it.state.isFinished }
            .mapNotNull { info ->
                val key = info.tags.firstOrNull { it.startsWith("$SOURCE_TAG:") }?.removePrefix("$SOURCE_TAG:")
                key?.let { it to info.state }
            }
            // Running wins over a second, queued request for the same source.
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, states) -> if (WorkInfo.State.RUNNING in states) WorkInfo.State.RUNNING else states.first() }

    /** Every switched-on source, once, now: those not due skip themselves, as on a scheduled tick. */
    fun runNow(context: Context) {
        val settings = Settings.load(context)
        val manager = WorkManager.getInstance(context)
        for (spec in Pipeline.SOURCES) {
            if (spec.key !in settings.sources || !canRun(spec.key)) continue
            val request = OneTimeWorkRequestBuilder<CrawlWorker>()
                .setInputData(input(spec.key, force = false))
                .addTag(TAG_ALL)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniqueWork(WorkNames.now(spec.key), ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Housekeeping in two minutes: several sources finishing together still make one run. */
    fun housekeepSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<HousekeepingWorker>().setInitialDelay(2, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WorkNames.HOUSEKEEP_SOON, ExistingWorkPolicy.KEEP, request)
    }

    /** Whether a crawl is under way right now, scheduled or run by hand. */
    fun isRunning(context: Context): Boolean =
        CrawlWorker.activeKeys().isNotEmpty() ||
            WorkManager.getInstance(context).getWorkInfosByTag(TAG_ALL).get().any { it.state == WorkInfo.State.RUNNING }

    /** A run handed over by an outage, waiting for the phone to come back online. */
    fun isResumePending(context: Context): Boolean =
        WorkManager.getInstance(context).getWorkInfosByTag(TAG_RESUME).get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }

    fun isScheduled(context: Context): Boolean =
        WorkManager.getInstance(context).getWorkInfosByTag(TAG_PERIODIC).get()
            .any { !it.state.isFinished }
}
