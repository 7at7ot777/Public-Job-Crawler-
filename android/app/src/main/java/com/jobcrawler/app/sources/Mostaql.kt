package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.jsoup.Jsoup

/**
 * Mostaql (mostaql.com), the Arabic freelance board: open projects in its
 * programming category. robots.txt only keeps bots off /search and /ajax,
 * so the public category listing and the project pages are read.
 *
 * One listing page a run (25 newest projects), then the project page of
 * each new one, up to [MAX_DETAILS]: only it has the budget, the duration,
 * the skills and the full brief. Gigs that name the CV's stack go to the
 * Freelance section (Freelance.fits, applied by Filters).
 */
class Mostaql(
    override val client: GuestClient = GuestClient("Mostaql"),
    private val cadence: Cadence = Cadence(KEY, EVERY_MS),
) : Source {
    override val key = KEY

    /** Posted for the person to bid on; there is no address to send a CV to. */
    override val autoEmail: Boolean = false

    override val listed = ListedCount()

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val cards = LinkedHashMap<String, Item>()

        try {
            for (item in parseList(client.get(LIST_URL, mapOf("category" to CATEGORY)))) {
                if (!Dates.isOlderThan(item.postedAt, MAX_AGE_DAYS)) cards.putIfAbsent(item.externalId, item)
            }
        } catch (error: BlockedException) {
            error.partialItems = cards.values.toList()
            throw error
        } finally {
            listed.add(cards.size)
        }

        return enrich(cards.values.toList(), store)
    }

    /**
     * Opens the page of each new project, the ones whose title and brief
     * already name the stack first: the skills tagged there often name it
     * when the brief does not. A project that has closed is dropped.
     */
    private fun enrich(cards: List<Item>, store: Store): List<Item> {
        val checked = store.checked.ids(KEY)
        val wanted = cards
            .filter { it.externalId !in checked && store.findByExternalId(KEY, it.externalId) == null }
            .sortedByDescending { Freelance.fits("${it.title}\n${it.excerpt.orEmpty()}") }
            .take(MAX_DETAILS)
        val result = LinkedHashMap<String, Item?>()
        for (card in cards) result[card.externalId] = card
        fun kept() = result.values.filterNotNull()

        for (card in wanted) {
            try {
                val detail = parseDetail(client.get(card.url)) ?: continue
                store.checked.mark(KEY, card.externalId)
                // A closed project is dropped; an open one gets its page's facts.
                result[card.externalId] = if (detail.open) apply(card, detail) else null
            } catch (error: BlockedException) {
                // A page that did not load keeps its card as listed.
                if (!error.transport) {
                    error.partialItems = kept()
                    throw error
                }
            }
        }

        val closed = result.values.count { it == null }
        Log.info("$KEY: ${cards.size} listed, ${wanted.size} opened, $closed closed")
        return kept()
    }

    /** What a project page adds to its listing card. */
    data class Detail(
        val open: Boolean,
        val budget: String?,
        val duration: String?,
        val skills: List<String>,
        val description: String?,
    )

    companion object {
        const val KEY = "mostaql"

        private const val LIST_URL = "https://mostaql.com/projects"

        /** Mostaql's programming category: web, apps, backend and the rest of development. */
        const val CATEGORY = "development"

        /** Every 30 minutes: on a freelance board the first offers are read first. */
        const val EVERY_MS = 30 * 60_000L

        /** Project pages opened a run; the rest wait for the next one. */
        const val MAX_DETAILS = 8

        /** Projects stay open for days, but a week-old one has its freelancer. */
        private const val MAX_AGE_DAYS = 3

        private val ID_RE = Regex("""/project/(\d+)""")

        /** Every project row on a listing page, newest first. A page without rows is no projects. */
        fun parseList(page: String): List<Item> {
            val items = LinkedHashMap<String, Item>()

            // Rows are cut out of the ~175 KB page first, so no DOM of the whole page is built.
            for (chunk in page.split("<tr class=\"project-row\"").drop(1)) {
                val row = Jsoup.parseBodyFragment("<table><tr" + chunk.substringBefore("</tr>") + "</tr></table>")
                val link = row.selectFirst("h2 a") ?: continue
                val url = link.attr("href").ifEmpty { null } ?: continue
                val id = ID_RE.find(url)?.groupValues?.get(1) ?: continue
                val title = Html.clean(link.text()) ?: continue

                val brief = Html.clean(row.selectFirst(".project__brief")?.text())
                val offers = row.select("ul.project__meta li").map { it.text() }
                    .firstNotNullOfOrNull { Regex("""(\d+)\s*عرض""").find(it)?.groupValues?.get(1) }

                items.putIfAbsent(
                    id,
                    Item(
                        source = KEY,
                        externalId = id,
                        title = title,
                        url = url,
                        company = Html.clean(row.selectFirst("ul.project__meta bdi")?.text()),
                        board = "Mostaql",
                        market = Freelance.market("Mostaql"),
                        workplace = "Remote",
                        remote = true,
                        postedAt = posted(row.selectFirst("time[datetime]")?.attr("datetime")),
                        keyword = Freelance.stack("$title\n${brief.orEmpty()}").joinToString(", ").ifEmpty { null },
                        excerpt = listOfNotNull(offers?.let { "📨 $it offers so far" }, brief).joinToString("\n").ifEmpty { null },
                    ),
                )
            }

            return items.values.toList()
        }

        /** A project page's status, budget, duration, skills and brief; null when it is not a project page. */
        fun parseDetail(page: String): Detail? {
            if (page.isBlank()) return null
            val doc = Jsoup.parse(page)
            val meta = doc.select("div.meta-row").associate { row ->
                Html.clean(row.selectFirst(".meta-label")?.text()).orEmpty() to Html.clean(row.selectFirst(".meta-value")?.text())
            }
            if (meta.isEmpty()) return null

            return Detail(
                // "مفتوح" is open; "مغلق", "مكتمل", "قيد التنفيذ" are not.
                open = meta["حالة المشروع"]?.contains("مفتوح") ?: true,
                budget = Freelance.budget(meta["الميزانية"]),
                duration = meta["مدة التنفيذ"],
                skills = doc.select("ul.skills li bdi").mapNotNull { Html.clean(it.text()) },
                description = Html.clean(
                    (doc.selectFirst("#projectDetailsTab .text-wrapper-div") ?: doc.selectFirst(".carda__content"))?.wholeText(),
                ),
            )
        }

        /** The card with its page's facts: the budget as its salary line, the rest in the excerpt. */
        fun apply(card: Item, detail: Detail): Item {
            val offers = card.excerpt?.lineSequence()?.firstOrNull { it.startsWith("📨") }
            val brief = card.excerpt?.lineSequence()?.filterNot { it.startsWith("📨") }?.joinToString("\n")?.ifEmpty { null }
            val excerpt = listOfNotNull(
                detail.duration?.let { "⏱ Delivery: $it" },
                offers,
                detail.skills.takeIf { it.isNotEmpty() }?.let { "🏷 ${it.joinToString(" · ")}" },
                detail.description ?: brief,
            ).joinToString("\n").take(4000).ifEmpty { null }
            val stack = Freelance.stack(listOfNotNull(card.title, excerpt).joinToString("\n")).joinToString(", ")
            return card.copy(
                excerpt = excerpt,
                salary = detail.budget ?: card.salary,
                keyword = stack.ifEmpty { card.keyword },
            )
        }

        /** Mostaql prints UTC as "2026-11-06 18:20:43". */
        fun posted(value: String?): String? {
            val text = value?.trim()?.takeIf { it.length >= 19 } ?: return null
            return Dates.absoluteToIso(text.substring(0, 19).replace(' ', 'T') + "Z")
        }
    }
}
