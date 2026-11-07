package com.jobcrawler.app

import com.jobcrawler.app.sources.CareerSites
import com.jobcrawler.app.sources.Html
import java.net.URI

/**
 * Turns a pasted careers link into a [SavedSite]: finds the site's sitemap,
 * then the address pattern its job pages share.
 *
 * The pattern is "a prefix plus one more segment": /careers/backend-developer
 * under /careers. The pasted link's own path is tried first, since it is
 * usually the jobs list; otherwise the prefix that ends in a jobs word
 * (careers, vacancies, jobs…) and has the most pages under it wins.
 */
class SiteFinder(private val client: GuestClient = GuestClient("Site finder", timeoutMs = 15_000, delayMs = 300)) {

    /** What was found, for the confirm dialog: the site to save and a few of its job pages. */
    data class Found(val site: SavedSite, val jobCount: Int, val examples: List<String>)

    class NotFound(message: String) : Exception(message)

    fun find(link: String, takenIds: Collection<String>): Found {
        val start = normalize(link) ?: throw NotFound("That doesn't look like a web address.")
        val uri = URI(start)
        val host = uri.host.lowercase().removePrefix("www.")
        val origin = "${uri.scheme}://${uri.host}"

        val robots = runCatching { client.get("$origin/robots.txt") }.getOrDefault("")
        val sitemaps = (sitemapsIn(robots) + COMMON_SITEMAPS.map { origin + it }).distinct()

        for (sitemap in sitemaps) {
            val xml = runCatching { client.get(sitemap) }.getOrDefault("")
            if (!xml.contains("<loc", ignoreCase = true)) continue

            // A sitemap index: the job sitemaps first, by name, and only those when any is named so.
            val children = if (xml.contains("<sitemapindex", ignoreCase = true)) {
                val all = CareerSites.locs(xml)
                val named = all.filter { JOB_SITEMAP.containsMatchIn(it) }
                (named.ifEmpty { all }).take(MAX_CHILD_SITEMAPS).map { it to runCatching { client.get(it) }.getOrDefault("") }
            } else {
                listOf(sitemap to xml)
            }

            val locs = children.flatMap { (_, body) -> CareerSites.locs(body) }.filter { sameHost(it, host) }
            val prefix = jobPrefix(uri.path ?: "", locs) ?: continue
            val pattern = patternFor(host, prefix)
            val jobs = locs.filter { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(it) }.distinct()

            // Save the one child sitemap that holds the jobs, or the index narrowed to those that do.
            val holding = children.filter { (_, body) -> CareerSites.locs(body).any { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(it) } }
                .map { it.first }
            val (savedSitemap, filter) = when {
                holding.size == 1 -> holding.first() to null
                children.size == 1 -> sitemap to null
                else -> sitemap to holding.joinToString("|") { Regex.escape(it) }
            }

            val page = runCatching { client.get(start) }.getOrDefault("")
            val site = SavedSite(
                id = UserSites.freeId(idFor(host), takenIds),
                company = companyName(page, host),
                sitemap = savedSitemap,
                pattern = pattern,
                sitemapFilter = filter,
            )
            return Found(site, jobs.size, jobs.take(3))
        }

        throw NotFound("No sitemap on $host lists job pages, so it can't be followed this way.")
    }

    companion object {
        private val COMMON_SITEMAPS = listOf("/sitemap.xml", "/sitemap_index.xml", "/wp-sitemap.xml", "/sitemap-index.xml")
        private const val MAX_CHILD_SITEMAPS = 6

        private val JOB_SITEMAP = Regex("job|career|vacanc|position|opening|opportunit", RegexOption.IGNORE_CASE)

        /** The last segment of a prefix that names a jobs list. */
        private val JOB_WORD = Regex(
            "^(?:jobs?|careers?|vacanc(?:y|ies)|positions?|openings?|opportunities|open-positions|job-openings|join-us|work-with-us|hiring)$",
            RegexOption.IGNORE_CASE,
        )

        fun normalize(link: String): String? {
            val text = link.trim()
            if (text.isEmpty() || text.contains(' ')) return null
            val withScheme = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "https://$text"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank() || !uri.host.contains('.')) return null
            return withScheme
        }

        fun sitemapsIn(robots: String): List<String> =
            Regex("^\\s*sitemap:\\s*(\\S+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                .findAll(robots).map { it.groupValues[1] }.toList()

        private fun sameHost(url: String, host: String): Boolean =
            runCatching { URI(url).host.lowercase().removePrefix("www.") == host }.getOrDefault(false)

        private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

        /**
         * The path the job pages sit one segment under, or null when no page
         * looks like one. [linkPath] is the pasted link's path.
         */
        fun jobPrefix(linkPath: String, locs: List<String>): String? {
            val counts = HashMap<String, Int>()
            for (loc in locs) {
                val parts = segments(runCatching { URI(loc).path }.getOrNull() ?: continue)
                if (parts.size < 2) continue
                val prefix = parts.dropLast(1).joinToString("/").lowercase()
                counts[prefix] = (counts[prefix] ?: 0) + 1
            }

            val linked = segments(linkPath).joinToString("/").lowercase()
            if (linked.isNotEmpty() && (counts[linked] ?: 0) > 0) return linked

            return counts.filterKeys { JOB_WORD.matches(it.substringAfterLast('/')) }
                .maxByOrNull { it.value }
                ?.key
        }

        fun patternFor(host: String, prefix: String): String {
            val path = prefix.split('/').joinToString("/") { Regex.escape(it) }
            return "^https?://(?:www\\.)?${Regex.escape(host)}/$path/([^/?#]+)/?$"
        }

        /** "acme" for jobs.acme.com or acme.com.eg. */
        fun idFor(host: String): String {
            val labels = host.split('.').filter { it.isNotEmpty() && it !in setOf("www", "jobs", "careers", "career") }
            val name = labels.dropLastWhile { it.length <= 3 && labels.size > 1 }.lastOrNull() ?: labels.firstOrNull() ?: "site"
            return name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "site" }
        }

        /** og:site_name, then the title's brand part, then the host. */
        fun companyName(page: String, host: String): String {
            CareerSites.meta(page, "og:site_name")?.let { return it }

            val title = Html.detag(CareerSites.firstGroup(page, Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)))
            val brand = title?.split(Regex("\\s+[|\\-–—]\\s+"))?.takeIf { it.size > 1 }?.last()
                ?.replace(Regex("\\s+(?:careers?|jobs)$", RegexOption.IGNORE_CASE), "")
                ?.let { Html.clean(it) }
            if (brand != null) return brand

            return idFor(host).split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        }
    }
}
