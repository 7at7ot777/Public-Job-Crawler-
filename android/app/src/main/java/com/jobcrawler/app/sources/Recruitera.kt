package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant

/**
 * Recruitera, the ATS behind the career pages of Paymob, Fawry, Banque Misr
 * and some eighty other employers — most of them Egyptian. Ported from
 * src/sources/recruitera.js.
 *
 * Read through the public JSON API its own careers pages call, found from the
 * sitemap rather than from a list of companies:
 *
 *   sitemap.xml                         every company career site
 *   /{site}/opportunities/filters       which job categories it has open
 *   /{site}/opportunities?category=…    the jobs in the tech ones, by cursor
 *   /{site}/jobs/{slug}                 the country, the date and the text
 *
 * Eighty sites do not fit one run's slice on a phone, so the sites are walked
 * in [Rotation]: each run carries on where the last one stopped, and a site's
 * new jobs are opened as soon as it is listed rather than after every site.
 *
 * Every [EVERY_MS] (six hours). A run the network cuts short — the phone
 * offline, or [OUTAGE_AFTER] requests in a row with no answer at all — does
 * not count as that run: its turn is handed back, the rotation goes back to
 * the first site that went unanswered, and [RunContext.retrySoon] asks the
 * worker for a retry instead of waiting out the six hours.
 */
