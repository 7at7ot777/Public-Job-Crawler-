package com.jobcrawler.app

import com.jobcrawler.app.sources.LinkedInPosts
import java.security.MessageDigest

/**
 * A LinkedIn post the person hands the app — shared from the LinkedIn app or
 * pasted — turned into a job that can be emailed from the review screen.
 *
 * The link is opened as the crawler opens posts, logged out. LinkedIn often
 * answers that with its sign-in page, so whatever text came with the link is
 * read too, and when neither has the post's words the person is asked for
 * them: only they can see the post.
 *
 * No filters: the person chose this post, so it is kept whatever it says.
 */
object PostIntake {
    /** What reading the input came to. */
    sealed class Outcome {
        /** The post's words were read; [Item.contactEmails] may still be empty. */
        data class Read(val item: Item) : Outcome()

        /** Only the link, and LinkedIn would not show the post: the text is needed. */
        data class Walled(val author: String?, val postedAt: String?) : Outcome()

        /** Neither a post link nor enough text to be a post. */
        object NotAPost : Outcome()
    }

    /** Marks the rows added here, so a run never takes them for its own finds. */
    const val NOTE = "added by hand"

    // Shorter than this, text that came with a link is the share sheet's own line, not the post.
    private const val MIN_TEXT = 40
    private const val TITLE_MAX = 140

    private val linkRe = Regex(
        "https?://(?:[a-z]+\\.)?(?:linkedin\\.com/(?:posts|feed/update)/[^\\s]+|lnkd\\.in/[^\\s]+)",
        RegexOption.IGNORE_CASE,
    )

    /** The post's link in what was shared or pasted, without tracking parameters. */
    fun findLink(input: String): String? =
        linkRe.find(input)?.value?.substringBefore('?')?.trimEnd('.', ',', ')', ']', '/')

    /** The input with its link taken out, when what is left reads as a post. */
    fun textOf(input: String, link: String? = findLink(input)): String? {
        val text = (if (link == null) input else linkRe.replace(input, " "))
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return text.takeIf { it.length >= MIN_TEXT }
    }

    /**
     * Reads the input, opening the link when there is one. Blocking: call off
     * the main thread.
     */
    fun read(input: String, open: (String) -> String = { GuestClient("LinkedIn").get(it) }): Outcome {
        val link = findLink(input)
        val pasted = textOf(input, link)
        if (link == null && pasted == null) return Outcome.NotAPost

        val post = link?.let { url ->
            runCatching { LinkedInPosts.parsePost(open(url)) }
                .onFailure { Log.warn("Add a post: could not open $url: ${it.message}") }
                .getOrNull()
        }

        // The pasted text is what the person saw; the page's may be a cut-down preview of it.
        val fetched = post?.text
        val text = when {
            pasted == null -> fetched
            fetched == null || pasted.contains(fetched) -> pasted
            fetched.contains(pasted) -> fetched
            else -> "$pasted\n\n$fetched"
        }
        if (text == null) return Outcome.Walled(post?.author ?: link?.let { LinkedInPosts.authorFromUrl(it) }, post?.postedAt)

        return Outcome.Read(toItem(link, text, post?.author, post?.postedAt))
    }

    fun toItem(link: String?, text: String, author: String? = null, postedAt: String? = null): Item {
        val externalId = link?.let { LinkedInPosts.externalIdOf(it) } ?: "hand-" + sha1(link ?: text).take(16)
        return Item(
            source = LinkedInPosts.KEY,
            externalId = externalId,
            itemType = "post",
            title = truncate(text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "LinkedIn post"),
            // A pasted post without its link: LinkedIn's front page is the nearest thing to open.
            url = link ?: "https://www.linkedin.com/feed/",
            company = author ?: link?.let { LinkedInPosts.authorFromUrl(it) },
            excerpt = text,
            postedAt = postedAt ?: link?.let { LinkedInPosts.activityDate(it) },
            contactEmails = Filters.extractEmails(text),
            contactPhones = Filters.extractPhones(text),
        )
    }

    /**
     * Stores the post, or brings an existing row up to date with what was
     * read now: the crawler may have kept only a search snippet with no address.
     *
     * @return the row to show and email from.
     */
    fun save(store: Store, item: Item): Row {
        val (row, created) = store.insertIfNew(item)
        checkNotNull(row) { "the post could not be stored" }

        // Kept off Telegram: the channel is for what the crawler finds.
        if (created) {
            store.markSkipped(row.id, NOTE)
            return row
        }

        val merged = row.item.copy(
            excerpt = listOfNotNull(row.item.excerpt, item.excerpt).maxByOrNull { it.length },
            contactEmails = (row.item.contactEmails + item.contactEmails).distinct(),
            contactPhones = (row.item.contactPhones + item.contactPhones).distinct(),
        )
        store.updateContent(row.id, merged)
        return row.copy(item = merged)
    }

    private fun truncate(value: String): String =
        if (value.length <= TITLE_MAX) value else value.take(TITLE_MAX - 1).trimEnd() + "…"

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
