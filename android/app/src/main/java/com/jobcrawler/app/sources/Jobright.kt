package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * jobright.ai, read through its public category pages (robots.txt allows
 * everything under /jobs/ and /remote-jobs/). Its personal feed, /jobs/recommend, needs a
 * login and is not used.
 *
 * jobright lists US jobs only, so a job is kept for one of two reasons, its
 * lane:
 *  - remote work open outside the US: the text names the world, EMEA,
 *    Europe's or Egypt's time zones;
 *  - a visa: jobright tags the job "H1B Sponsor Likely" / "H1B Sponsored",
 *    or the text offers sponsorship or relocation.
 * Neither, and the job is dropped: a US-only remote job cannot hire from
 * Cairo. The lane becomes the item's market, so the Egypt location rule
 * (which reads only broad markets) leaves these alone.
 *
 * On top of the lane, a job has to name the CV's stack (PHP, Laravel, NestJS,
 * Node.js, Go, TypeScript) and fit 1-3 years: no more than [MAX_YEARS]
 * required, no senior-only level, no internships or part-time. The shared
 * Filters still run afterwards (title, ineligible phrases, seniority words).
 *
 * The pages ship their jobs as JSON in __NEXT_DATA__, about 16-20 newest
 * first, and take no filter parameters, so the source reads many short
 * pages instead of paging deep: every location jobright has a page for —
 * the whole country plus its 11 city pages — for each category. The
 * nationwide pages are read every run; the city pages are walked in turn,
 * starting where the last run stopped, so a run short of time loses none. jobright rewrites each posting into a
 * summary; an email address survives only rarely, and when it does the
 * pipeline's auto-email sends the CV to it.
 */