class Recruitera(
    // The API allows 60 requests a minute.
    override val client: GuestClient = GuestClient("Recruitera", headers = mapOf("Accept" to "application/json"), delayMs = 1_100),
    /** Keep Saudi and UAE jobs too, not only Egypt's. */
    private val gulf: Boolean = false,
) : Source {
    private val wanted = countries(gulf)
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    private val cadence = Cadence(KEY, EVERY_MS)

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        val previousRun = cadence.last(store)
        cadence.started(store)
        val items = mutableListOf<Item>()
        val checked = store.checked.ids(KEY).toMutableSet()
        var opened = 0
        var sites = emptyList<String>()
        var current: String? = null
        val streak = Unanswered()

        try {
            sites = sites()
            Log.info("Recruitera: ${sites.size} career sites")

            Rotation(store, ROTATION).walk(sites) { site ->
                if (opened >= MAX_DETAILS) return@walk
                current = site

                val listed = try {
                    techJobs(site)
                } catch (error: BlockedException) {
                    if (!error.transport) throw error
                    Log.warn("Recruitera: could not list $site: ${error.message}")
                    if (streak.failed(site)) throw outage(streak, error)
                    return@walk
                }
                streak.answered()

                var company: String? = null

                for ((job, category) in listed) {
                    if (opened >= MAX_DETAILS) break
                    val id = job.optString("id")
                    if (id in checked || store.findByExternalId(KEY, id) != null) continue

                    // Generated QA postings and drafts, and the listing's own
                    // age where it states one: settled without opening a page.
                    val days = if (job.has("days_since_posting") && !job.isNull("days_since_posting")) {
                        job.optDouble("days_since_posting")
                    } else {
                        null
                    }
                    if (isJunkTitle(job.optString("title")) || (days != null && days > MAX_AGE_DAYS)) {
                        store.checked.mark(KEY, id)
                        checked += id
                        continue
                    }

                    val slug = job.optString("slug")
                    val body = try {
                        client.get("$API_BASE/${encode(site)}/jobs/${encode(slug)}")
                    } catch (error: BlockedException) {
                        if (!error.transport) throw error
                        if (streak.failed(site)) throw outage(streak, error)
                        continue
                    }
                    streak.answered()
                    opened += 1

                    // Opened, so never opened again: a job in Dubai is still in Dubai tomorrow.
                    store.checked.mark(KEY, id)
                    checked += id

                    val detail = parseJobDetail(body)
                    if (detail == null) {
                        Log.warn("Recruitera job did not parse: ${jobUrl(site, slug)} (${client.lastResponse})")
                        continue
                    }
                    if (!detail.available || isJunkTitle(detail.title) || detail.country !in wanted) continue

                    val postedAt = detail.publishedAt
                        ?: days?.let { Instant.now().minusSeconds((it * 86_400).toLong()).toString() }
                    if (Dates.isOlderThan(postedAt, MAX_AGE_DAYS)) continue

                    if (company == null) company = companyName(site)

                    items += Item(
                        source = KEY,
                        externalId = detail.id,
                        title = detail.title,
                        url = jobUrl(site, slug),
                        company = company,
                        location = detail.location,
                        // The country came from the job itself, so the location filter has nothing to work out.
                        market = marketOf(detail.country) ?: detail.location,
                        remote = detail.workModel == "remote",
                        workplace = WORKPLACES[detail.workModel],
                        employmentType = detail.employmentType,
                        seniority = detail.experience,
                        keyword = category,
                        postedAt = postedAt,
                        excerpt = detail.excerpt,
                    )
                }
            }
        } catch (error: BlockedException) {
            // The network, not Recruitera: hand the turn back and start again
            // where the answers stopped. A block or the run's time running out
            // is a real end, and keeps its turn.
            if (error.transport || client.run.lost) {
                cadence.restore(store, previousRun)
                val resumeFrom = streak.firstSite ?: current
                val index = resumeFrom?.let { sites.indexOf(it) } ?: -1
                if (index >= 0) store.setState(ROTATION, index.toString())
                if (!client.run.lost) client.run.retrySoon = true
                Log.warn("Recruitera: cut short by the network; it runs again soon from ${resumeFrom ?: "the start"}")
            }
            error.partialItems = items
            throw error
        }

        return items
    }

    private fun outage(streak: Unanswered, error: BlockedException) =
        BlockedException("Recruitera unreachable: ${streak.count} requests in a row got no answer (${error.message})", transport = true)

    /**
     * Requests in a row that got no answer at all. One unreachable site is
     * skipped; [OUTAGE_AFTER] of them in a row is the network, and walking the
     * other seventy-odd sites into the same timeout would only report an
     * empty run as a clean one.
     */
    class Unanswered(private val limit: Int = OUTAGE_AFTER) {
        var count = 0
            private set

        /** The site the current streak started on, where a retry picks up. */
        var firstSite: String? = null
            private set

        /** @return whether the streak has reached the limit. */
        fun failed(site: String): Boolean {
            if (count == 0) firstSite = site
            count += 1
            return count >= limit
        }

        fun answered() {
            count = 0
            firstSite = null
        }
    }

    /** Career sites from the sitemap, without the demo and test accounts. */
    private fun sites(): List<String> =
        parseSitemapSites(client.get(SITEMAP))
            .filter { it.lowercase() !in EXCLUDED_SITES && !Regex("^test(?:-|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .sorted()

    /** Every job a site has open in a wanted category, with the category's name. */
    private fun techJobs(site: String): List<Pair<JSONObject, String>> {
        val base = "$API_BASE/${encode(site)}"
        val found = mutableListOf<Pair<JSONObject, String>>()

        for ((categoryId, name) in pickCategories(client.get("$base/opportunities/filters"))) {
            var cursor: String? = null
            for (page in 0 until MAX_PAGES) {
                val (jobs, next) = parseOpportunities(
                    client.get("$base/opportunities", mapOf("category" to categoryId, "cursor" to cursor)),
                )
                jobs.forEach { found += it to name }
                if (next == null || jobs.isEmpty()) break
                cursor = next
            }
        }
        return found
    }

    /** No job response names the employer; the slug stands in if this fails. */
    private fun companyName(site: String): String =
        try {
            parseCompanyName(client.get("$API_BASE/${encode(site)}")) ?: site
        } catch (error: BlockedException) {
            if (!error.transport) throw error
            site
        }

    data class Detail(
        val id: String,
        val title: String,
        val available: Boolean,
        val country: String?,
        val location: String?,
        val workModel: String?,
        val employmentType: String?,
        val experience: String?,
        val publishedAt: String?,
        val excerpt: String?,
    )

    companion object {
        const val KEY = "recruitera"

        /** Every six hours: the same jobs stay listed for weeks, and a run is ~100 requests. */
        const val EVERY_MS = 6 * 3_600_000L

        /** Unanswered requests in a row that mean the network is down, not one site. */
        const val OUTAGE_AFTER = 5

        private const val ROTATION = "recruitera.rotation"
        private const val SITE = "https://app.recruitera.ai"
        private const val SITEMAP = "$SITE/sitemap.xml"
        private const val API_BASE = "$SITE/api/public/v1"

        // recutira is Recruitera's own demo site.
        private val EXCLUDED_SITES = setOf("recutira")

        // Recruitera's own category names; every site shares one vocabulary.
        private val CATEGORIES = setOf("it/software development", "engineering - telecom/technology")

        // Read off each job, not the company: Egypt, and Saudi Arabia and the UAE with the switch on.
        fun countries(gulf: Boolean) = if (gulf) setOf("EG", "SA", "AE") else setOf("EG")

        /** The market a kept job's country stands for: "Egypt", "Saudi Arabia", "United Arab Emirates". */
        fun marketOf(country: String?): String? = when (country) {
            "EG" -> "Egypt"
            "SA" -> Gulf.SAUDI
            "AE" -> Gulf.UAE
            else -> null
        }

        private const val MAX_PAGES = 5
        // Job pages opened per run; the rest wait for the next one.
        private const val MAX_DETAILS = 20
        private const val MAX_AGE_DAYS = 7

        private val WORKPLACES = mapOf("remote" to "Remote", "hybrid" to "Hybrid", "on_site" to "On-site")

        private val COUNTRY_NAMES = mapOf(
            "EG" to "Egypt", "SA" to "Saudi Arabia", "AE" to "United Arab Emirates", "QA" to "Qatar",
            "KW" to "Kuwait", "BH" to "Bahrain", "OM" to "Oman", "JO" to "Jordan", "PK" to "Pakistan",
        )

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun jobUrl(site: String, slug: String) = "$SITE/careers/${encode(site)}/${encode(slug)}"

        private fun json(body: String): JSONObject? {
            val text = body.trim()
            if (!text.startsWith("{")) return null
            return runCatching { JSONObject(text) }.getOrNull()
        }

        /** A company page is the sitemap entry with one path segment after /careers/. */
        fun parseSitemapSites(xml: String): List<String> =
            Regex("<loc>\\s*([^<\\s]+)\\s*</loc>").findAll(xml).mapNotNull { match ->
                val path = match.groupValues[1].replace(Regex("^https?://[^/]+"), "").trimEnd('/')
                Regex("^/careers/([^/?#]+)$").find(path)?.groupValues?.get(1)
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            }.distinct().toList()

        /** The wanted categories' ids and names. */
        fun pickCategories(body: String): List<Pair<String, String>> {
            val categories = json(body)?.optJSONObject("data")?.optJSONArray("categories") ?: return emptyList()
            return (0 until categories.length()).mapNotNull { i ->
                val category = categories.optJSONObject(i) ?: return@mapNotNull null
                val id = Html.clean(category.optString("id")) ?: return@mapNotNull null
                val name = category.optString("name").trim()
                if (name.lowercase() in CATEGORIES) id to name else null
            }
        }

        /** One page of a site's opportunities, and the cursor to the next. */
        fun parseOpportunities(body: String): Pair<List<JSONObject>, String?> {
            val payload = json(body) ?: return emptyList<JSONObject>() to null
            val data = payload.optJSONArray("data")
            val jobs = (0 until (data?.length() ?: 0)).mapNotNull { i ->
                data!!.optJSONObject(i)?.takeIf { it.optString("id").isNotEmpty() && it.optString("slug").isNotEmpty() }
            }
            val meta = payload.optJSONObject("meta")
            val next = if (meta == null || meta.isNull("next_cursor")) null else Html.clean(meta.optString("next_cursor"))
            return jobs to next
        }

        fun parseCompanyName(body: String): String? =
            Html.clean(json(body)?.optJSONObject("data")?.optJSONObject("company")?.optString("name"))

        fun parseJobDetail(body: String): Detail? {
            val job = json(body)?.optJSONObject("data") ?: return null
            val id = Html.clean(job.optString("id")) ?: return null
            val title = Html.clean(job.optString("title")) ?: return null

            fun str(obj: JSONObject?, name: String) =
                if (obj == null || obj.isNull(name)) null else Html.clean(obj.optString(name))

            val place = job.optJSONObject("location_details")
            val country = str(place, "country")?.uppercase()
            val city = str(place, "city")

            // The office's own label ("Paymob Head Office") means nothing to a
            // reader; the city and country do.
            val location = listOfNotNull(city, country?.let { COUNTRY_NAMES[it] ?: it }).joinToString(", ")
                .ifEmpty { str(job, "location") }

            // Requirements first: the company blurb up front is where an
            // unrelated "senior leadership" would trip the seniority rules.
            val excerpt = listOf("requirements", "responsibilities", "description")
                .map { Html.strip(str(job, it)) }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .take(4000)
                .ifEmpty { null }

            return Detail(
                id = id,
                title = title,
                available = !(job.has("available") && job.optBoolean("available", true) == false),
                country = country,
                location = location,
                workModel = str(job, "work_model"),
                employmentType = str(job, "employment_type"),
                experience = str(job, "experience"),
                publishedAt = Dates.absoluteToIso(str(job, "published_at")),
                excerpt = excerpt,
            )
        }

        /**
         * Titles no real vacancy carries: QA accounts posting generated jobs
         * ("Legacy Data Agent mun6kji05zs", "… 1790713328542"), and drafts.
         */
        fun isJunkTitle(title: String?): Boolean {
            val text = title?.trim().orEmpty()
            if (text.isEmpty()) return true
            if (Regex("^(?:untitled|test)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return true
            if (Regex("\\(copy\\)\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(text)) return true

            val last = text.split(Regex("\\s+")).last()
            return Regex("^\\d{10,}$").matches(last) ||
                (Regex("^[a-z0-9]{8,}$", RegexOption.IGNORE_CASE).matches(last) && last.any { it.isDigit() } && last.any { it.isLetter() })
        }
    }
}
