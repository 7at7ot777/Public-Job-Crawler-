package com.jobcrawler.app

import android.content.Context
import com.jobcrawler.app.sources.SerperBudget

/**
 * Defaults mirror src/config.js as of the Egypt-only scope: Egypt jobs, the
 * backend stack, junior to mid. Only what a person would change from the phone
 * — the bot, the keywords, the sources, the interval — is editable; the filter
 * lists stay here, where the Node app keeps them too.
 */
object Defaults {
    val keywords = listOf(
        "Laravel Developer",
        "Node.js Developer",
        "Backend Developer",
        "PHP Developer",
        "Full Stack Developer",
        "Application Support",
        "Application Developer",
        "Golang Developer",
        "Go Developer",
    )

    /* What the first release searched, for installs that saved keywords before the rest existed. */
    val firstKeywords = keywords - setOf("Golang Developer", "Go Developer")

    // "Cairo" alone also matches Cairo, Illinois. The districts and Obour give
    // LinkedIn's denser local result sets; one is searched per run (LinkedInJobs).
    val locations = listOf(
        "Egypt",
        "Cairo, Egypt",
        "Giza, Egypt",
        "Maadi, Cairo, Egypt",
        "Heliopolis, Cairo, Egypt",
        "Obour City, Egypt",
        "Alexandria, Egypt",
        "Sheraton, Cairo, Egypt",
        "Mohandessin, Giza, Egypt",
        "Dokki, Giza, Egypt",
    )

    /* Saudi Arabia and the UAE, searched on LinkedIn by gulf_jobs and posted to Telegram under #Gulf. */
    val gulfLocations = listOf("Saudi Arabia", "United Arab Emirates")

    // Every source is on: Worldwide and Gulf search (LinkedIn's included), the posts and paid Serper
    // sources (under the Serper daily cap) and AI data labelling.
    val sources = listOf(
        "linkedin_jobs", "wuzzuf", "tanqeeb", "workable", "careers", "recruitera", "ats", "drjob",
        "jobright", "relocateme", "remote_feeds", "jobgether", "hn_hiring", "sponsor_boards", "job_apis",
        // LinkedIn worldwide (remote, open to anyone or EMEA/Egypt) and LinkedIn Saudi & UAE.
        "linkedin_worldwide", "gulf_jobs",
        // Hiring posts and Glassdoor through Google (paid, capped), Saudi & UAE posts, AI data labelling.
        "linkedin_posts", "glassdoor", "gulf_posts", "ai_data",
        // Arabic freelance gigs (Region.FREELANCE).
        "mostaql", "khamsat",
    )

    /*
     * LinkedIn's job search runs on Egypt only: the Europe/Canada/Australia and
     * Saudi/UAE LinkedIn searches shared its hourly request cap and starved it.
     * Dropped from every saved install too, not just new ones.
     */
    val retiredSources: Set<String> get() = Pipeline.RETIRED.keys

    /* What the first release knew, for installs that saved settings before the rest existed. */
    val firstSources = setOf("linkedin_jobs", "wuzzuf", "tanqeeb", "workable")

    const val INTERVAL_MINUTES = 15L
}

/**
 * What the settings screen stores. The bot and the channel are not among
 * them: they are built in from the repo's .env, so nothing is typed on the phone.
 */
