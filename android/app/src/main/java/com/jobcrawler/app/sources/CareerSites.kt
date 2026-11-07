package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Companies' own careers sites: a sitemap that lists every open job, and one
 * page per job. Framer, Webflow, WordPress and most hand-built sites look like
 * this, so adding a company is one [Site] line in [SITES], not a new source.
 *
 * A job page is read through its JobPosting JSON-LD when it has one (what
 * Google for Jobs asks sites for), otherwise through its heading, og: tags
 * and main text. A site whose markup says more than that brings its own
 * [Site.parser], as VerteX does.
 *
 * Most such sites date nothing, so "new" means "not listed last time". Each
 * site keeps its own checked ledger: only pages it has never opened are
 * opened, and a site's first run records what is open and sends none of it —
 * otherwise adding a company would post its whole board at once.
 */
class CareerSites(
    override val client: GuestClient = GuestClient(
        "Career sites",
        timeoutMs = 20_000,
        // Every site is another host; the pace only keeps the phone polite.
        delayMs = 1_500,
    ),
    private val sites: List<Site> = SITES,
) : Source {
    override val key = KEY
    override val regions get() = com.jobcrawler.app.Region.reach(KEY, sites.any { com.jobcrawler.app.Gulf.isGulf(it.market) })

    /** What the sitemaps listed this run; see [Source.listed]. */
    override val listed = ListedCount()

    /**
     * @param jobUrl matches a job page's address in the sitemap; its first
     *        group, when it has one, is the job's id within the site.
     * @param sitemapFilter for a sitemap index: which child sitemaps to read.
     * @param parser the site's own page reader, for markup the generic one misses.
     */
    data class Site(
        val id: String,
        val company: String,
        val sitemap: String,
        val jobUrl: Regex,
        val location: String = "Cairo, Egypt",
        val market: String = "Egypt",
        val sitemapFilter: Regex? = null,
        val parser: ((String, Page) -> Item?)? = null,
    )

    /** What a parser is told about the page it reads. */
    data class Page(val url: String, val externalId: String, val site: Site)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        listed.reset()
        val items = mutableListOf<Item>()

        try {
            Rotation(store, "$KEY.rotation").walk(sites) { site ->
                try {
                    items.addAll(visit(site, store))
                } catch (error: BlockedException) {
                    // One company's site being down is not a reason to skip the rest.
                    if (!error.transport) throw error
                    Log.warn("Career sites: could not read ${site.company}: ${error.message}")
                }
            }
        } catch (error: BlockedException) {
            error.partialItems = items
            throw error
        }

        return items
    }

    private fun visit(site: Site, store: Store): List<Item> {
        val pages = jobPages(site)
        if (pages.isEmpty()) {
            Log.warn("Career sites: ${site.company}'s sitemap listed no jobs (${client.lastResponse})")
            return emptyList()
        }
        // Listed, even if every one is known already: the sitemap reads fine (Breakage).
        listed.add(pages.size)

        val ledger = "$KEY:${site.id}"
        val checked = store.checked.ids(ledger)

        if (checked.isEmpty()) {
            pages.forEach { store.checked.mark(ledger, it.externalId) }
            Log.info("Career sites: first run for ${site.company}, recorded ${pages.size} open jobs without sending them")
            return emptyList()
        }

        // Still listed: touched again, so the ledger's pruning never forgets an
        // open job and sends it as new.
        pages.filter { it.externalId in checked }.forEach { store.checked.mark(ledger, it.externalId) }

        val wanted = pages
            .filter { it.externalId !in checked && store.findByExternalId(KEY, it.externalId) == null }
            .take(MAX_DETAILS)

        Log.info("Career sites: ${site.company}: ${wanted.size} new jobs to open")

        val items = mutableListOf<Item>()

        for (page in wanted) {
            val html = try {
                client.get(page.url)
            } catch (error: BlockedException) {
                if (!error.transport) {
                    error.partialItems = items
                    throw error
                }
                store.checked.mark(ledger, page.externalId)
                continue
            }

            // Opened, so never opened again — whatever is decided below.
            store.checked.mark(ledger, page.externalId)

            val parse = site.parser ?: { body: String, info: Page -> parseGeneric(body, info) }
            val item = runCatching { parse(html, page) }
                .onFailure { Log.warn("Career sites: could not read ${page.url}: ${it.message}") }
                .getOrNull()

            if (item == null) {
                Log.warn("Career sites: ${page.url} had no readable job")
                continue
            }
            items += item
        }

        return items
    }

    /** Every job page the site's sitemap lists, following a sitemap index one level down. */
    private fun jobPages(site: Site): List<Page> {
        val xml = client.get(site.sitemap)

        val sitemaps = if (Regex("<sitemapindex", RegexOption.IGNORE_CASE).containsMatchIn(xml)) {
            locs(xml).filter { site.sitemapFilter?.containsMatchIn(it) ?: true }.take(MAX_SITEMAPS).map { client.get(it) }
        } else {
            listOf(xml)
        }

        return sitemaps.flatMap { locs(it) }
            .mapNotNull { url -> idOf(site, url)?.let { Page(url, "${site.id}:$it", site) } }
            .distinctBy { it.externalId }
    }

    companion object {
        const val KEY = "careers"
        private const val MAX_DETAILS = 20
        private const val MAX_SITEMAPS = 5
        private const val MAX_EXCERPT = 4000

        /*
         * One line per company. jobUrl is matched against the full address;
         * check a site's /sitemap.xml for the pattern its job pages follow.
         */
        val SITES = listOf(
            Site(
                "vertex", "VerteX Technologies", "https://vertextech-eg.com/sitemap.xml",
                Regex("""^https?://(?:www\.)?vertextech-eg\.com/vacancies/([^/?#]+)/?$""", RegexOption.IGNORE_CASE),
                location = "Smart Village, Cairo, Egypt", parser = Vertex::parse,
            ),
        )

        fun locs(xml: String): List<String> =
            Regex("<loc>\\s*([^<]+?)\\s*</loc>", RegexOption.IGNORE_CASE).findAll(xml)
                .mapNotNull { Html.decode(it.groupValues[1])?.trim() }
                .toList()

        /** The job's id within its site: the pattern's group, or else the path. */
        fun idOf(site: Site, url: String): String? {
            val match = site.jobUrl.find(url.trim()) ?: return null
            val group = if (match.groupValues.size > 1) match.groupValues[1] else ""
            val raw = group.ifEmpty { null } ?: Regex("^https?://[^/]+/?").replace(url.trim(), "").trimEnd('/')
            if (raw.isEmpty()) return null
            return runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw).lowercase()
        }

        /** A job page read through JSON-LD when it has a JobPosting, through its markup otherwise. */
        fun parseGeneric(page: String, info: Page): Item? {
            if (page.isBlank()) return null
            return fromJsonLd(page, info) ?: fromMarkup(page, info)
        }

        private fun fromJsonLd(page: String, info: Page): Item? {
            val posting = jsonLdBlocks(page).firstNotNullOfOrNull { findPosting(it) } ?: return null

            val title = Html.clean(Html.decode(posting.optString("title"))) ?: return null

            // Past its own closing date: closed, whatever the sitemap still says.
            val validThrough = runCatching { Instant.parse(Dates.toIso(posting.optString("validThrough"))) }.getOrNull()
            if (validThrough != null && validThrough.isBefore(Instant.now())) return null

            val place = placeOf(posting)
            val remote = posting.optString("jobLocationType").equals("TELECOMMUTE", ignoreCase = true)

            return Item(
                source = KEY,
                externalId = info.externalId,
                title = title,
                url = info.url,
                company = Html.clean(posting.optJSONObject("hiringOrganization")?.optString("name")) ?: info.site.company,
                location = place ?: info.site.location,
                market = info.site.market,
                postedAt = Dates.toIso(posting.optString("datePosted").ifEmpty { null }),
                excerpt = Html.strip(posting.optString("description")).take(MAX_EXCERPT).ifEmpty { null },
                employmentType = textOf(posting.opt("employmentType")),
                remote = remote,
                workplace = if (remote) "Remote" else null,
            )
        }

        private fun fromMarkup(page: String, info: Page): Item? {
            val company = info.site.company
            val title = Html.detag(firstGroup(page, Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)))
                ?: withoutCompany(meta(page, "og:title"), company)
                ?: withoutCompany(Html.detag(firstGroup(page, Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE))), company)
                ?: return null

            return Item(
                source = KEY,
                externalId = info.externalId,
                title = title,
                url = info.url,
                company = company,
                location = info.site.location,
                market = info.site.market,
                excerpt = (mainText(page) ?: meta(page, "og:description") ?: meta(page, "description"))?.take(MAX_EXCERPT),
            )
        }

        /** The page's <main> or <article>, without its scripts and chrome. */
        private fun mainText(page: String): String? {
            val body = firstGroup(page, Regex("<main\\b[^>]*>([\\s\\S]*?)</main>", RegexOption.IGNORE_CASE))
                ?: firstGroup(page, Regex("<article\\b[^>]*>([\\s\\S]*?)</article>", RegexOption.IGNORE_CASE))
                ?: return null
            val text = Html.strip(
                body.replace(Regex("<(script|style|noscript|nav|header|footer|form|svg)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " "),
            )
            return text.ifEmpty { null }
        }

        private fun jsonLdBlocks(page: String): List<Any> =
            Regex("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
                .findAll(page)
                .mapNotNull { match ->
                    val text = match.groupValues[1].trim()
                    runCatching<Any> { if (text.startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull()
                }
                .toList()

        /** The JobPosting in a block, which may be a list or sit in an @graph. */
        private fun findPosting(node: Any?): JSONObject? = when (node) {
            is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findPosting(node.opt(it)) }
            is JSONObject -> when {
                isPosting(node) -> node
                else -> findPosting(node.opt("@graph"))
            }
            else -> null
        }

        private fun isPosting(node: JSONObject): Boolean = when (val type = node.opt("@type")) {
            is String -> type.equals("JobPosting", ignoreCase = true)
            is JSONArray -> (0 until type.length()).any { type.optString(it).equals("JobPosting", ignoreCase = true) }
            else -> false
        }

        /** "Cairo, Egypt" from jobLocation, which may be one place or several. */
        private fun placeOf(posting: JSONObject): String? {
            val locations = when (val value = posting.opt("jobLocation")) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
                is JSONObject -> listOf(value)
                else -> emptyList()
            }

            val places = locations.mapNotNull { location ->
                val address = location.optJSONObject("address") ?: return@mapNotNull null
                val country = address.optJSONObject("addressCountry")?.optString("name") ?: address.optString("addressCountry")
                listOf(address.optString("addressLocality"), address.optString("addressRegion"), country)
                    .mapNotNull { Html.clean(it) }
                    .distinct()
                    .joinToString(", ")
                    .ifEmpty { null }
            }.distinct()

            return places.joinToString(" / ").ifEmpty { null }
        }

        private fun textOf(value: Any?): String? = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { Html.clean(value.optString(it)) }.joinToString(", ").ifEmpty { null }
            is String -> Html.clean(value)
            else -> null
        }

        /** "Backend Developer - Acme" or "Backend Developer | Acme Careers" to the role alone. */
        private fun withoutCompany(title: String?, company: String): String? {
            val text = Html.clean(title) ?: return null
            val parts = text.split(Regex("\\s+[|\\-–—]\\s+"))
            val role = parts.filterNot { it.contains(company, ignoreCase = true) }.firstOrNull() ?: parts.first()
            return Html.clean(role)
        }

        fun meta(page: String, name: String): String? {
            val tag = Regex("<meta[^>]*(?:name|property)=[\"']${Regex.escape(name)}[\"'][^>]*>", RegexOption.IGNORE_CASE).find(page)?.value
                ?: return null
            // The closing quote is the opening one: "We're hiring" is in double quotes.
            val content = Regex("content=([\"'])([\\s\\S]*?)\\1", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)
            return Html.clean(Html.decode(content))
        }

        fun firstGroup(text: String, pattern: Regex) = pattern.find(text)?.groupValues?.get(1)
    }
}
