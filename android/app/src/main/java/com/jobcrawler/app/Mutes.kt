package com.jobcrawler.app

/**
 * Companies and title words the person never wants to see again, set from a
 * job's screen or by long-pressing it in the list.
 *
 * Filters is pure (tests call it with no database), so the list is a
 * snapshot loaded at the start of a run, like [Criteria].
 */
object Mutes {
    enum class Kind(val db: String) {
        COMPANY("company"),
        WORD("word"),
        ;

        companion object {
            fun parse(value: String?) = Kind.entries.firstOrNull { it.db == value }
        }
    }

    /** [value] is normalized (how it is compared); [label] is how the person wrote it. */
    data class Entry(val kind: Kind, val value: String, val label: String, val createdAt: String = "")

    /*
     * Swapped whole by [load] / [replace], never changed in place, so
     * workers reading it in parallel with a reload never see a mix.
     */
    @Volatile
    private var current: List<Entry> = emptyList()

    val all: List<Entry> get() = current

    fun replace(entries: List<Entry>) {
        current = entries
    }

    fun load(store: Store) {
        current = runCatching { store.mutes.all() }.getOrDefault(current)
    }

    /** How a company is compared: the store's fingerprint normalization. */
    fun key(company: String): String = Store.normalize(company) ?: company.trim().lowercase()

    /** Words too common in titles to mean anything on their own: never offered, never penalised. */
    private val GENERIC = setOf(
        "developer", "engineer", "software", "backend", "back", "end", "full", "stack", "web", "junior", "mid",
        "level", "senior", "remote", "the", "and", "for", "with", "job", "jobs", "hiring", "role", "team", "new",
        "egypt", "cairo", "giza", "hybrid", "onsite", "site", "time", "part",
    )

    /** The words of a title worth muting or learning from: folded, three letters or more, not generic. */
    fun titleWords(title: String): List<String> =
        Filters.fold(title).split(Regex("[^\\p{L}\\p{N}+#.]+"))
            .map { it.trim('.', '-') }
            .filter { it.length >= 3 && it !in GENERIC && it.any(Char::isLetter) }
            .distinct()

    /** "muted-company" or "muted-title" when a mute matches, else null. */
    fun reasonFor(item: Item, mutes: List<Entry> = current): String? {
        if (mutes.isEmpty()) return null
        val company = item.company?.let { Store.normalize(it) }
        if (company != null && mutes.any { it.kind == Kind.COMPANY && it.value == company }) return "muted-company"
        val title = Filters.fold(item.title)
        if (mutes.any { it.kind == Kind.WORD && Filters.containsTerm(title, it.value) }) return "muted-title"
        return null
    }
}
