package com.jobcrawler.app

import com.jobcrawler.app.sources.AiData
import com.jobcrawler.app.sources.Ats
import com.jobcrawler.app.sources.CareerSites
import com.jobcrawler.app.sources.DrJob
import com.jobcrawler.app.sources.Glassdoor
import com.jobcrawler.app.sources.Recruitera
import com.jobcrawler.app.sources.Tanqeeb
import com.jobcrawler.app.sources.Workable
import com.jobcrawler.app.sources.Wuzzuf

/**
 * Where a job is: Egypt, the Gulf (Saudi Arabia and the UAE), or anywhere
 * else in the world — remote-from-anywhere and relocation jobs alike.
 *
 * A source has a home region (what Settings groups it under) and may reach
 * more than one: the boards bring Saudi/UAE jobs too while
 * [Settings.boardsGulf] is on. A job has exactly one, read from the job
 * itself by [of], because a multi-region source's items differ.
 */
enum class Region(val label: String, val tag: String, val flag: String) {
    EGYPT("Egypt", "#Egypt", "🇪🇬"),
    GULF("Saudi & UAE", "#Gulf", "🇸🇦"),
    WORLDWIDE("Worldwide", "#Worldwide", "🌍"),
    /** Gigs on the Arabic freelance boards (Mostaql, Khamsat), not jobs. */
    FREELANCE("Freelance", "#Freelance", "💼"),
    ;

    companion object {
        /** The regions the app shows: Egypt, the Gulf, Worldwide, and the freelance gigs. */
        val SHOWN = listOf(EGYPT, GULF, WORLDWIDE, FREELANCE)

        /*
         * Sources whose every job is abroad, or in the Gulf: from the
         * registry ([Pipeline.Spec.home]), retired keys included for their
         * stored rows. Lazy, because the registry itself names regions.
         * [MOSTLY_REMOTE] is deliberately left out: ai_data is filed under
         * Worldwide for Settings, but not every job of it is abroad, so its
         * registry home stays Egypt and it is emailed as for Egypt.
         */
        private val WORLDWIDE_SOURCES by lazy { Pipeline.keysIn(WORLDWIDE) }
        private val GULF_SOURCES by lazy { Pipeline.keysIn(GULF) }
        private val FREELANCE_SOURCES by lazy { Pipeline.keysIn(FREELANCE) }

        /**
         * Filed under Worldwide, but not every job is abroad: AI data work is
         * mostly remote ("Anywhere"), yet it also searches Egypt, so each job
         * is placed by where it is, and the CV is emailed as for Egypt.
         */
        private val MOSTLY_REMOTE = setOf(AiData.KEY)

        /** Egypt's sources that also carry Saudi/UAE jobs when [Settings.boardsGulf] is on. */
        private val GULF_CAPABLE = setOf(
            Wuzzuf.KEY, Workable.KEY, Tanqeeb.KEY, Recruitera.KEY, Ats.KEY, Glassdoor.KEY, CareerSites.KEY, DrJob.KEY,
        )

        /** The region Settings files a source under. */
        fun home(sourceKey: String): Region = when (sourceKey) {
            in MOSTLY_REMOTE -> WORLDWIDE
            else -> Pipeline.spec(sourceKey)?.home ?: Pipeline.RETIRED[sourceKey] ?: EGYPT
        }

        /** Every region a source can bring jobs from, given the settings. */
        fun reach(sourceKey: String, gulf: Boolean): Set<Region> {
            if (sourceKey in MOSTLY_REMOTE) return setOf(WORLDWIDE, EGYPT)
            val home = home(sourceKey)
            return if (home == EGYPT && gulf && sourceKey in GULF_CAPABLE) setOf(EGYPT, GULF) else setOf(home)
        }

        /** A stored value back to a region; null or unknown reads as null. */
        fun parse(value: String?): Region? = entries.firstOrNull { it.name == value }

        /**
         * The region of one job. The Gulf wins over the source's lane: a
         * Saudi listing on a worldwide board is still a Gulf job, and the
         * Gulf is where the CV is emailed differently.
         */
        fun of(item: Item): Region {
            // A gig is a gig wherever its client is: a Mostaql project naming Riyadh stays freelance.
            if (item.source in FREELANCE_SOURCES) return FREELANCE
            if (item.source in GULF_SOURCES || Gulf.isGulf(item.market)) return GULF
            val abroad = item.source in WORLDWIDE_SOURCES ||
                (item.source == Glassdoor.KEY && item.market != null && !Gulf.isGulf(item.market))
            // An Egypt source that names a Gulf place without setting the market (company boards).
            if (Gulf.countryOf(item.location) != null && !isEgypt(item.location)) return GULF
            if (abroad) return WORLDWIDE
            if (item.source in MOSTLY_REMOTE && !isEgypt(item.location)) return WORLDWIDE
            val market = item.market.orEmpty()
            if (market.startsWith("🌍") || market.startsWith("✈️")) return WORLDWIDE
            return EGYPT
        }

        private val egyptRe = Regex("""\b(egypt|cairo|giza|alexandria)\b|مصر|القاهرة""", RegexOption.IGNORE_CASE)

        private fun isEgypt(place: String?) = place != null && egyptRe.containsMatchIn(place)
    }
}
