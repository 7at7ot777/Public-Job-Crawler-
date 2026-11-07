package com.jobcrawler.app

import com.jobcrawler.app.sources.AbroadFit
import com.jobcrawler.app.sources.CvStack
import java.time.Duration
import java.time.Instant

/**
 * How well a job fits, 0–100, so the best of a run goes out first and the
 * Jobs screen can sort by it. The filters already decided the job is worth
 * sending; this only ranks what passed.
 *
 * At home (Egypt and the Gulf): the title's stack 30, the stack in the text
 * 25, years in reach 15, freshness 15, remote 5, an address to write to 5,
 * the region 5. Abroad, [AbroadFit] already measures the CV fit and the
 * chance of being taken from Egypt, so its score is used with freshness.
 * Then what the person said: two 👎 on a company or a title word cost it.
 */
object Score {
    /** What the person's verdicts taught: company and word → number of 👎; companies with a 👍. */
    data class Learned(
        val companies: Map<String, Int> = emptyMap(),
        val words: Map<String, Int> = emptyMap(),
        val liked: Set<String> = emptySet(),
    )

    /*
     * Swapped whole, never changed in place: a worker scoring in parallel
     * with a reload sees the old value or the new one, never a mix. Only
     * [reload] sets it.
     */
    @Volatile
    var learned = Learned()
        private set

    private const val DOWNS_TO_LEARN = 2
    private val backendTerms = listOf("backend", "back-end", "back end", "api", "server")

    fun of(item: Item, now: Instant = Instant.now(), learned: Learned = this.learned): Int {
        val raw = if (Mailer.isAbroad(item)) {
            (AbroadFit.score(item) * 0.85 + freshness(item, now)).toInt()
        } else {
            home(item, now)
        }
        return (raw + adjustment(item, learned)).coerceIn(0, 100)
    }

    private fun home(item: Item, now: Instant): Int {
        val title = item.title
        val text = "$title\n${item.excerpt.orEmpty()}"
        val folded = Filters.fold(title)

        val titlePoints = when {
            item.itemType == "post" -> 15
            CvStack.of(title).isNotEmpty() -> 30
            Filters.containsAny(folded, backendTerms) -> 22
            else -> 15
        }
        val stack = minOf(CvStack.of(text).size * 8, 17) + minOf(CvStack.score(text) * 2, 8)
        val years = yearsPoints(item)
        val remote = if (item.remote || Filters.containsTerm(Filters.fold(text), "remote") || item.workplace?.lowercase()?.let { "remote" in it || "hybrid" in it } == true) 5 else 0
        val email = if (item.contactEmails.isNotEmpty()) 5 else 0
        val region = when (Region.of(item)) {
            Region.EGYPT -> 5
            Region.GULF -> 3
            Region.WORLDWIDE -> 2
            Region.FREELANCE -> 3
        }
        return titlePoints + stack + years + freshness(item, now) + remote + email + region
    }

    private fun yearsPoints(item: Item): Int {
        val junior = Filters.containsAny(Filters.fold(item.title), listOf("junior", "jr", "mid", "intermediate"))
        val min = item.yearsMin ?: return if (junior) 15 else 8
        val max = item.yearsMax
        return when {
            min <= 2 || (max != null && max <= 3) || junior -> 15
            min == 3 -> 12
            min == 4 -> 6
            else -> 0
        }
    }

    /** When it was posted (else first seen): a fresh one is worth applying to first. */
    private fun freshness(item: Item, now: Instant): Int {
        val at = item.postedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return 8
        val days = Duration.between(at, now).toHours() / 24.0
        return when {
            days <= 1 -> 15
            days <= 3 -> 11
            days <= 7 -> 7
            days <= 14 -> 3
            else -> 0
        }
    }

    private fun adjustment(item: Item, learned: Learned): Int {
        var points = 0
        val company = item.company?.let { Store.normalize(it) }
        if (company != null) {
            if ((learned.companies[company] ?: 0) >= DOWNS_TO_LEARN) points -= 25
            if (company in learned.liked) points += 5
        }
        val wordPenalty = Mutes.titleWords(item.title).count { (learned.words[it] ?: 0) >= DOWNS_TO_LEARN } * 10
        return points - minOf(wordPenalty, 20)
    }

    /** Counts the 👎 per company and title word, and the companies with a 👍. */
    fun learn(down: List<Item>, up: List<Item> = emptyList()): Learned {
        val companies = mutableMapOf<String, Int>()
        val words = mutableMapOf<String, Int>()
        for (item in down) {
            item.company?.let { Store.normalize(it) }?.let { companies.merge(it, 1, Int::plus) }
            for (word in Mutes.titleWords(item.title)) words.merge(word, 1, Int::plus)
        }
        val liked = up.mapNotNull { it.company?.let { c -> Store.normalize(c) } }.toSet()
        return Learned(companies, words, liked)
    }

    /** At the start of a run and after a verdict: what the store's 👍 and 👎 say now. */
    fun reload(store: Store) {
        learned = runCatching { learn(store.withFeedback(Feedback.DOWN), store.withFeedback(Feedback.UP)) }.getOrDefault(learned)
    }
}
