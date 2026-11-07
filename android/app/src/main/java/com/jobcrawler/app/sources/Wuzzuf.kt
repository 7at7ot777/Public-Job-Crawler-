package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store

/**
 * Wuzzuf, read through its sitemap: the search pages are behind Cloudflare,
 * while the sitemap and the job pages are open and robots.txt allows both.
 *
 * Every sitemap URL carries the same <lastmod>, so freshness is read off each
 * page. Two filters keep a run from opening thousands: the slug names the
 * role, and the checked ledger remembers what was already opened.
 *
 * Which job sitemaps exist is read from the sitemap index each run, not
 * hard-coded: one file holds 50,000 URLs at most, so new jobs can land in a
 * file a fixed list never reads, and every run then finds nothing new.
 */
class Wuzzuf(
    override val client: GuestClient = GuestClient(
        "Wuzzuf",
        timeoutMs = 30_000,
        headers = mapOf(
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
        ),
    ),
    /** Read Wuzzuf's Saudi job sitemaps after Egypt's. */
    private val gulf: Boolean = true,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    /** What the sitemaps listed this run; see [Source.listed]. */
    override val listed = ListedCount()

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        listed.reset()
        val checked = store.checked.ids(KEY)
        fun opened(candidate: Candidate) =
            candidate.externalId in checked || store.findByExternalId(KEY, candidate.externalId) != null

        // By job id, so a job listed in two files is opened once.
        val unseen = LinkedHashMap<String, Candidate>()

        // Saudi's files only with the Saudi/UAE switch on; Wuzzuf has no UAE site.
        for ((index, sitemap) in sitemaps().filter { gulf || it.market == EGYPT }.withIndex()) {
            if (client.isStopped()) break
            // Each file is megabytes: past the first, read one only with time left to open pages after it.
            if (index > 0 && client.deadline - System.currentTimeMillis() < SITEMAP_RESERVE_MS) {
                Log.info("Wuzzuf: no time left this run for ${nameOf(sitemap.url)}")
                break
            }
            val xml = try {
                client.get(sitemap.url)
            } catch (error: BlockedException) {
                // Unreachable is this one file; throttled is the whole site.
                if (!error.transport) throw error
                Log.warn("Wuzzuf: could not read ${nameOf(sitemap.url)}: ${error.message}")
                continue
            }
            val urls = locs(xml)
            if (urls.isEmpty()) {
                // A challenge page or an empty answer, not a quiet day: say so.
                Log.warn(
                    "Wuzzuf: ${nameOf(sitemap.url)} listed no URLs (${client.lastResponse}); " +
                        "it began: ${xml.take(120).replace(Regex("\\s+"), " ")}",
                )
                continue
            }
            val matched = candidates(urls, sitemap.market)
            // Listed, even if every one is opened already: the sitemap reads fine (Breakage).
            listed.add(matched.size)
            val fresh = matched.filterNot(::opened)
            Log.info(
                "Wuzzuf ${nameOf(sitemap.url)}: ${xml.length / 1024} KB, ${urls.size} URLs, " +
                    "lastmod ${lastmodOf(xml) ?: "none"}, ${matched.size} match the slug, " +
                    "${matched.size - fresh.size} already opened",
            )
            for (candidate in fresh) {
                // In both files: the narrower market places it (the Egypt file lists Saudi jobs too).
                val earlier = unseen[candidate.externalId]
                if (earlier == null || earlier.market == EGYPT) unseen[candidate.externalId] = candidate
            }
        }

        val wanted = unseen.values.take(MAX_DETAILS)

        Log.info("Wuzzuf: ${wanted.size} unseen pages to open")

        val items = mutableListOf<Item>()

        for (candidate in wanted) {
            // Stopped means stop now, with what is parsed, not after nine more pages.
            if (client.isStopped()) break
            val html = try {
                client.get(candidate.url)
            } catch (error: BlockedException) {
                // Throttled means stop. Unreachable means skip this page, and
                // remember it, or every run stops on it again.
                if (!error.transport) {
                    error.partialItems = items
                    throw error
                }
                store.checked.mark(KEY, candidate.externalId)
                continue
            }

            // Opened, so never opened again — whatever is decided below.
            store.checked.mark(KEY, candidate.externalId)

            val parsed = parsePage(html, candidate.url) ?: continue
            if (Dates.isOlderThan(parsed.postedAt, MAX_AGE_DAYS)) continue

            items += parsed.copy(market = candidate.market, keyword = candidate.matched)
        }

        return items
    }

    /**
     * The job sitemaps to read this run, from the index; the known two when
     * the index cannot be read or names none.
     */
    private fun sitemaps(): List<Sitemap> {
        // An index that cannot be read costs the discovery, not the source.
        val index = try {
            client.get(INDEX)
        } catch (error: BlockedException) {
            if (client.isStopped()) throw error
            Log.warn("Wuzzuf: could not read the sitemap index: ${error.message}")
            ""
        }
        val listed = jobSitemaps(index)
        if (listed.isEmpty()) {
            Log.warn("Wuzzuf: the sitemap index named no job sitemaps (${client.lastResponse}); reading the known ones")
            return FALLBACK
        }
        val chosen = choose(listed)
        Log.info(
            "Wuzzuf: ${listed.size} job sitemaps in the index; reading " +
                chosen.joinToString { "${nameOf(it.url)}${it.lastmod?.let { mod -> " ($mod)" } ?: ""}" },
        )
        return chosen
    }

    /** A job sitemap: its country, its number in the index, and when it last changed. */
    data class Sitemap(val url: String, val market: String, val number: Int, val lastmod: String?)

    private data class Candidate(val url: String, val externalId: String, val matched: String, val market: String)

    private fun candidates(urls: List<String>, market: String): List<Candidate> =
        urls.mapNotNull { url ->
            val externalId = externalIdOf(url) ?: return@mapNotNull null
            val slug = url.lowercase()
            val matched = SLUG_TERMS.firstOrNull { it in slug } ?: return@mapNotNull null
            Candidate(url, externalId, matched, market)
        }

    companion object {
        const val KEY = "wuzzuf"
        private const val INDEX = "https://wuzzuf.net/sitemap.xml"
        private const val MAX_DETAILS = 10
        private const val MAX_AGE_DAYS = 7

        /*
         * Files read per market per run. Each is megabytes and Wuzzuf has a
         * minute or two of the run, so the one changed most recently: that
         * is where new jobs are.
         */
        const val MAX_SITEMAPS_PER_MARKET = 1

        /** Time a further sitemap needs left in the source's slice: its download, then pages to open. */
        private const val SITEMAP_RESERVE_MS = 60_000L

        private const val EGYPT = "Egypt"

        /* The country a job sitemap's prefix stands for: sitemap-job-1, sitemap-saudi-job-1. */
        private val MARKETS = mapOf("" to EGYPT, "saudi" to "Saudi Arabia")

        /** Egypt first: it is the main market, and a short run should not spend itself on Saudi. */
        private val FALLBACK = listOf(
            Sitemap("https://wuzzuf.net/sitemap-job-1.xml", EGYPT, 1, null),
            Sitemap("https://wuzzuf.net/sitemap-saudi-job-1.xml", "Saudi Arabia", 1, null),
        )

        private val JOB_SITEMAP_RE = Regex("""/sitemap-(?:([a-z]+)-)?job-(\d+)\.xml$""", RegexOption.IGNORE_CASE)

        /** Every <loc> in a sitemap, trimmed and with &amp; decoded. */
        fun locs(xml: String): List<String> =
            Regex("<loc>([^<]+)</loc>").findAll(xml).map { it.groupValues[1].trim().replace("&amp;", "&") }.toList()

        /** The first <lastmod>: every URL in a job sitemap carries the same one. */
        fun lastmodOf(xml: String): String? =
            Regex("<lastmod>([^<]+)</lastmod>").find(xml)?.groupValues?.get(1)?.trim()

        /** The job sitemaps a sitemap index names, for the markets wanted (not .gz: the client does not unpack it). */
        fun jobSitemaps(index: String): List<Sitemap> =
            Regex("<sitemap>([\\s\\S]*?)</sitemap>").findAll(index).mapNotNull { entry ->
                val url = locs(entry.groupValues[1]).firstOrNull() ?: return@mapNotNull null
                val match = JOB_SITEMAP_RE.find(url) ?: return@mapNotNull null
                val market = MARKETS[match.groupValues[1].lowercase()] ?: return@mapNotNull null
                Sitemap(url, market, match.groupValues[2].toInt(), lastmodOf(entry.groupValues[1]))
            }.distinctBy { it.url }.toList()

        /**
         * Per market, the files changed most recently (the highest-numbered
         * when no dates are given), Egypt first as in [FALLBACK].
         */
        fun choose(sitemaps: List<Sitemap>): List<Sitemap> =
            sitemaps.groupBy { it.market }
                .toList()
                .sortedBy { (market, _) -> if (market == EGYPT) 0 else 1 }
                .flatMap { (_, files) ->
                    files.sortedWith(compareByDescending<Sitemap> { it.lastmod ?: "" }.thenByDescending { it.number })
                        .take(MAX_SITEMAPS_PER_MARKET)
                }

        private fun nameOf(url: String) = url.substringAfterLast('/')

        /* URL fragments, where "node" is "nodejs" or "node-js" and never a word. */
        private val SLUG_TERMS = listOf(
            "backend", "back-end", "laravel", "php", "nodejs", "node-js", "full-stack", "fullstack",
            "software-engineer", "software-developer", "web-developer",
            "golang", "go-developer", "go-engineer", "go-backend",
        )

        /*
         * "<role> job at <company> in <location> – Apply on Wuzzuf", or
         * "<role> vacancy in <location> | 5 to 10 years experience".
         * Only the first names the company.
         */
        private val TITLE_RE = Regex("""^(.*?)\s+job at\s+(.*?)\s+in\s+(.*?)\s+[–-]\s*Apply on Wuzzuf""", RegexOption.IGNORE_CASE)
        private val VACANCY_RE = Regex("""^(.*?)\s+vacancy in\s+([^|]+?)\s*(?:\||$)""", RegexOption.IGNORE_CASE)
        private val EXPERIENCE_RE = Regex("""(\d+)\s*to\s*(\d+)\s*years?(?:\s+of)?\s+experience""", RegexOption.IGNORE_CASE)

        /** The slug's leading token: /jobs/p/yqpqphojdzwj-middle-full-stack… */
        fun externalIdOf(url: String): String? =
            Regex("/jobs/p/([a-z0-9]+)-", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)

        /**
         * A job page, without building a DOM: pages are 500-700 KB and every
         * class is an emotion hash. The h1, the title, og: tags and fixed
         * heading words held across every page tried.
         */
        fun parsePage(page: String, url: String, now: Long = System.currentTimeMillis()): Item? {
            if (page.isBlank()) return null
            val externalId = externalIdOf(url) ?: return null

            val heading = Html.detag(firstGroup(page, Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)))
            val pageTitle = Html.detag(firstGroup(page, Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)))
                ?: meta(page, "og:title")

            val named = pageTitle?.let { TITLE_RE.find(it) }
            val vacancy = if (named != null || pageTitle == null) null else VACANCY_RE.find(pageTitle)

            val title = heading ?: named?.groupValues?.get(1) ?: vacancy?.groupValues?.get(1)
            if (title == null) {
                Log.warn("Wuzzuf page had no readable title: $url")
                return null
            }

            val description = meta(page, "og:description")

            return Item(
                source = KEY,
                externalId = externalId,
                title = title,
                url = url,
                company = named?.groupValues?.get(2)?.let(Html::clean),
                location = Html.clean(named?.groupValues?.get(3) ?: vacancy?.groupValues?.get(2)),
                postedAt = Dates.relativeToIso(page, now),
                excerpt = descriptionOf(page) ?: description,
                seniority = EXPERIENCE_RE.find("${pageTitle ?: ""} ${description ?: ""}")
                    ?.let { "${it.groupValues[1]} to ${it.groupValues[2]} years experience" },
            )
        }

        /** From the "Job Description" heading to the next section, detagging only that window. */
        private fun descriptionOf(page: String): String? {
            val start = Regex(">\\s*Job Description\\s*<", RegexOption.IGNORE_CASE).find(page)?.range?.first ?: return null
            val window = page.substring(start, minOf(page.length, start + 12_000))
            val body = Html.detag(window.replaceFirst(Regex("^[^<]*<"), "<")) ?: return null

            val end = Regex("Job Requirements|Job Details|About the Company|Similar Jobs", RegexOption.IGNORE_CASE).find(body)?.range?.first
            val text = (if (end == null) body else body.substring(0, end))
                .replaceFirst(Regex("^Job Description\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            return text.ifEmpty { null }
        }

        private fun meta(page: String, property: String): String? {
            val tag = Regex("<meta[^>]*(?:property|name)=[\"']$property[\"'][^>]*>", RegexOption.IGNORE_CASE).find(page)?.value
                ?: return null
            return Html.clean(Html.decode(firstGroup(tag, Regex("content=[\"']([\\s\\S]*?)[\"']", RegexOption.IGNORE_CASE))))
        }

        private fun firstGroup(text: String, pattern: Regex) = pattern.find(text)?.groupValues?.get(1)
    }
}