data class Settings(
    val keywords: List<String>,
    val sources: Set<String>,
    val intervalMinutes: Long,
    /** Careers sites added on the phone; saved by [UserSites], never by [save]. */
    val careerSites: List<SavedSite> = emptyList(),
    /** Paid Serper searches a day; saved by [SerperPrefs], never by [save]. 0 = no cap. */
    val serperDailyCap: Int = SerperBudget.DEFAULT_DAILY_CAP,
    /** Hiring-post places searched in OR groups rather than one by one; saved by [SerperPrefs]. */
    val postsGrouped: Boolean = false,
    /** Workable, the company boards, Recruitera, Wuzzuf, Tanqeeb and Glassdoor also search Saudi Arabia and the UAE. */
    val boardsGulf: Boolean = true,
    /** The most years of experience to apply with; a job asking more is dropped. 0 = no limit. Saved by [TuningPrefs]. */
    val maxYears: Int = 0,
    /** Weaker matches wait for one bundled message every few hours; saved by [TuningPrefs]. */
    val digest: Boolean = false,
    /** In digest mode, a job scoring at least this goes out on its own. */
    val digestThreshold: Int = TuningPrefs.DEFAULT_DIGEST_THRESHOLD,
    /** A source's own schedule, in minutes, where the person changed it; saved by [TuningPrefs]. */
    val sourceIntervals: Map<String, Long> = emptyMap(),
    /** Remote job feeds switched off one by one; saved by [FeedPrefs], never by [save]. */
    val feedsOff: Set<String> = emptySet(),
) {
    val botToken: String get() = BuildConfig.TELEGRAM_BOT_TOKEN
    val chatId: String get() = BuildConfig.TELEGRAM_CHAT_ID
    val telegramReady get() = botToken.isNotBlank() && chatId.isNotBlank()

    companion object {
        private const val PREFS = "settings"

        fun load(context: Context): Settings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            // A source added by an update starts on; one switched off stays off.
            val known = prefs.getStringSet("knownSources", null) ?: Defaults.firstSources
            val saved = prefs.getStringSet("sources", null)
            val sources = if (saved == null) Defaults.sources.toSet() else saved + (Defaults.sources - known) - Defaults.retiredSources

            // Likewise a keyword added by an update joins the saved list once; one deleted stays deleted.
            val knownKeywords = prefs.getStringSet("knownKeywords", null) ?: Defaults.firstKeywords.toSet()
            val savedKeywords = splitList(prefs.getString("keywords", null))
            val keywords = savedKeywords?.let { saved ->
                saved + Defaults.keywords.filter { it !in knownKeywords && saved.none { s -> s.equals(it, ignoreCase = true) } }
            }

            return Settings(
                keywords = keywords ?: Defaults.keywords,
                sources = sources,
                intervalMinutes = prefs.getLong("intervalMinutes", Defaults.INTERVAL_MINUTES),
                careerSites = UserSites.load(context),
                serperDailyCap = SerperPrefs.dailyCap(context),
                postsGrouped = SerperPrefs.grouped(context),
                boardsGulf = prefs.getBoolean("boardsGulf", true),
                maxYears = TuningPrefs.maxYears(context),
                digest = TuningPrefs.digest(context),
                digestThreshold = TuningPrefs.digestThreshold(context),
                sourceIntervals = TuningPrefs.sourceIntervals(context),
                feedsOff = FeedPrefs.off(context),
            )
        }

        fun save(context: Context, settings: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("keywords", settings.keywords.joinToString(", "))
                .putStringSet("knownKeywords", Defaults.keywords.toSet())
                .putStringSet("sources", settings.sources)
                .putStringSet("knownSources", Defaults.sources.toSet())
                .putLong("intervalMinutes", settings.intervalMinutes)
                .putBoolean("boardsGulf", settings.boardsGulf)
                .apply()
        }

        fun splitList(value: String?): List<String>? {
            if (value.isNullOrBlank()) return null
            return value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
        }
    }
}

/**
 * The hiring-posts search's cost settings, kept apart from [Settings.save] so
 * saving the rest of the screen never resets them.
 */
object SerperPrefs {
    private const val PREFS = "serper"

    fun dailyCap(context: Context): Int =
        prefs(context).getInt("dailyCap", SerperBudget.DEFAULT_DAILY_CAP)

    fun grouped(context: Context): Boolean = prefs(context).getBoolean("grouped", false)

    fun setDailyCap(context: Context, cap: Int) = prefs(context).edit().putInt("dailyCap", cap.coerceAtLeast(0)).apply()

