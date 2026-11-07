package com.jobcrawler.app

/** One posting, as a source produces it and the store keeps it. */
data class Item(
    val source: String,
    val externalId: String,
    var title: String,
    val url: String,
    var company: String? = null,
    var location: String? = null,
    /** The market the search asked for; settles where the posting is. */
    val market: String? = null,
    var postedAt: String? = null,
    val keyword: String? = null,
    var excerpt: String? = null,
    var seniority: String? = null,
    var employmentType: String? = null,
    var workplace: String? = null,
    /** The board an aggregated posting really lives on. */
    var board: String? = null,
    var remote: Boolean = false,
    var contactEmails: List<String> = emptyList(),
    var contactPhones: List<String> = emptyList(),
    val itemType: String = "job",
    /** Years of experience the posting asks for (Extract), null when it does not say. */
    var yearsMin: Int? = null,
    var yearsMax: Int? = null,
    /** The salary the posting names, compacted ("EGP 15k–20k/mo"), or null. */
    var salary: String? = null,
    /** How well it fits, 0–100 (Score); null for rows stored before scoring. */
    var score: Int? = null,
)

/** A stored row: the item plus its delivery state. */
data class Row(
    val id: Long,
    val item: Item,
    val status: String,
    /** When this phone first stored it, ISO-8601. */
    val createdAt: String? = null,
    /** Where the job is; stored, or worked out from the item for older rows. */
    val region: Region = Region.of(item),
    /** The person's verdict: [Feedback.UP], [Feedback.DOWN], or null. */
    val feedback: String? = null,
)

/**
 * The remote refused us, or never answered. Carries whatever was parsed before
 * it, because partial results are independently valid and should still be
 * delivered.
 *
 * [transport] is true when there was no answer at all — DNS, a reset, a
 * timeout. A source walking many URLs skips that one page; a refusal means stop.
 */
class BlockedException(
    message: String,
    var partialItems: List<Item> = emptyList(),
    val transport: Boolean = false,
    /** Set when a person has to act — out of Serper credits, the daily cap — and the pipeline sends an alert. */
    val alert: String? = null,
) : Exception(message)

/** Something a person has to act on, sent to Telegram at most once a day per [kind]. */
data class Alert(val kind: String, val message: String)
