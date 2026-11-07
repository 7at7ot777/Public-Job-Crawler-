package com.jobcrawler.app.sources

import com.jobcrawler.app.Item

/**
 * VerteX Technologies' vacancy pages (vertextech-eg.com/vacancies), one of the
 * [CareerSites]. A Framer site with no JSON-LD: the classes are hashes, but
 * the layers carry the designer's names ("Vacancy description"), and those
 * are what this reads. The level and stack chips sit between the heading and
 * the description.
 */
object Vertex {
    private val LEVELS = listOf("Trainee", "Junior", "Middle", "Senior", "Tech lead", "Architect", "Principal")
    private const val DESCRIPTION = "data-framer-name=\"Vacancy description\""

    fun parse(page: String, info: CareerSites.Page): Item? {
        if (page.isBlank()) return null

        val title = Html.detag(CareerSites.firstGroup(page, Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)))
            ?: Html.clean(Html.decode(CareerSites.firstGroup(page, Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)))
                ?.replace(Regex("\\s+-\\s+VerteX Technologies\\s*$", RegexOption.IGNORE_CASE), ""))
            ?: return null

        val tags = tagsOf(page)
        val level = tags.firstOrNull { tag -> LEVELS.any { it.equals(tag, ignoreCase = true) } }
        val excerpt = listOfNotNull(tags.joinToString(" · ").ifEmpty { null }, descriptionOf(page))
            .joinToString("\n")
            .take(4000)

        return Item(
            source = CareerSites.KEY,
            externalId = info.externalId,
            title = title,
            url = info.url,
            company = info.site.company,
            location = locationOf(page) ?: info.site.location,
            market = info.site.market,
            seniority = level,
            excerpt = excerpt.ifEmpty { null },
        )
    }

    private fun tagsOf(page: String): List<String> {
        val start = page.indexOf("</h1>")
        val end = page.indexOf(DESCRIPTION)
        if (start == -1 || end == -1 || end < start) return emptyList()

        return Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
            .findAll(page.substring(start, end))
            .mapNotNull { Html.detag(it.groupValues[1]) }
            .filter { it.length <= 60 }
            .distinct()
            .toList()
    }

    /** Framer puts no data-framer attribute inside rich text, so the next one ends it. */
    private fun descriptionOf(page: String): String? {
        val marker = page.indexOf(DESCRIPTION)
        if (marker == -1) return null
        val open = page.indexOf('>', marker)
        if (open == -1) return null

        val next = page.indexOf("data-framer-", open)
        var window = page.substring(open + 1, if (next == -1) minOf(page.length, open + 20_000) else next)

        // The cut lands inside the next layer's opening tag.
        val lastTag = window.lastIndexOf('<')
        if (lastTag != -1 && window.indexOf('>', lastTag) == -1) window = window.substring(0, lastTag)

        return Html.strip(window).ifEmpty { null }
    }

    /** "…job position in Cairo, Smart Village. Apply…" — the page's own place, plus the country. */
    private fun locationOf(page: String): String? {
        val description = CareerSites.meta(page, "description") ?: CareerSites.meta(page, "og:description") ?: return null
        val place = Html.clean(Regex("""\bposition in\s+([^.!|]+?)\s*[.!|]""", RegexOption.IGNORE_CASE).find(description)?.groupValues?.get(1))
            ?: return null
        return if (place.contains("egypt", ignoreCase = true)) place else "$place, Egypt"
    }
}