    fun setGrouped(context: Context, on: Boolean) = prefs(context).edit().putBoolean("grouped", on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The experience cap, digest mode and per-source schedules, kept apart from
 * [Settings.save] like [SerperPrefs]: each control saves its own value.
 */
object TuningPrefs {
    private const val PREFS = "tuning"
    const val DEFAULT_DIGEST_THRESHOLD = 70

    fun maxYears(context: Context): Int = prefs(context).getInt("maxYears", 0)
    fun setMaxYears(context: Context, years: Int) = prefs(context).edit().putInt("maxYears", years.coerceIn(0, 30)).apply()

    fun digest(context: Context): Boolean = prefs(context).getBoolean("digest", false)
    fun setDigest(context: Context, on: Boolean) = prefs(context).edit().putBoolean("digest", on).apply()

    fun digestThreshold(context: Context): Int = prefs(context).getInt("digestThreshold", DEFAULT_DIGEST_THRESHOLD)
    fun setDigestThreshold(context: Context, value: Int) =
        prefs(context).edit().putInt("digestThreshold", value.coerceIn(0, 100)).apply()

    fun sourceIntervals(context: Context): Map<String, Long> =
        prefs(context).all.filterKeys { it.startsWith("every.") }
            .mapNotNull { (key, value) -> (value as? Long)?.let { key.removePrefix("every.") to it } }
            .toMap()

    /** null puts the source back on its default schedule. */
    fun setSourceInterval(context: Context, source: String, minutes: Long?) {
        prefs(context).edit().apply {
            if (minutes == null) remove("every.$source") else putLong("every.$source", minutes.coerceAtLeast(15))
        }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Which of the remote job feeds (Himalayas, Jobicy, Remotive, We Work
 * Remotely, RemoteOK) are switched off, kept apart from [Settings.save] like
 * [SerperPrefs]. Every feed is on until switched off, so one added by an
 * update starts on.
 */
object FeedPrefs {
    private const val PREFS = "feeds"

    fun off(context: Context): Set<String> = prefs(context).getStringSet("off", null)?.toSet() ?: emptySet()

    fun setOn(context: Context, id: String, on: Boolean) {
        val next = if (on) off(context) - id else off(context) + id
        prefs(context).edit().putStringSet("off", next).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Pacing and limits, as the Node app's config.http and config.limits. */
object Limits {
    const val TIMEOUT_MS = 15_000L
    const val DELAY_MS = 2_500L
    const val MAX_RETRIES = 3
    const val BACKOFF_BASE_MS = 4_000L
    const val MAX_DELAY_MS = 60_000L
    const val THROTTLE_BUDGET = 5

    /*
     * How long a request, or a run's start, waits for the network to come
     * back: a Wi-Fi ↔ mobile switch takes seconds, not this. A failure during
     * the gap is retried free, without spending one of MAX_RETRIES.
     */
    const val HANDOVER_WAIT_MS = 45_000L

    const val MAX_ITEMS_PER_RUN = 100
    const val RETRY_AFTER_MINUTES = 30L
    const val MAX_SEND_ATTEMPTS = 5
    const val SEND_DELAY_MS = 4_000L

    /*
     * WorkManager stops a worker after ten minutes; this leaves one for
     * delivery. Each source gets an even share of what is left when it
     * starts, so time a quick source does not use goes to the ones after it.
     * A source that runs out stops the way a block stops it: what it parsed
     * is kept, and the rest waits for the next run.
     */
    const val RUN_BUDGET_MS = 540_000L

    /*
     * Recruitera and the company boards walk dozens of sites, and their jobs
     * stay open for weeks: every three hours finds the same jobs as every
     * thirty minutes, for a fraction of the requests, time and battery.
     */
    const val SLOW_SOURCE_EVERY_MS = 3 * 3_600_000L

    /** How early a source's own schedule may fire and still count as due (see Cadence). */
    const val CADENCE_SLACK_MS = 10 * 60_000L

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}
