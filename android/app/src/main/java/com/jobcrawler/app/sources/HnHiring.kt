package com.jobcrawler.app.sources

import com.jobcrawler.app.BlockedException
import com.jobcrawler.app.Filters
import com.jobcrawler.app.GuestClient
import com.jobcrawler.app.Limits
import com.jobcrawler.app.Item
import com.jobcrawler.app.Log
import com.jobcrawler.app.Store
import org.json.JSONObject

/**
 * Hacker News' monthly "Ask HN: Who is hiring?", through its free search API
 * (Algolia). Each top-level comment is one company: "Acme | Backend Engineer
 * | Berlin | REMOTE | VISA", then the ad. Read once a day, newest comments
 * first; each judged once by [RemoteFeeds.judge].
 */
class HnHiring(
    override val client: GuestClient = GuestClient("Hacker News", headers = mapOf("Accept" to "application/json")),
    private val cadence: Cadence = Cadence(KEY, Limits.SLOW_SOURCE_EVERY_MS * 8),
) : Source {
    override val key = KEY

    override val autoEmail: Boolean = true

    override fun isDue(store: Store) = cadence.isDue(store)

    override fun fetch(keywords: List<String>, store: Store): List<Item> {
        cadence.started(store)
        val kept = mutableListOf<Item>()
        val rejected = sortedMapOf<String, Int>()
        var listed = 0
        var seen = 0
        try {
            val story = latestThread(client.get(STORIES_URL)) ?: return emptyList<Item>().also { Log.warn("Hacker News: no Who is hiring thread found") }
            val checked = store.checked.ids(KEY)
            val ads = ads(client.get(COMMENTS_URL.format(story)), story)
            listed = ads.size
            for (ad in ads) {
                if ("hn:${ad.id}" in checked) { seen += 1; continue }
                store.checked.mark(KEY, "hn:${ad.id}")
                RemoteFeeds.tally(RemoteFeeds.judge(ad, KEY), kept, rejected)
            }
        } catch (error: BlockedException) {
            error.partialItems = kept.toList()
            throw error
        } finally {
            Log.info(RemoteFeeds.summary("Hacker News", RemoteFeeds.Companion.Counts(listed, seen, 0, listed - seen), kept.size, rejected))
        }
        return kept
    }

    companion object {
        const val KEY = "hn_hiring"
        private const val STORIES_URL = "https://hn.algolia.com/api/v1/search_by_date?tags=story,author_whoishiring&hitsPerPage=5"
        private const val COMMENTS_URL = "https://hn.algolia.com/api/v1/search_by_date?tags=comment,story_%s&hitsPerPage=300"

        /** The newest "Who is hiring?" story's id; the same account also posts "Who wants to be hired?". */
        fun latestThread(body: String): String? =
            RemoteFeeds.objects(JSONObject(body).optJSONArray("hits"))
                .firstOrNull { it.optString("title").contains("who is hiring", ignoreCase = true) }
                ?.optString("objectID")?.ifEmpty { null }

        private val ROLE = Regex("""\b(engineer|developer|programmer|backend|back-end|full[- ]?stack|swe)\b""", RegexOption.IGNORE_CASE)
        private val VISA = Regex("""\bvisa\b""", RegexOption.IGNORE_CASE)
        private val NO_VISA = Regex("""\bno\s+visa\b|\bvisa:\s*no\b""", RegexOption.IGNORE_CASE)

        /** Each company's ad in the thread: top-level comments only, replies are not ads. */
        fun ads(body: String, story: String): List<RemoteFeeds.Job> =
            RemoteFeeds.objects(JSONObject(body).optJSONArray("hits")).mapNotNull { hit ->
                if (hit.optString("parent_id") != story) return@mapNotNull null
                val id = hit.optString("objectID").ifEmpty { null } ?: return@mapNotNull null
                val html = hit.optString("comment_text")
                val first = Html.clean(Html.decode(Html.detag(html.substringBefore("<p>")))) ?: return@mapNotNull null
                val parts = first.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size < 2) return@mapNotNull null

                val title = parts.drop(1).firstOrNull { ROLE.containsMatchIn(it) } ?: parts[1]
                val place = parts.drop(1).filter { it != title }.joinToString(" · ").ifEmpty { null }
                val remote = Filters.containsAny(Filters.fold(first), listOf("remote"))
                var text = Html.strip(Html.decode(html))
                // "VISA" in the header is the thread's way of saying the company sponsors.
                if (VISA.containsMatchIn(first) && !NO_VISA.containsMatchIn(first)) text += "\nVisa sponsorship available."

                RemoteFeeds.Job(
                    feed = "hn",
                    id = id,
                    title = title.take(120),
                    company = parts[0].take(80),
                    url = "https://news.ycombinator.com/item?id=$id",
                    location = place,
                    restriction = null,
                    description = text,
                    postedAt = hit.optString("created_at").ifEmpty { null },
                    remote = remote,
                )
            }
    }
}