class Jobright(
    override val client: GuestClient = GuestClient("Jobright"),
    private val cadence: Cadence = Cadence(KEY, EVERY_MS),
) : Source {
    override val key = KEY

    /** Posted like any other match, and a job that gives an address is emailed the CV too. */
    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val kept = LinkedHashMap<String, Parsed>()
        val rejected = sortedMapOf<String, Int>()
        var parsed = 0

        try {
            fun read(page: String) {
                for (job in parse(client.get(page))) {
                    parsed += 1
                    val reason = job.rejected
                        ?: if (Dates.isOlderThan(job.item.postedAt, MAX_AGE_DAYS)) "too-old" else null
                    if (reason != null) rejected.merge(reason, 1, Int::plus)
                    else kept.putIfAbsent(job.item.externalId, job)
                }
            }
            PAGES.forEach(::read)
            Rotation(store, "$KEY.cityPage").walk(CITY_PAGES, ::read)
        } catch (error: BlockedException) {
            error.partialItems = ranked(kept.values)
            throw error
        } finally {
            Log.info("jobright: $parsed listed, ${kept.size} fit a lane and the CV | dropped $rejected")
        }

        return ranked(kept.values)
    }

    /** One listed job, and why it was dropped, if it was. */
    data class Parsed(val item: Item, val rejected: String?, val lane: Lane?, val score: Int)

    enum class Lane(val label: String) {
        REMOTE("🌍 Remote, open outside the US"),
        VISA("✈️ Visa sponsorship (H1B)"),
    }

    companion object {
        const val KEY = "jobright"
        private const val BASE = "https://jobright.ai"

        /*
         * Category pages that exist (from sitemap-taxonomy): a guessed slug
         * such as "php-developer" or "node-js-developer" silently falls back
         * to an unrelated category (Salesforce Developer), so only these.
         */
        val CATEGORIES = listOf("backend-engineer", "full-stack-engineer", "golang-engineer")

        /* Every place jobright has a page for: its 11 cities (sitemap-taxonomy), each within 50 miles plus remote. */
        val CITIES = listOf(
            "new-york-ny", "san-francisco-ca", "los-angeles-ca", "seattle-wa", "austin-tx", "boston-ma",
            "chicago-il", "dallas-tx", "houston-tx", "atlanta-ga", "phoenix-az",
        )

        /** Read every run: each category across the whole country, and the remote board. */
        val PAGES = CATEGORIES.map { "$BASE/jobs/$it" } + "$BASE/remote-jobs/software-engineering"

        /** Walked in turn across runs. */
        val CITY_PAGES = CITIES.flatMap { city -> CATEGORIES.map { "$BASE/jobs/$it-in-$city" } }

        private const val EVERY_MS = 60 * 60_000L
        private const val MAX_AGE_DAYS = 1

        const val MAX_YEARS = CvStack.MAX_YEARS

        // jobright's own flags, as recommendation tags.
        private val SPONSOR_TAGS = setOf("h1b sponsor likely", "h1b sponsored")
        private const val NO_SPONSOR_TAG = "no h1b"
        private val BLOCKING_TAGS = setOf("u.s. citizen only", "us citizen only", "security clearance required")

        /* Remote work a person in Cairo can take: the text has to say so. */
        val WORLDWIDE = listOf(
            "worldwide", "anywhere in the world", "work from anywhere", "remote anywhere", "fully distributed",
            "globally distributed", "global team", "global remote", "remote global", "international candidates",
            "outside the us", "outside the u.s.", "outside of the us", "any country", "any time zone", "any timezone",
            "all time zones", "emea", "mena", "middle east", "africa", "egypt", "europe", "european time",
            "european hours", "cet", "cest", "eet", "gmt+2", "gmt+3", "utc+2", "utc+3", "gulf hours",
        )

        /* A remote job only for people already in the US, on top of Filters.ineligible. */
        val US_ONLY = listOf(
            "us-based", "u.s.-based", "us based", "based in the us", "based in the u.s.", "based in the united states",
            "within the us", "within the u.s.", "within the united states", "us only", "u.s. only", "us residents",
            "u.s. residents", "w2", "w-2", "us time zones", "us hours",
        )

        val VISA_TERMS = listOf(
            "visa sponsorship available", "visa sponsorship is available", "will sponsor", "we sponsor",
            "sponsorship available", "offer visa sponsorship", "provide visa sponsorship", "h1b sponsorship",
            "h-1b sponsorship", "relocation assistance", "relocation support", "relocation package", "relocation provided",
        )

        private val ACCEPTED_LEVELS = Regex("entry|junior|mid|new grad|associate|intermediate", RegexOption.IGNORE_CASE)
        private val SENIOR_LEVELS = Regex("senior|lead|staff|principal|director|executive|manager", RegexOption.IGNORE_CASE)
        private val INTERN = Regex("""\bintern(ship)?s?\b""", RegexOption.IGNORE_CASE)

        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>""")

        /** Every job on a page, kept or not, in page order. A page without its data is no jobs. */
        fun parse(page: String): List<Parsed> {
            val json = NEXT_DATA.find(page)?.groupValues?.get(1) ?: return emptyList()
            val props = runCatching { JSONObject(json).getJSONObject("props").getJSONObject("pageProps") }.getOrNull()
                ?: return emptyList()
            // A category page calls it jobList, the remote page defaultData.
            val list = props.optJSONArray("jobList") ?: props.optJSONArray("defaultData") ?: return emptyList()

            return (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.let(::job) }
        }

        private fun job(entry: JSONObject): Parsed? {
            val job = entry.optJSONObject("jobResult") ?: return null
            val id = Html.clean(job.optString("jobId")) ?: return null
            val title = Html.clean(job.optString("jobTitle")) ?: return null

            val tags = strings(job.optJSONArray("recommendationTags")).map { it.lowercase() }
            val summary = Html.clean(job.optString("jobSummary"))
            val duties = strings(job.optJSONArray("coreResponsibilities"))
            val needs = strings(job.optJSONArray("requirements"))
            val body = listOfNotNull(summary).plus(duties).plus(needs).joinToString("\n")
            val text = Filters.fold("$title\n$body")

            val remote = job.optBoolean("isRemote", false)
            val seniority = Html.clean(job.optString("jobSeniority"))
            val type = Html.clean(job.optString("employmentType"))
            val years = if (job.has("minYearsOfExperience") && !job.isNull("minYearsOfExperience")) job.optInt("minYearsOfExperience") else null

            val lane = laneOf(tags, text, remote)
            val stack = CvStack.of("$title\n$body")
            val score = CvStack.score("$title\n$body")

            val rejected = when {
                tags.any { it in BLOCKING_TAGS } -> "citizen-or-clearance"
                lane == null -> "no-lane"
                stack.isEmpty() -> "stack"
                INTERN.containsMatchIn(title) || type?.contains("intern", ignoreCase = true) == true -> "internship"
                type?.contains("part", ignoreCase = true) == true -> "part-time"
                years != null && years > MAX_YEARS -> "years"
                seniority != null && !ACCEPTED_LEVELS.containsMatchIn(seniority) && SENIOR_LEVELS.containsMatchIn(seniority) -> "level"
                // Another language, or only for people already there.
                else -> AbroadFit.blocked("$title\n$body")
            }

            val location = strings(job.optJSONArray("jobLocations")).joinToString(" / ").ifEmpty { null }
                ?: Html.clean(job.optString("jobLocation"))
            val company = Html.clean(entry.optJSONObject("companyResult")?.optString("companyName"))
            val salary = Html.clean(job.optString("salaryDesc"))

            val excerpt = buildList {
                lane?.let { add(it.label) }
                salary?.let { add("💰 $it") }
                years?.let { add("Needs $it+ years") }
                summary?.let(::add)
                if (needs.isNotEmpty()) add("Requirements:\n" + needs.joinToString("\n") { "• $it" })
            }.joinToString("\n").take(4000)

            val item = Item(
                source = KEY,
                externalId = id,
                title = title,
                url = "$BASE/jobs/info/$id",
                company = company,
                location = location,
                market = lane?.label,
                postedAt = posted(job.optString("publishTime")),
                keyword = stack.joinToString(", ").ifEmpty { null },
                excerpt = excerpt.ifEmpty { null },
                seniority = seniority,
                employmentType = type,
                workplace = Html.clean(job.optString("workModel")),
                remote = remote,
            )
            return Parsed(item, rejected, lane, score)
        }

        /** Remote open outside the US first: it needs no visa at all. */
        fun laneOf(tags: List<String>, text: String, remote: Boolean): Lane? {
            if (remote && Filters.containsAny(text, WORLDWIDE) && !Filters.containsAny(text, US_ONLY)) return Lane.REMOTE
            if (NO_SPONSOR_TAG in tags) return null
            if (tags.any { it in SPONSOR_TAGS } || Filters.containsAny(text, VISA_TERMS)) return Lane.VISA
            return null
        }

        /** jobright prints UTC as "2026-11-01 14:01:05". */
        fun posted(value: String?): String? {
            val text = value?.trim()?.takeIf { it.length >= 19 } ?: return null
            return Dates.absoluteToIso(text.substring(0, 19).replace(' ', 'T') + "Z")
        }

        /** Most of the CV first, so a capped delivery sends the best fits. */
        fun ranked(jobs: Collection<Parsed>): List<Item> = jobs.sortedByDescending { it.score }.map { it.item }

        private fun strings(array: JSONArray?): List<String> =
            (0 until (array?.length() ?: 0)).mapNotNull { Html.clean(array!!.optString(it)) }
    }
}
