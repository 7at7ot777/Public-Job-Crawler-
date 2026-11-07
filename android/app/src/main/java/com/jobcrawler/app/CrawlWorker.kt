package com.jobcrawler.app

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import com.jobcrawler.app.sources.LinkedInJobs

/**
 * One source's run. Every source has its own worker and schedule
 * ([Scheduler]); WorkManager starts the process, this runs, and the process
 * is free to die again — nothing stays resident between runs.
 *
 * Up to [Lanes.MAX_PARALLEL] run at once. A worker that cannot get a slot,
 * or the lane of a remote another source is using, asks to be retried a
 * minute later instead of waiting: WorkManager has only a few threads.
 */
class CrawlWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.init(applicationContext)

        // Work an older build enqueued (one worker for every source): move the schedule over, then end.
        val key = inputData.getString(Scheduler.SOURCE) ?: run {
            Log.info("A run from before the per-source schedule; moving the schedule over")
            runCatching { Scheduler.upgrade(applicationContext) }
                .onFailure { Log.error("Schedule upgrade failed: ${it.message}") }
            return Result.success()
        }
        val force = inputData.getBoolean(Scheduler.FORCE, false)
        // LinkedIn Jobs run by hand over a chosen window (Home's play button); scheduled runs never carry it.
        val hours = inputData.getInt(Scheduler.HOURS, 0).takeIf { force && key == LinkedInJobs.KEY && it in 1..LinkedInJobs.MAX_HOURS }

        /*
         * The same source twice — its schedule and a resume, or a tap on Run
         * now — runs once. One asked for by hand waits for the other to end;
         * a replacement for a run that is winding down (after an app update)
         * waits too, or that run would be lost until the next tick.
         */
        val prior = active.putIfAbsent(key, this)
        if (prior != null) {
            if (force || prior.isStopped) {
                Log.info("$key is already running; this run starts once it ends")
                return Result.retry()
            }
            Log.info("$key is already running; skipping this one")
            return Result.success()
        }

        val permit = Lanes.tryEnter(key)
        if (permit == null) {
            active.remove(key, this)
            val lanes = Lanes.names(key)
            Log.info("$key waits: " + if (lanes.isEmpty()) "${Lanes.MAX_PARALLEL} sources are running" else "another ${lanes.joinToString("/")} source or ${Lanes.MAX_PARALLEL} sources are running")
            return Result.retry()
        }

        // What ended the app since the last run, a force stop or a run cut short: once, by the first worker in.
        val first = synchronized(active) { (running++ == 0) }
        if (first) {
            ExitWatch.check(applicationContext)
            ExitWatch.runStarted(applicationContext)
        }
        try {
            return crawl(key, force, hours)
        } finally {
            permit.close()
            active.remove(key, this)
            val last = synchronized(active) { (--running == 0) }
            if (last) ExitWatch.runEnded(applicationContext)
            // The run in between periodic ticks, for a source that wants one sooner than 15 minutes.
            if (!force) {
                runCatching { Scheduler.chainNext(applicationContext, key, inputData.getString(Scheduler.CHAIN)) }
                    .onFailure { Log.warn("$key: could not queue its next run: ${it.message}") }
            }
        }
    }

    private fun crawl(key: String, force: Boolean, hours: Int? = null): Result {
        val settings = Settings.load(applicationContext)
        Criteria.load(applicationContext)

        Net.init(applicationContext)

        if (!force) noteGap(key, Scheduler.intervalFor(key, settings))
        Log.info(
            when {
                hours != null -> "Run started: $key, by hand, last ${hours}h"
                force -> "Run started: $key, by hand"
                else -> "Run started: $key"
            },
        )
        goForeground(key)

        // No network constraint on the request (see Scheduler), so offline is checked here.
        if (!Net.awaitOnline(isStopped = { isStopped })) {
            if (isStopped) return Result.success()
            Log.warn("Offline; $key resumes once the phone is back online")
            saveSummary("$key paused: offline")
            Scheduler.resumeWhenOnline(applicationContext, key, hours)
            return Result.success()
        }

        // One connection for the whole process, never closed here: other sources are using it.
        val store = Store.shared(applicationContext)
        Mutes.load(store)
        Score.reload(store)
        val started = java.time.Instant.now()
        val run = RunContext()

        return try {
            val pipeline = Pipeline(
                store = store,
                settings = settings,
                isStopped = { isStopped },
                applier = Applier(applicationContext, store),
                onAlert = { kind, text -> Notices.breakage(applicationContext, kind.removePrefix("zero-"), text) },
                snapshotDir = java.io.File(applicationContext.filesDir, Snapshots.DIR),
            )
            val stat = pipeline.runOne(key, force, run, hours)
            if (stat == null) {
                Log.info("$key: not due")
                return Result.success()
            }
            if (run.lost) {
                Log.warn("Lost the network mid-run; $key resumes once the phone is back online")
                Scheduler.resumeWhenOnline(applicationContext, key, hours)
            } else if (run.retrySoon) {
                Log.warn("$key got no answer from the network; it runs again in ${Scheduler.RETRY_MINUTES} min")
                Scheduler.retrySoon(applicationContext, key)
            }
            noteOutage(store, key, failed = run.lost || run.retrySoon)
            saveSummary(stat.toString())
            RunReport.merge(applicationContext, started, java.time.Instant.now(), listOf(stat))
            // New rows: the review notice, retries, and the digest catch up soon, not at the next half hour.
            if (stat.stored > 0 || stat.error != null) Scheduler.housekeepSoon(applicationContext)
            Result.success()
        } catch (error: Exception) {
            Log.error("$key failed: ${error.javaClass.simpleName}: ${error.message}")
            saveSummary("$key failed: ${error.message}")
            noteOutage(store, key, failed = true)
            // The next periodic run is the retry; no extra back-off loop.
            Result.success()
        }
    }

    /** Counts the run toward [Outages] and posts the notification it calls for, if any. */
    private fun noteOutage(store: Store, key: String, failed: Boolean) {
        runCatching {
            Outages.record(store, key, failed)?.let { notice ->
                Log.warn("${notice.title}: ${notice.text}")
                Notices.outage(applicationContext, key, notice.title, notice.text)
            }
        }.onFailure { Log.warn("$key: could not record the outage: ${it.message}") }
    }

    /** Why the system cut a run short, so the log says more than "stopped". */
    override fun onStopped() {
        val reason = when (stopReason) {
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
            WorkInfo.STOP_REASON_PREEMPT -> "preempted"
            WorkInfo.STOP_REASON_TIMEOUT -> "out of time"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "device state (Doze)"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "network constraint"
            WorkInfo.STOP_REASON_QUOTA -> "background quota"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background restriction"
            WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
            WorkInfo.STOP_REASON_USER -> "user"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
            // What WorkManager reports when it reschedules everything, as it does after an update.
            WorkInfo.STOP_REASON_UNKNOWN -> "no reason given (often right after an app update)"
            // WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT.
            -128 -> "foreground time limit reached (Android 15 dataSync: 6 h a day)"
            else -> "code $stopReason"
        }
        val key = inputData.getString(Scheduler.SOURCE) ?: "a run"
        Log.warn("$key stopped by the system: $reason. ${Power.describe(applicationContext)}")
    }

    /**
     * The run as foreground work, under a quiet "Searching for jobs" notice:
     * phone cleaners and swipe-away leave a foreground app alone far more
     * often than a background one. Android may refuse (a background start
     * not allowed, the daily data-sync allowance spent); the run then goes on
     * in the background, as it always did.
     */
    private fun goForeground(key: String) {
        runCatching { setForegroundAsync(RunNotice.info(applicationContext, key)).get() }
            .onFailure {
                val cause = (it as? java.util.concurrent.ExecutionException)?.cause ?: it
                Log.warn("Running in the background only (${cause.javaClass.simpleName}); a phone cleaner may stop it")
            }
    }

    /**
     * A scheduled run much later than its schedule says — overnight, say —
     * names what the phone was doing to the app, so the gap explains itself.
     */
    private fun noteGap(key: String, intervalMinutes: Long) {
        val prefs = applicationContext.getSharedPreferences("status", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("$key.lastStartedAt", 0L)
        prefs.edit().putLong("$key.lastStartedAt", now).putLong("lastStartedAt", now).apply()
        if (last == 0L) return

        val gapMinutes = (now - last) / 60_000
        if (gapMinutes < maxOf(intervalMinutes * 3, 60L)) return
        // Paused, a gap is what was asked for.
        if (!runCatching { Scheduler.isScheduled(applicationContext) }.getOrDefault(true)) return
        Log.warn(
            "$key: no run for ${gapMinutes / 60} h ${gapMinutes % 60} min, though its schedule is every " +
                "$intervalMinutes min. ${Power.describe(applicationContext)}",
        )
    }

    companion object {
        /** The worker running each source. WorkManager runs every worker in this one process, so a map is enough. */
        private val active = ConcurrentHashMap<String, CrawlWorker>()
        private var running = 0

        fun activeKeys(): Set<String> = active.keys.toSet()
    }

    private fun saveSummary(summary: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        applicationContext.getSharedPreferences("status", Context.MODE_PRIVATE).edit()
            .putString("lastRun", time)
            .putString("lastSummary", summary)
            .apply()
    }
}
