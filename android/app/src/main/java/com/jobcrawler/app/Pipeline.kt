package com.jobcrawler.app

import com.jobcrawler.app.sources.AbroadFit
import com.jobcrawler.app.sources.AbroadJobs
import com.jobcrawler.app.sources.AiData
import com.jobcrawler.app.sources.HnHiring
import com.jobcrawler.app.sources.JobApis
import com.jobcrawler.app.sources.Khamsat
import com.jobcrawler.app.sources.Mostaql
import com.jobcrawler.app.sources.RemoteFeeds
import com.jobcrawler.app.sources.SponsorBoards
import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.CareerSites
import com.jobcrawler.app.sources.DrJob
import com.jobcrawler.app.sources.Glassdoor
import com.jobcrawler.app.sources.Jobgether
import com.jobcrawler.app.sources.Jobright
import com.jobcrawler.app.sources.LinkedInJobs
import com.jobcrawler.app.sources.LinkedInWorldwide
import com.jobcrawler.app.sources.LinkedInPosts
import com.jobcrawler.app.sources.Recruitera
import com.jobcrawler.app.sources.RelocateMe
import com.jobcrawler.app.sources.Source
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import com.jobcrawler.app.sources.Wuzzuf

/**
 * fetch → filter → store → deliver, for one source, as src/pipeline.js.
 *
 * Each source runs in its own worker (CrawlWorker), so a run is one source:
 * a slow walk no longer eats a quick source's time, and one source failing or
 * out of quota leaves the rest alone. Delivery is per item, so one throttled
 * message never blocks or duplicates the rest. What used to close every pass
 * (retries, pruning, the review notice) is HousekeepingWorker's now.
 */
