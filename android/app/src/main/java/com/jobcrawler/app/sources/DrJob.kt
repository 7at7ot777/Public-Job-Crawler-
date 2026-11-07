package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.StateStore
import com.jobcrawler.app.Store
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Dr.Job (drjobpro.com, and drjobs.ae for the UAE), a Middle East board
 * whose robots.txt allows every crawler. Each keyword has its own
 * server-rendered page, newest first ("/egypt/php-developer-jobs"), with
 * twenty cards that already carry the company, the place and the age.
 * A card that could pass the filters and is not stored yet has its page
 * opened for the whole ad (JobPosting JSON-LD), at most [MAX_DETAILS] a run.
 *
 * Part of it is re-listed from other boards; the store's company + title
 * fingerprint absorbs that overlap.
 */
class DrJob(
    override val client: GuestClient = GuestClient("Dr.Job"),
    /** Search the Saudi and UAE sites after Egypt's. */
    private val gulf: Boolean = false,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        val seen = walk(sites(gulf), keywords, store, get = { client.get(it) }, isStopped = { client.isStopped() }) { html, keyword, site ->
            val cards = parseList(html, keyword, site)
            if (cards.isEmpty() && html.length > 2000 && !html.contains("No results matching", ignoreCase = true)) {
                Log.warn("Dr.Job returned a page with no job cards for \"$keyword\" on ${site.market} (${client.lastResponse})")
            }
            cards.filter { !Dates.isOlderThan(it.postedAt, MAX_AGE_DAYS) }
        }
        return enrich(seen, store)
    }

    /** The whole ad for the cards worth it; a card keeps its snippet when the page does not answer. */
    private fun enrich(items: List<Item>, store: Store): List<Item> {
        val wanted = items
            .filter { Filters.couldPass(it) && store.findByExternalId(KEY, it.externalId) == null }
            .take(MAX_DETAILS)
        for (item in wanted) {
            try {
                val detail = parseDetail(client.get(item.url)) ?: continue
                item.excerpt = detail.description ?: item.excerpt
                item.company = item.company ?: detail.company
                // The card's "15 hours ago" is finer than the page's date alone.
                item.postedAt = item.postedAt ?: detail.postedAt
                item.employmentType = item.employmentType ?: detail.employmentType
            } catch (error: BlockedException) {
                // A dead link or one page the server cannot render: that card keeps its snippet.
                if (!error.transport && serverStatus(error) == null) {
                    error.partialItems = items
                    throw error
                }
            }
        }
        return items
    }

    /** One country's listing pages, and the market every job on them is in. */
    data class Site(val base: String, val market: String)

    data class Detail(val description: String?, val company: String?, val postedAt: String?, val employmentType: String?)

    companion object {
        const val KEY = "drjob"
        private const val MAX_AGE_DAYS = 7
        private const val MAX_DETAILS = 10

        val EGYPT = Site("https://www.drjobpro.com/egypt", "Egypt")

        /* The UAE moved to its own domain: drjobpro.com's UAE pages answer 404 and point there. */
        val GULF = listOf(Site("https://www.drjobpro.com/saudi-arabia", Gulf.SAUDI), Site("https://drjobs.ae", Gulf.UAE))

        /*
         * drjobs.ae answers HTTP 500, not an empty page, for a keyword it has
         * no listings under ("/laravel-developer-jobs", "/php-developer-jobs",
         * even its own "/search-jobs?keyword=laravel"), while "/backend-developer-jobs"
         * works. One such page is that page's answer, not the site refusing us.
         */

        /** Paths that answered 5xx: "count|epoch ms of the last one", under this prefix plus the URL. */
        const val FAILED_PREFIX = "drjob.5xx."

        /** A path that 5xx'd n runs running waits n days (at most [MAX_SKIP_DAYS]) before it is tried again. */
        const val MAX_SKIP_DAYS = 7

        /** This many 5xx on one site, with nothing from it answering, is the site down: its other paths wait. */
        const val SITE_DOWN_AFTER = 3

        private const val DAY_MS = 86_400_000L

        private val SERVER_ERROR = Regex("""returned HTTP (5\d\d) after""")

        /**
         * The status when [error] is [com.jobcrawler.app.GuestClient]'s "returned HTTP 5xx
         * after n attempts"; null for a real block (429/403/999), the deadline, a stop.
         */
        fun serverStatus(error: BlockedException): Int? =
            SERVER_ERROR.find(error.message.orEmpty())?.groupValues?.get(1)?.toInt()

        /** Whether [url] 5xx'd recently enough to be left out this run. */
        fun isResting(state: StateStore, url: String, now: Long): Boolean {
            val (count, last) = failure(state, url) ?: return false
            return now < last + minOf(count, MAX_SKIP_DAYS) * DAY_MS
        }

        private fun failure(state: StateStore, url: String): Pair<Int, Long>? {
            val parts = state.getState(FAILED_PREFIX + url)?.split("|") ?: return null
            val count = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val last = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return if (count > 0) count to last else null
        }

        /**
         * Every site's keyword pages, in order, into one list of cards.
         *
         * A page that still answers 5xx after the client's retries is skipped,
         * logged and remembered ([isResting]), and the walk goes on. A real
         * block, the deadline or a stop throws, carrying what was read; so does
         * every site being down, which the run should report rather than call a
         * quiet day.
         */
        fun walk(
            sites: List<Site>,
            keywords: List<String>,
            state: StateStore,
            get: (String) -> String,
            isStopped: () -> Boolean = { false },
            now: Long = System.currentTimeMillis(),
            parse: (html: String, keyword: String, site: Site) -> List<Item>,
        ): List<Item> {
            val seen = LinkedHashMap<String, Item>()
            val resting = mutableListOf<String>()
            val failed = mutableListOf<String>()
            var answered = 0
            var tried = 0
            try {
                for (site in sites) {
                    var siteAnswered = 0
                    var siteFailed = 0
                    for (keyword in keywords) {
                        if (isStopped()) break
                        val slug = slug(keyword) ?: continue
                        val url = "${site.base}/$slug-jobs"
                        if (isResting(state, url, now)) {
                            resting += url
                            continue
                        }
                        tried += 1
                        val html = try {
                            get(url)
                        } catch (error: BlockedException) {
                            val status = serverStatus(error) ?: throw error
                            siteFailed += 1
                            failed += url
                            val count = (failure(state, url)?.first ?: 0) + 1
                            state.setState(FAILED_PREFIX + url, "$count|$now")
                            Log.warn("Dr.Job: HTTP $status for ${url.removePrefix("https://")}, skipped; next tried in ${minOf(count, MAX_SKIP_DAYS)} day(s)")
                            if (siteAnswered == 0 && siteFailed >= SITE_DOWN_AFTER) {
                                Log.warn("Dr.Job: ${site.market} answered HTTP 5xx $siteFailed times and nothing else; its other pages wait for the next run")
                                break
                            }
                            continue
                        }
                        siteAnswered += 1
                        answered += 1
                        if (failure(state, url) != null) state.setState(FAILED_PREFIX + url, "")
                        for (item in parse(html, keyword, site)) seen.putIfAbsent(item.externalId, item)
                    }
                }
            } catch (error: BlockedException) {
                error.partialItems = seen.values.toList()
                throw error
            }
            if (resting.isNotEmpty()) {
                Log.info("Dr.Job: left out ${resting.size} page(s) that answered HTTP 5xx recently: ${resting.joinToString { it.removePrefix("https://") }}")
            }
            if (answered == 0 && failed.size == tried && failed.size >= SITE_DOWN_AFTER) {
                throw BlockedException(
                    "Dr.Job returned HTTP 5xx for all ${failed.size} page(s) it tried; the site looks down",
                    partialItems = seen.values.toList(),
                )
            }
            return seen.values.toList()
        }

        /** Egypt first, so a short run spends itself there. */
        fun sites(gulf: Boolean) = if (gulf) listOf(EGYPT) + GULF else listOf(EGYPT)

        /** "Node.js Developer" to "node-js-developer", as the site names its keyword pages; null for nothing left. */
        fun slug(keyword: String): String? =
            keyword.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { null }

        private val CARD_RE = Regex(
            """<div[^>]*class="card-item job-card[\s\S]*?(?=<div[^>]*class="card-item job-card|$)""",
            RegexOption.IGNORE_CASE,
        )

        /* The job's id ends its address: ".../php-backend-developer-cairo-pentavalue-MVZW6DME27M83AG". */
        private val ID_RE = Regex("""-([A-Z0-9]{15})/?$""")

        /**
         * Cards are cut out of the ~350 KB page first and parsed one at a
         * time, so no DOM of the whole page is ever built.
         */
        fun parseList(page: String, keyword: String?, site: Site = EGYPT): List<Item> {
            val items = LinkedHashMap<String, Item>()
            for (match in CARD_RE.findAll(page)) {
                val card = Jsoup.parseBodyFragment(match.value)
                val href = card.selectFirst("a.job-card-stretched-link")?.attr("href")?.ifEmpty { null } ?: continue
                val url = if (href.startsWith("http")) href else origin(site) + (if (href.startsWith("/")) "" else "/") + href
                val id = ID_RE.find(url)?.groupValues?.get(1) ?: continue
                val title = Html.clean(card.selectFirst("h2.job-name")?.text()) ?: continue
                items.putIfAbsent(
                    id,
                    Item(
                        source = KEY,
                        externalId = id,
                        title = title,
                        url = url,
                        company = Html.clean(card.selectFirst(".company-name")?.text()),
                        // "Cairo - Egypt".
                        location = Html.clean(card.selectFirst(".location span")?.text())?.replace(Regex("\\s+-\\s+"), ", "),
                        postedAt = age(Html.clean(card.selectFirst(".posted-date span")?.text())),
                        employmentType = Html.clean(card.selectFirst(".job-type")?.text()),
                        excerpt = Html.clean(card.selectFirst(".job-desc-attr")?.text()),
                        // One country's pages: the market is known.
                        market = site.market,
                        keyword = keyword,
                        board = "Dr.Job",
                    ),
                )
            }
            return items.values.toList()
        }

        private fun origin(site: Site) = Regex("""^https?://[^/]+""").find(site.base)?.value ?: site.base

        /** "15 hours ago" as a date; "30+ days ago", the oldest the site says, as thirty days. */
        fun age(text: String?, now: Long = System.currentTimeMillis()): String? =
            Dates.toIso(text?.replace(Regex("""(\d+)\+"""), "$1"), now)

        private val LD_JSON = Regex("""<script[^>]*application/ld\+json[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)

        /** The job page's JobPosting JSON-LD, or null when the page has none. */
        fun parseDetail(page: String): Detail? {
            for (match in LD_JSON.findAll(page)) {
                val json = runCatching { JSONObject(match.groupValues[1].trim()) }.getOrNull() ?: continue
                if (json.optString("@type") != "JobPosting") continue
                return Detail(
                    description = Html.strip(json.optString("description")).ifEmpty { null }?.take(12_000),
                    company = Html.clean(json.optJSONObject("hiringOrganization")?.optString("name")),
                    postedAt = Dates.absoluteToIso(json.optString("datePosted")),
                    employmentType = Html.clean(json.optString("employmentType"))?.takeIf { it != "null" },
                )
            }
            return null
        }
    }
}
