package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Dates
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Item
import com.jobcrawler.app.Limits
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jobgether, a remote-jobs board with a public search API made for crawlers
 * (jobgether.com/astroapi/ai/jobs/docs; robots.txt allows it, two seconds
 * apart). Searched with each keyword for jobs open to Egypt, newest first.
 *
 * The list gives a title, the company and a place ("Anywhere", "EMEA"), not
 * the ad. So a job whose title fits is opened once: its page carries the
 * description and the countries that may apply, and a job that leaves Egypt
 * out is dropped there. Then [RemoteFeeds.judge] decides like any feed.
 * Opened jobs are remembered; a run opens at most [MAX_DETAILS], the rest
 * wait for the next.
 */
class Jobgether(
    override val client: GuestClient = GuestClient("Jobgether", headers = mapOf("Accept" to "application/json, text/html"), delayMs = 2_000L),
    private val cadence: Cadence = Cadence(KEY, Limits.SLOW_SOURCE_EVERY_MS),
) : Source {
    override val key = KEY

    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = cadence.isDue(store)

    /** What the remote listed this run; see [Source.listed]. */
    override val listed = ListedCount()

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val checked = store.checked.ids(KEY)
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        val count = RemoteFeeds.Companion.Counts()
        val cards = LinkedHashMap<String, Card>()

        try {
            for (keyword in queries(keywords)) {
                if (client.isStopped()) break
                val listed = try {
                    parseList(client.get(API_URL, params(keyword)))
                } catch (error: BlockedException) {
                    if (!error.transport) throw error
                    Log.warn("Jobgether: \"$keyword\" unreachable: ${error.message}")
                    continue
                } catch (error: Exception) {
                    Log.warn("Jobgether: \"$keyword\" did not parse: ${error.javaClass.simpleName}: ${error.message}")
                    continue
                }
                count.listed += listed.size
                for (card in listed) cards.putIfAbsent(card.id, card)
            }

            var opened = 0
            for (card in cards.values) {
                if (client.isStopped()) break
                val id = "jobgether:${card.id}"
                if (id in checked) { count.seen += 1; continue }
                if (Dates.isOlderThan(card.postedAt, MAX_AGE_DAYS)) { count.old += 1; continue }
                // Settled from the list alone, without opening the page.
                val early = earlyReject(card)
                if (early != null) {
                    store.checked.mark(KEY, id)
                    count.judged += 1
                    rejected[early] = (rejected[early] ?: 0) + 1
                    continue
                }
                if (opened >= MAX_DETAILS) continue
                opened += 1
                val page = try {
                    client.get(card.url)
                } catch (error: BlockedException) {
                    if (!error.transport) throw error
                    continue
                }
                store.checked.mark(KEY, id)
                count.judged += 1
                val detail = parseOffer(page)
                if (detail.countries.isNotEmpty() && "EG" !in detail.countries) {
                    rejected["region-locked"] = (rejected["region-locked"] ?: 0) + 1
                    continue
                }
                RemoteFeeds.tally(RemoteFeeds.judge(asJob(card, detail), KEY), kept, rejected)
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.toList()
            throw error
        } finally {
            listed.add(count.listed)
            Log.info(RemoteFeeds.summary("Jobgether", count, kept.size, rejected))
        }
        return kept
    }

    /** One job as the search lists it. [location] is who may apply, in Jobgether's words ("Anywhere", "EMEA"). */
    data class Card(
        val id: String,
        val title: String,
        val company: String?,
        val url: String,
        val location: String?,
        val remote: String?,
        val contractType: String?,
        val experience: String?,
        val postedAt: String?,
    )

    /** What the job's own page adds: the ad, and the ISO codes of the countries that may apply (empty when it does not say). */
    data class Offer(val description: String, val countries: Set<String>, val postedAt: String?, val company: String?)

    companion object {
        const val KEY = "jobgether"
        const val API_URL = "https://jobgether.com/api/v1/jobs"
        private const val MAX_AGE_DAYS = 14
        private const val MAX_DETAILS = 15

        /* The CV's stack, when the person's keywords are empty. */
        val DEFAULT_QUERIES = listOf("Backend Developer", "PHP Developer", "Laravel Developer", "Node.js Developer", "Full Stack Developer")

        /** The person's keywords, each once (case aside). */
        fun queries(keywords: List<String>): List<String> =
            keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.ifEmpty { DEFAULT_QUERIES }

        /*
         * "egypt" also brings the worldwide and EMEA jobs Egypt may take.
         * An unknown parameter is ignored silently (it returns the default
         * US feed), so these names are exactly what the docs give.
         */
        fun params(keyword: String): Map<String, Any?> = mapOf(
            "keyword" to keyword,
            "locations" to "egypt",
            "sort" to "date",
            "limit" to 25,
            "page" to 1,
        )

        /** Why a listed job is dropped before its page is opened, or null to open it. */
        fun earlyReject(card: Card): String? {
            val type = card.contractType.orEmpty()
            val item = Item(source = KEY, externalId = card.id, title = card.title, url = card.url, location = card.location)
            return when {
                !AbroadJobs.titleFits(item) -> "title"
                type.contains("intern", ignoreCase = true) -> "internship"
                type.contains("part", ignoreCase = true) -> "part-time"
                else -> null
            }
        }

        fun parseList(body: String): List<Card> = RemoteFeeds.objects(JSONObject(body).optJSONArray("jobs")).mapNotNull { job ->
            Card(
                id = clean(job.optString("id")) ?: return@mapNotNull null,
                title = clean(Html.decode(job.optString("title"))) ?: return@mapNotNull null,
                company = clean(job.optString("company")),
                url = clean(job.optString("url")) ?: return@mapNotNull null,
                location = clean(job.optString("location")),
                remote = clean(job.optString("remote")),
                contractType = clean(job.optString("contractType")),
                experience = clean(job.optString("experience")),
                postedAt = Dates.absoluteToIso(job.optString("postedAt")),
            )
        }

        private val LD_JSON = Regex("""<script[^>]*application/ld\+json[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)

        /** The job page's JobPosting JSON-LD; an empty [Offer] when the page has none. */
        fun parseOffer(page: String): Offer {
            for (match in LD_JSON.findAll(page)) {
                val json = runCatching { JSONObject(match.groupValues[1].trim()) }.getOrNull() ?: continue
                if (json.optString("@type") != "JobPosting") continue
                val countries = when (val places = json.opt("applicantLocationRequirements")) {
                    is JSONArray -> RemoteFeeds.objects(places).mapNotNull { clean(it.optString("name"))?.uppercase() }
                    is JSONObject -> listOfNotNull(clean(places.optString("name"))?.uppercase())
                    else -> emptyList()
                }.toSet()
                return Offer(
                    description = Html.strip(json.optString("description")),
                    countries = countries,
                    postedAt = Dates.absoluteToIso(json.optString("datePosted")),
                    company = clean(json.optJSONObject("hiringOrganization")?.optString("name")),
                )
            }
            return Offer("", emptySet(), null, null)
        }

        /** The card and its page as a feed job. Egypt being on the page's list is said in the place, so the lane check sees it. */
        fun asJob(card: Card, offer: Offer): RemoteFeeds.Job {
            val remote = card.remote?.contains("remote", ignoreCase = true) != false
            val restriction = listOfNotNull("Egypt".takeIf { "EG" in offer.countries }, card.location).joinToString(" · ").ifEmpty { null }
            val facts = listOfNotNull(card.contractType?.let { "Contract: $it" }, card.experience?.let { "Experience: $it" })
            return RemoteFeeds.Job(
                feed = "jobgether",
                id = card.id,
                title = card.title,
                company = offer.company ?: card.company,
                url = card.url,
                location = null,
                restriction = restriction,
                description = (facts + offer.description).filter { it.isNotBlank() }.joinToString("\n"),
                postedAt = offer.postedAt ?: card.postedAt,
                remote = remote,
            )
        }

        private fun clean(value: String?): String? = Html.clean(value)?.takeIf { it != "null" }
    }
}