class Pipeline(
    private val store: Store,
    private val settings: Settings,
    private val isStopped: () -> Boolean = { false },
    private val applier: Applier? = null,
    /** A source that went quiet: the phone's own notification, beside the Telegram alert. */
    private val onAlert: (kind: String, text: String) -> Unit = { _, _ -> },
    /** Where a page that parsed to nothing is kept; null keeps none (tests). */
    private val snapshotDir: java.io.File? = null,
) {
    data class SourceStat(
        val source: String,
        var parsed: Int = 0,
        /** What the source says the remote listed, before its own checks; null when it does not say ([Source.listed]). */
        var listed: Int? = null,
        var matched: Int = 0,
        var stored: Int = 0,
        var delivered: Int = 0,
        var seeded: Int = 0,
        var digested: Int = 0,
        var blocked: Boolean = false,
        var error: String? = null,
        val rejected: MutableMap<String, Int> = sortedMapOf(),
    ) {
        /** How a run ended: read this, not [blocked] and [error] together. */
        sealed interface End {
            /** Fetched without a refusal or an error (it may still have parsed nothing). */
            data object Clean : End
            /** The remote refused or stopped answering; what was parsed before still counts. */
            data class Blocked(val reason: String?) : End
            /** The source broke; nothing from it this run. */
            data class Failed(val reason: String) : End
        }

        val end: End
            get() = when {
                blocked -> End.Blocked(error)
                error != null -> End.Failed(error!!)
                else -> End.Clean
            }

        val clean: Boolean get() = end == End.Clean

        fun block(reason: String?) {
            blocked = true
            error = reason
        }

        fun fail(reason: String) {
            error = reason
        }

        override fun toString() = buildString {
            append("$source: parsed $parsed, matched $matched, new $stored, sent $delivered")
            if (digested > 0) append(", digest $digested")
            if (seeded > 0) append(", seeded $seeded")
            when (val end = end) {
                is End.Blocked -> append(", STOPPED: ${end.reason}")
                is End.Failed -> append(", ERROR: ${end.reason}")
                End.Clean -> Unit
            }
        }
    }

    /** A remote that several sources spend one budget on; they take turns ([Lanes]). */
    enum class Lane(val label: String) { LINKEDIN("LinkedIn"), SERPER("Serper") }

    /**
     * One entry of the registry: everything the app knows about a source by
     * its key — how to build it, how often its worker runs, where its jobs
     * are, which shared remotes it talks to. [Region.home], [Lanes],
     * [Mailer.AUTO_SOURCES] and the home screen all read it from here.
     */
    data class Spec(
        val key: String,
        /** Minutes between runs; null follows the schedule picked in Settings. */
        val everyMinutes: Long?,
        /** Where its jobs are, as Settings groups it; a Gulf source's jobs are emailed, never posted. */
        val home: Region = Region.EGYPT,
        /** Remotes whose budget it shares with other sources. A [Lane.SERPER] source needs the Serper key. */
        val lanes: Set<Lane> = emptySet(),
        /** Anything else it needs built in before it can do anything (its API keys). */
        val needs: () -> Boolean = { true },
        val create: (Settings) -> Source,
    ) {
        /** Whether it can do anything at all: a paid one needs its key built in. */
        fun canRun(): Boolean = (Lane.SERPER !in lanes || BuildConfig.SERPER_API_KEY.isNotBlank()) && needs()
    }

    private val telegram = if (settings.telegramReady) Telegram(settings.botToken, settings.chatId) else null

    private var run = RunContext()

    /**
     * One source, if it is due (or [force]d: run by hand, due or not,
     * switched on or not). Null when there was nothing to do.
     *
     * [hours]: LinkedIn Jobs run by hand over the last this many hours,
     * instead of since its last run.
     */
    fun runOne(key: String, force: Boolean = false, run: RunContext = RunContext(), hours: Int? = null): SourceStat? {
        this.run = run
        val spec = spec(key) ?: return null.also { Log.warn("No source called $key") }
        if (!force && key !in settings.sources) return null
        val source = if (key == LinkedInJobs.KEY && force && hours != null) {
            LinkedInJobs(timeWindow = LinkedInJobs.windowForHours(hours))
        } else {
            spec.create(settings)
        }
        if (!force && !source.isDue(store)) return null

        /*
         * A source's first run is seeded, not sent: otherwise its whole
         * window arrives at once. Per source, so enabling a new one later
         * does not mute the rest, and the rest do not flood when it seeds.
         */
        val seeding = shouldSeed(store.getState(seededKey(key)), store.isEmpty(key))
        if (seeding) Log.warn("$key has stored nothing yet; this run records what is out there without sending it")

        Log.info("── ${source.key} ──")
        val stat = runSource(source, seeding, System.currentTimeMillis() + Limits.RUN_BUDGET_MS)
        if (stat.clean && !run.lost) store.setState(seededKey(key), "1")
        return stat
    }

    /** Rows a stalled delivery left behind (HousekeepingWorker): pending or failed, past their retry wait. */
    fun retryPending(run: RunContext = RunContext()): Int {
        this.run = run
        if (telegram == null) return 0
        return deliver(store.getRetryable(Limits.MAX_ITEMS_PER_RUN))
    }

    companion object {
        /*
         * Every source, its schedule (from each source's own Cadence), and
         * how to build it. The once-a-day sources (posts, Glassdoor) run
         * hourly and their isDue lets the first run after 07:00 through.
         */
        val SOURCES: List<Spec> = listOf(
            // boardsGulf: Saudi/UAE jobs too, posted like Egypt's.
            Spec(Wuzzuf.KEY, null) { current -> Wuzzuf(gulf = current.boardsGulf) },
            Spec(Workable.KEY, null) { current -> Workable(gulf = current.boardsGulf) },
            Spec(CareerSites.KEY, null) { current -> CareerSites(sites = CareerSites.SITES + current.careerSites.map { it.toSite() }) },
            Spec(Tanqeeb.KEY, null) { current -> Tanqeeb(gulf = current.boardsGulf) },
            // Dr.Job: Egypt, and Saudi/UAE with boardsGulf, posted like Egypt's.
            Spec(DrJob.KEY, null) { current -> DrJob(gulf = current.boardsGulf) },
            Spec(LinkedInJobs.KEY, LinkedInJobs.EVERY_MS / 60_000, lanes = setOf(Lane.LINKEDIN)) { _ -> LinkedInJobs() },
            // OFF (2026-11-05): LinkedIn posts and every Serper source are disabled. Uncomment to bring them back
            // (and drop their keys from RETIRED below).
            // Spec(LinkedInPosts.KEY, 60, lanes = setOf(Lane.LINKEDIN, Lane.SERPER)) { current -> LinkedInPosts(dailyCap = current.serperDailyCap, grouped = current.postsGrouped) },
            // Glassdoor through Google: daily, eight paid searches (ten with Saudi/UAE) under the same Serper cap.
            // Spec(Glassdoor.KEY, 60, lanes = setOf(Lane.SERPER)) { current -> Glassdoor(dailyCap = current.serperDailyCap, gulf = current.boardsGulf) },
            Spec(Recruitera.KEY, Recruitera.EVERY_MS / 60_000) { current -> Recruitera(gulf = current.boardsGulf) },
            // Named companies' career boards, twice a day.
            Spec(Ats.KEY, Ats.EVERY_MS / 60_000) { current -> Ats(gulf = current.boardsGulf) },
            // AI data labelling and assessment (audio, images, Arabic text), every 3 hours.
            // OFF (2026-11-05): AiData stays off; uncomment (and drop it from RETIRED) to bring it back.
            // Spec(AiData.KEY, SLOW) { _ -> AiData() },
            // Worldwide remote sources (back on): remote-from-anywhere or visa jobs that fit the CV.
            // US listings, hourly: remote-from-anywhere or visa-sponsored jobs that fit the CV.
            Spec(Jobright.KEY, 60, home = Region.WORLDWIDE) { _ -> Jobright() },
            // Europe and Japan, every 3 hours: relocation jobs with the visa paid, that fit the CV.
            Spec(RelocateMe.KEY, SLOW, home = Region.WORLDWIDE) { _ -> RelocateMe() },
            // Free remote-job feeds (Himalayas, Jobicy, Remotive, We Work Remotely, RemoteOK, Working Nomads, Arbeitnow, Remote Rocketship).
            Spec(RemoteFeeds.KEY, SLOW, home = Region.WORLDWIDE) { current -> RemoteFeeds(off = current.feedsOff) },
            // Jobgether's public search API: remote jobs open to Egypt, every 3 hours.
            Spec(Jobgether.KEY, SLOW, home = Region.WORLDWIDE) { _ -> Jobgether() },
            // Hacker News "Who is hiring": the monthly thread, remote or visa ads.
            Spec(HnHiring.KEY, SLOW * 8, home = Region.WORLDWIDE) { _ -> HnHiring() },
            // Greenhouse, Lever and Ashby boards of companies in Europe and the UK known to sponsor visas.
            Spec(SponsorBoards.KEY, SLOW, home = Region.WORLDWIDE) { _ -> SponsorBoards() },
            // Adzuna, Jooble, Careerjet: each only once its key is in .env.
            Spec(JobApis.KEY, 12 * 60, home = Region.WORLDWIDE, needs = { JobApis.Keys.fromBuild().any }) { _ -> JobApis() },
            // Arabic freelance boards, every 30 minutes: gigs that name the CV's stack, in their own section.
            Spec(Mostaql.KEY, Mostaql.EVERY_MS / 60_000, home = Region.FREELANCE) { _ -> Mostaql() },
            Spec(Khamsat.KEY, Khamsat.EVERY_MS / 60_000, home = Region.FREELANCE) { _ -> Khamsat() },
            // LinkedIn "Worldwide", remote, hourly: jobs open to anyone or to EMEA/Egypt, on its own request budget.
            // Back on: its own hourly request budget (LinkedInRest.takeWorldwide), so it never spends Egypt's share.
            Spec(LinkedInWorldwide.KEY, 60, home = Region.WORLDWIDE, lanes = setOf(Lane.LINKEDIN)) { _ -> LinkedInWorldwide() },
            // LinkedIn jobs in Saudi Arabia and the UAE (Criteria GULF_SEARCH_PLACES), every 3 hours over the last day: posted to Telegram under #Gulf.
            Spec(LinkedInJobs.GULF_KEY, SLOW, home = Region.GULF, lanes = setOf(Lane.LINKEDIN)) { _ -> LinkedInJobs.gulf() },
            // Saudi and UAE hiring posts: emailed, never posted.
            // OFF (2026-11-05): Serper source, disabled with LinkedIn posts. Gulf jobs come from the boards above (boardsGulf).
            // Spec(LinkedInPosts.GULF_KEY, 60, home = Region.GULF, lanes = setOf(Lane.LINKEDIN, Lane.SERPER)) { _ -> LinkedInPosts.gulf() },
        )

        /*
         * Sources no longer run, and where their jobs were: their stored rows
         * still need a region, and the Gulf one's an automatic email.
         */
        val RETIRED: Map<String, Region> = mapOf(
            AbroadJobs.KEY to Region.WORLDWIDE,
            // OFF (2026-11-05): LinkedIn posts and the Serper sources.
            LinkedInPosts.KEY to Region.EGYPT,
            Glassdoor.KEY to Region.EGYPT,
            LinkedInPosts.GULF_KEY to Region.GULF,
            // Its registry home was Egypt (Region.MOSTLY_REMOTE still places its stored rows).
            AiData.KEY to Region.EGYPT,
        )

        const val SLOW = Limits.SLOW_SOURCE_EVERY_MS / 60_000

        fun spec(key: String): Spec? = SOURCES.firstOrNull { it.key == key }

        /** Every key, live or retired, whose jobs are in [region]. */
        fun keysIn(region: Region): Set<String> =
            (SOURCES.filter { it.home == region }.map { it.key } + RETIRED.filterValues { it == region }.keys).toSet()

        /** Every live source that shares [lane]. */
        fun keysOn(lane: Lane): Set<String> = SOURCES.filter { lane in it.lanes }.map { it.key }.toSet()

        fun seededKey(key: String) = "$key.seeded"

        /** Seed only a source's first run: nothing stored and never finished a run before. */
        fun shouldSeed(seededFlag: String?, empty: Boolean): Boolean = seededFlag == null && empty
    }

    private fun runSource(source: Source, seeding: Boolean, deadline: Long): SourceStat {
        val stat = SourceStat(source.key)

        for (client in source.clients) {
            client.deadline = deadline
            client.isStopped = isStopped
            client.run = run
        }

        // Things a person has to act on — out of credits, a cap reached — as opposed to a bad run.
        val alerts = mutableListOf<Alert>()

        // A count from an earlier run of a kept instance must not make this one look healthy.
        source.listed?.reset()

        val items = try {
            source.fetch(settings.keywords, store)
        } catch (error: BlockedException) {
            stat.block(error.message)
            error.alert?.let { alerts += Alert(it, error.message ?: it) }
            error.partialItems
        } catch (error: Exception) {
            // One broken source must not abort the run.
            stat.fail("${error.javaClass.simpleName}: ${error.message}")
            emptyList()
        }

        alerts += source.takeAlerts()

        stat.parsed = items.size
        stat.listed = source.listed?.count
        checkSilence(source, stat, alerts)
        if (!seeding) alert(source.key, alerts)

        val kept = items.filter { item ->
            // What it asks for and how well it fits, before the filters: the experience gate reads the years.
            Extract.apply(item)
            val verdict = Filters.evaluate(item, settings.maxYears)
            if (!verdict.pass) {
                stat.rejected.merge(verdict.reason!!, 1, Int::plus)
                return@filter false
            }
            item.contactEmails = verdict.emails
            item.contactPhones = verdict.phones
            item.score = Score.of(item)
            true
        }
        stat.matched = kept.size

        val created = kept.mapNotNull { item ->
            runCatching { store.insertIfNew(item) }
                .onFailure { Log.error("Could not store ${item.source}/${item.externalId}: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.second }
                ?.first
        }
        stat.stored = created.size

        // What the run actually found, job by job, for the log screen.
        val createdIds = created.map { it.item.externalId }.toSet()
        for (item in kept) {
            val mark = if (item.externalId in createdIds) "+ new " else "· seen"
            Log.info("$mark ${source.key}: ${item.title}${item.company?.let { " @ $it" } ?: ""}")
        }

        // Jobs abroad: only the best fits go out; the rest stay on the Jobs screen. Then best first.
        val outgoing = (if (seeding) created else bestAbroad(created)).sortedByDescending { it.item.score ?: 0 }

        if (seeding) {
            created.forEach { store.markSkipped(it.id, "seeded: database was empty, not sent") }
            stat.seeded = created.size
        } else if (source.emailOnly) {
            // The channel is Egypt's; these only go out as CV emails.
            outgoing.forEach { store.markSkipped(it.id, "outside Egypt: emailed, not posted") }
        } else if (telegram == null) {
            outgoing.forEach { store.markSkipped(it.id, "Telegram is not configured") }
        } else {
            // Digest mode: the weaker matches wait for the next bundle (HousekeepingWorker).
            val (now, later) = Digest.split(outgoing, settings.digest, settings.digestThreshold)
            later.forEach { store.markDigest(it.id) }
            stat.digested = later.size
            stat.delivered = deliver(now.take(Limits.MAX_ITEMS_PER_RUN))
        }

        // Apply buttons, for jobs genuinely new: never for a seeding run's backlog.
        if (!seeding && !source.emailOnly) {
            runCatching { applier?.handle(outgoing) }
                .onFailure { Log.error("Apply notifications failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        // Saudi/UAE and abroad (jobright, Relocate.me) jobs with an address: the CV goes out now, with no limit on how many.
        if (!seeding && source.autoEmail) {
            runCatching { applier?.autoEmail(outgoing, isStopped, run) }
                .onFailure { Log.error("Auto-email failed: ${it.javaClass.simpleName}: ${it.message}") }
        }

        Log.info(stat.toString() + if (stat.rejected.isEmpty()) "" else " | rejected ${stat.rejected}")
        runCatching { store.runs.record(RunHistory.of(stat)) }
            .onFailure { Log.error("Could not record the run: ${it.message}") }
        return stat
    }

    /*
     * A source that answers but parses nothing, run after run, while it used
     * to find jobs: the site has likely changed. The page is kept on any
     * clean empty run, so the first broken one is there to fix the parser
     * with; the alert waits for [Breakage.STREAK] in a row. A run that
     * listed jobs but had none new (all seen, opened or too old) is not
     * empty ([Breakage.silent]): no page kept, and the streak starts over.
     */
    private fun checkSilence(source: Source, stat: SourceStat, alerts: MutableList<Alert>) {
        runCatching {
            val since = java.time.Instant.now().minusSeconds(Breakage.LOOKBACK_DAYS * 86_400)
            val history = store.runs.since(since, source.key)
            val key = "${source.key}.zeroStreak"
            val previous = store.getState(key)?.toIntOrNull() ?: 0
            val next = Breakage.nextStreak(
                previous, stat.parsed, stat.blocked, stat.error, Breakage.yieldsHistorically(history), stat.listed,
            ) ?: return
            store.setState(key, next.toString())

            if (Breakage.silent(stat.parsed, stat.listed, stat.blocked, stat.error) && snapshotDir != null) {
                source.clients.firstNotNullOfOrNull { it.lastBody?.takeIf(String::isNotBlank) }?.let { body ->
                    val file = Snapshots.save(snapshotDir, source.key, body)
                    Log.warn("Parsed nothing from a good answer; page saved as ${file.name}")
                }
            }
            if (next >= Breakage.STREAK) {
                val lastYield = history.lastOrNull { it.parsed > 0 }?.ranAt
                alerts += Alert("zero-${source.key}", Breakage.message(source.key, next, lastYield))
            }
        }.onFailure { Log.error("Silence check failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Tells the channel about something only a person can fix, once a day
     * per kind: a source stopped by its cap stays stopped all day, and every
     * run repeating that would bury the jobs. The day is recorded only once
     * the message is through, so a failed send is tried on the next run.
     */
    private fun alert(sourceKey: String, alerts: List<Alert>) {
        for (alert in alerts) {
            Log.warn("Alert ($sourceKey): ${alert.message}")
            val day = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Cairo")).toString()
            val key = "alert.${alert.kind}.day"
            if (store.getState(key) == day) continue
            val client = telegram
            if (client == null) {
                // No channel to tell: the phone's own notification, still once a day.
                if (alert.kind.startsWith("zero-")) {
                    onAlert(alert.kind, alert.message)
                    store.setState(key, day)
                }
                continue
            }

            runCatching { client.send("⚠️ <b>Crawler alert</b> (${escape(sourceKey)})\n${escape(alert.message)}") }
                .onSuccess {
                    store.setState(key, day)
                    if (alert.kind.startsWith("zero-")) onAlert(alert.kind, alert.message)
                }
                .onFailure { Log.error("Alert could not be sent: ${it.message}") }
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Egypt's jobs as they are; of the jobs abroad, only [AbroadFit.best],
     * best first. The others are kept, marked with their score, so the Jobs
     * screen still lists them.
     */
    private fun bestAbroad(rows: List<Row>): List<Row> {
        val (abroad, home) = rows.partition { Mailer.isAbroad(it.item) }
        if (abroad.isEmpty()) return rows
        val chosen = AbroadFit.best(abroad.map { it.item }).mapNotNull { item -> abroad.firstOrNull { it.item === item } }
        for (row in abroad - chosen.toSet()) {
            val score = AbroadFit.score(row.item)
            store.markSkipped(row.id, "lower fit: $score/100, below ${AbroadFit.MIN_SCORE} or past the best ${AbroadFit.MAX_PER_RUN}")
            Log.info("· lower fit $score/100, not sent: ${row.item.title}${row.item.company?.let { " @ $it" } ?: ""}")
        }
        if (abroad.size > chosen.size) Log.info("Abroad: sending the best ${chosen.size} of ${abroad.size} new jobs by CV fit")
        return home + chosen
    }

    private fun deliver(rows: List<Row>): Int {
        var delivered = 0
        for (row in rows) {
            if (isStopped() || run.lost) break
            // One pace across every worker, to stay under Telegram's per-channel limit.
            if (!SendPace.await(isStopped)) break
            // Mid-handover, wait for the new network rather than burn a send attempt.
            if (!Net.awaitOnline(isStopped = isStopped)) {
                // Unsent rows stay pending; housekeeping's retry sends them.
                if (!isStopped()) run.lost = true
                break
            }
            if (deliverOne(row.id)) delivered += 1
        }
        return delivered
    }

    /** The only place a row is marked sent, so an outage delays but never loses a posting. */
    private fun deliverOne(id: Long): Boolean {
        val row = store.findById(id) ?: return false
        if (row.status == Status.SENT) return false
        val client = telegram ?: return false

        return try {
            val to = Topics.forRegion(row.region)
            client.send(Formatter.format(row.item), to)
            store.markSent(row.id)
            Log.info("→ sent: ${row.item.title}" + (to.threadId?.let { " (topic $it)" } ?: ""))
            true
        } catch (error: TelegramRateLimit) {
            store.recordAttempt(row.id, error.message ?: "rate limited")
            Thread.sleep(minOf(error.retryAfterSeconds, 60) * 1000)
            false
        } catch (error: TelegramPermanent) {
            store.markFailed(row.id, error.message ?: "rejected", permanent = true)
            Log.error("Telegram rejected the message permanently: ${error.message}")
            false
        } catch (error: Exception) {
            store.markFailed(row.id, error.message ?: error.javaClass.simpleName)
            Log.error("Telegram delivery failed: ${error.message}")
            false
        }
    }
}
