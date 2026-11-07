package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Gulf
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.jsoup.Jsoup

/**
 * Tanqeeb, an aggregator that re-lists LinkedIn, Bayt and Wuzzuf — the one
 * route to Bayt's listings that does not mean defeating bot protection. The
 * overlap with LinkedIn is absorbed by the store's company + title fingerprint.
 */
class Tanqeeb(
    override val client: GuestClient = GuestClient("Tanqeeb"),
    /** Search Tanqeeb's Saudi and UAE sites after Egypt's. */
    private val gulf: Boolean = false,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, gulf)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        val seen = LinkedHashMap<String, Item>()

        try {
            for (site in sites(gulf)) for (keyword in keywords) {
                var term = searchTerm(keyword)
                var html = search(site, term)
                var results = parseSearch(html, keyword, site)

                // Tanqeeb finds nothing for some exact phrases ("Laravel Developer")
                // that it has twenty jobs for without the role word.
                val broader = broaderTerm(term)
                if (results.isEmpty() && broader != null) {
                    term = broader
                    html = search(site, term)
                    results = parseSearch(html, keyword, site)
                }

                if (results.isEmpty() && html.length > 2000) {
                    Log.warn("Tanqeeb returned a page with no job cards for \"$term\" (${client.lastResponse})")
                }

                for (item in results) {
                    if (!Dates.isOlderThan(item.postedAt, MAX_AGE_DAYS)) seen.putIfAbsent(item.externalId, item)
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = seen.values.toList()
            throw error
        }

        return enrich(seen.values.toList(), store)
    }

    private fun search(site: Site, term: String): String = client.get(
        "${site.host}/jobs/search",
        mapOf(
            "keywords" to term,
            "page" to 1,
            // Relevance order puts months-old postings first.
            "order_by" to "most_recent",
        ),
    )

    /**
     * Open postings for the employer and the description. A card has no
     * company, and company is half of the cross-source fingerprint.
     */
    private fun enrich(items: List<Item>, store: Store): List<Item> {
        val wanted = items
            .filter { Filters.couldPass(it, requireTitle = false) && store.findByExternalId(KEY, it.externalId) == null }
            .take(MAX_DETAILS)

        for (item in wanted) {
            try {
                val (company, excerpt) = parsePage(client.get(item.url))
                item.company = company ?: item.company
                item.excerpt = excerpt ?: item.excerpt
            } catch (error: BlockedException) {
                if (!error.transport) {
                    error.partialItems = items
                    throw error
                }
            }
        }

        return items
    }

    companion object {
        const val KEY = "tanqeeb"

        /** One country's Tanqeeb: its own host, and the market every job on it is in. */
        data class Site(val host: String, val market: String)

        val EGYPT = Site("https://egypt.tanqeeb.com", "Egypt")
        val GULF = listOf(Site("https://saudi.tanqeeb.com", Gulf.SAUDI), Site("https://uae.tanqeeb.com", Gulf.UAE))

        /** Egypt first, so a short run spends itself there. */
        fun sites(gulf: Boolean) = if (gulf) listOf(EGYPT) + GULF else listOf(EGYPT)
        private const val MAX_AGE_DAYS = 7
        private const val MAX_DETAILS = 10

        private val CARD_RE = Regex(
            """<div[^>]*class="[^"]*search-job-card-top[^"]*"[\s\S]*?(?=<div[^>]*class="[^"]*search-job-card-top|$)""",
            RegexOption.IGNORE_CASE,
        )

        /** A dot sends the search to the homepage: "Node.js" finds nothing, "Node js" does. */
        fun searchTerm(keyword: String) = keyword.replace(".", " ").replace(Regex("\\s+"), " ").trim()

        /** The term without its trailing role word, or null when there is none to drop. */
        fun broaderTerm(term: String): String? =
            term.replace(Regex("\\s+(developer|engineer)$", RegexOption.IGNORE_CASE), "").takeIf { it != term && it.isNotBlank() }

        /**
         * Cards are cut out of the ~450 KB page first and parsed one at a
         * time, so no DOM of the whole page is ever built.
         */
        fun parseSearch(page: String, keyword: String?, site: Site = EGYPT): List<Item> {
            val items = LinkedHashMap<String, Item>()

            for (match in CARD_RE.findAll(page)) {
                val card = Jsoup.parseBodyFragment(match.value)
                val link = card.selectFirst("a.search-job-title-link, .search-job-title a") ?: continue
                val href = link.attr("href").ifEmpty { null } ?: continue
                val title = Html.clean(card.selectFirst(".search-job-title")?.attr("title")) ?: Html.clean(link.text()) ?: continue
                val externalId = Regex("/jobs/(\\d+)\\.html", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1) ?: continue

                val (workplace, location) = splitLocation(Html.clean(card.selectFirst(".search-job-workplace-location")?.text()))

                items.putIfAbsent(
                    externalId,
                    Item(
                        source = KEY,
                        externalId = externalId,
                        title = title,
                        url = if (href.startsWith("http")) href else site.host + (if (href.startsWith("/")) "" else "/") + href,
                        board = Html.clean(card.selectFirst(".search-job-source")?.text()),
                        employmentType = Html.clean(card.selectFirst(".search-job-tag")?.text()),
                        workplace = workplace,
                        location = location,
                        // "8 hours ago" now; "13 August 2026" when this was first written.
                        postedAt = Dates.toIso(Html.clean(card.selectFirst(".search-job-date")?.text())),
                        // One country's site: the market is known.
                        market = site.market,
                        keyword = keyword,
                    ),
                )
            }

            return items.values.toList()
        }

        /** "On-site - Egypt - Cairo": work mode first, then the place. */
        fun splitLocation(value: String?): Pair<String?, String?> {
            if (value == null) return null to null
            val parts = value.split(Regex("\\s+-\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) return null to value
            return Html.clean(parts[0]) to Html.clean(parts.drop(1).joinToString(", "))
        }

        /** The employer and the description, read off the string. */
        fun parsePage(page: String): Pair<String?, String?> {
            if (page.isBlank()) return null to null

            val company = Regex("<[a-z]+[^>]*class=\"[^\"]*job-meta-company[^\"]*\"[^>]*>([^<]*)", RegexOption.IGNORE_CASE)
                .find(page)?.groupValues?.get(1)?.let(Html::detag)

            val start = Regex(">\\s*Job Details\\s*<", RegexOption.IGNORE_CASE).find(page)?.range?.first
            val excerpt = start?.let {
                val from = page.indexOf('>', it) + 1
                Html.detag(page.substring(from, minOf(page.length, it + 12_000)))
                    ?.replaceFirst(Regex("^Job Details\\s*", RegexOption.IGNORE_CASE), "")
                    ?.trim()
                    ?.ifEmpty { null }
            }

            return company to excerpt
        }
    }
}
