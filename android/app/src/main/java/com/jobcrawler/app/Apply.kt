package com.jobcrawler.app

import android.content.Context
import com.jobcrawler.app.sources.RelocateMe

/**
 * How the app applies for you: a notification with an Apply button for every
 * new job, and the CV emailed to the address a posting gives — one job at a
 * time, from the review screen. Saudi and UAE jobs are the exception: their
 * CV goes out on its own, from a second Gmail account when one is set.
 *
 * Everything is set on the phone, the Gmail account and its app password
 * included; .env values, when a build has them, are only the first defaults.
 */
data class ApplySettings(
    val notify: Boolean = true,
    val name: String = BuildConfig.APPLY_NAME,
    val phone: String = BuildConfig.APPLY_PHONE,
    val pitch: String = DEFAULT_PITCH,
    /** Domains, addresses or company names never emailed. */
    val neverEmail: List<String> = emptyList(),
    /** The account CVs are sent from, and a Google "app password" for it — never the account's own. */
    val gmail: String = BuildConfig.APPLY_GMAIL,
    val appPassword: String = BuildConfig.APPLY_GMAIL_APP_PASSWORD,
    /** Email Saudi and UAE jobs on their own, without the review screen. */
    val gulfAutoEmail: Boolean = true,
    /** A second account for every job outside Egypt (Saudi/UAE, jobright, Relocate.me); blank sends them from [gmail]. */
    val gulfGmail: String = "",
    val gulfAppPassword: String = "",
    /** Email jobs abroad (jobright, Relocate.me) on their own, from the second account, when one gives an address. */
    val abroadAutoEmail: Boolean = true,
) {
    val mailReady get() = gmail.isNotBlank() && appPassword.isNotBlank()

    /** These settings sending from the second account (Saudi/UAE and abroad): its own when both fields are set, else the main one. */
    val gulfSender: ApplySettings
        get() = if (gulfGmail.isNotBlank() && gulfAppPassword.isNotBlank()) copy(gmail = gulfGmail, appPassword = gulfAppPassword) else this

    companion object {
        private const val PREFS = "apply"

        const val DEFAULT_PITCH =
            "I'm a backend developer working mainly with PHP/Laravel and Node.js, building REST APIs and the databases behind them. " +
                "I also build frontends as an AI-assisted frontend developer."

        /* Earlier defaults: a phone that saved one unedited gets the current default instead. */
        private val OLD_PITCHES = setOf(
            "I'm a backend developer working mainly with PHP/Laravel and Node.js, building REST APIs and the databases behind them.",
        )

        fun load(context: Context): ApplySettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = ApplySettings()
            return ApplySettings(
                notify = prefs.getBoolean("notify", defaults.notify),
                name = prefs.getString("name", null)?.ifBlank { null } ?: defaults.name,
                phone = prefs.getString("phone", null)?.ifBlank { null } ?: defaults.phone,
                pitch = prefs.getString("pitch", null)?.trim()?.takeIf { it.isNotEmpty() && it !in OLD_PITCHES } ?: defaults.pitch,
                neverEmail = Settings.splitList(prefs.getString("neverEmail", null)) ?: emptyList(),
                gmail = prefs.getString("gmail", null)?.ifBlank { null } ?: defaults.gmail,
                appPassword = prefs.getString("appPassword", null)?.ifBlank { null } ?: defaults.appPassword,
                gulfAutoEmail = prefs.getBoolean("gulfAutoEmail", defaults.gulfAutoEmail),
                gulfGmail = prefs.getString("gulfGmail", null) ?: defaults.gulfGmail,
                gulfAppPassword = prefs.getString("gulfAppPassword", null) ?: defaults.gulfAppPassword,
                abroadAutoEmail = prefs.getBoolean("abroadAutoEmail", defaults.abroadAutoEmail),
            )
        }

        fun save(context: Context, settings: ApplySettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("notify", settings.notify)
                // Left by the builds that emailed on their own.
                .remove("autoEmail")
                .putString("name", settings.name)
                .putString("phone", settings.phone)
                .putString("pitch", settings.pitch)
                .putString("neverEmail", settings.neverEmail.joinToString(", "))
                .putString("gmail", settings.gmail)
                .putString("appPassword", settings.appPassword)
                .putBoolean("gulfAutoEmail", settings.gulfAutoEmail)
                .putString("gulfGmail", settings.gulfGmail)
                .putString("gulfAppPassword", settings.gulfAppPassword)
                .putBoolean("abroadAutoEmail", settings.abroadAutoEmail)
                .apply()
        }
    }
}

/**
 * The note that goes with an application: pasted on LinkedIn, or the email's
 * body. Each part has a few wordings, picked by the job, so no two emails a
 * spam filter compares are the same text with a name swapped in — while the
 * same job always gets the same note.
 */
object CoverNote {
    private val greetings = listOf("Hello %s,", "Dear %s,", "Hi %s,")
    private val openings = listOf(
        "I'd like to apply for %s.",
        "I'm writing to apply for %s.",
        "I came across %s and would like to be considered.",
    )
    private val closingsWithCv = listOf(
        "My CV is attached, and I'd be glad to talk at a time that suits you.",
        "I've attached my CV and would welcome the chance to discuss the role.",
        "Please find my CV attached; I'm happy to arrange a call whenever convenient.",
    )
    private val closings = listOf(
        "I'd be glad to talk at a time that suits you.",
        "I'd welcome the chance to discuss the role.",
        "I'm happy to arrange a call whenever convenient.",
    )
    private val signOffs = listOf("Best regards,", "Kind regards,", "Thank you for your time,")

    /** Where the job was seen, as a person would say it. */
    fun seenOn(item: Item): String = when (item.source) {
        "linkedin_jobs", "linkedin_posts", "gulf_jobs", "gulf_posts" -> "LinkedIn"
        "wuzzuf" -> "Wuzzuf"
        "tanqeeb" -> item.board ?: "Tanqeeb"
        "drjob" -> "Dr.Job"
        "workable" -> "Workable"
        "glassdoor" -> "Glassdoor"
        else -> "your careers page"
    }

    /** What an employer abroad has to know first: where the person is, and what the job would take. */
    fun situation(item: Item): String? {
        val remote = "I'm based in Cairo, Egypt (UTC+2/+3), with full overlap with European working hours, and work remotely."
        if (item.source == RelocateMe.KEY) {
            val country = RelocateMe.countryOf(item.market) ?: return remote
            return "I'm based in Cairo, Egypt, and would be glad to relocate to $country with the visa sponsorship the role offers."
        }
        val market = item.market ?: return null
        if (item.source == com.jobcrawler.app.sources.AbroadJobs.KEY && market.startsWith("✈️")) {
            val place = com.jobcrawler.app.sources.AbroadFit.regionOf(item.location)?.substringAfter(' ')?.let { if (it == "UK") "the UK" else it }
            if (place != null) return "I'm based in Cairo, Egypt, and would be glad to relocate to $place with the visa sponsorship the role offers."
        }
        return when {
            market.startsWith("🌍") -> remote
            market.startsWith("✈️") -> "I'm based in Cairo, Egypt, and would need visa sponsorship to relocate for the role."
            market == Gulf.SAUDI -> "I'm based in Cairo, Egypt, and ready to relocate to Saudi Arabia."
            market == Gulf.UAE -> "I'm based in Cairo, Egypt, and ready to relocate to the UAE."
            else -> null
        }
    }

    /**
     * The email's subject, from the job's own title: a board's title as is, a
     * hiring post's role as its text names it. When the posting says what the
     * subject must be ("Subject: PHP Dev - Cairo", "with Laravel in the
     * subject"), that is honoured first — it is what a recruiter's inbox
     * filter looks for.
     */
    fun subject(item: Item): String {
        val role = if (item.itemType == "post") Roles.roleIn(item) else item.title.trim().ifEmpty { null }
        val base = role?.let { "$it application" }
        val asked = Roles.requestedSubject(item)
        return when {
            asked == null -> base ?: "Application for the role in your LinkedIn post"
            asked.exact -> asked.text
            base == null -> "${asked.text} application"
            base.contains(asked.text, ignoreCase = true) -> base
            else -> "${asked.text} - $base"
        }
    }

    /** A stable pick per job: the same job always gets the same wording. */
    fun variantOf(item: Item): Int = (item.source + ":" + item.externalId).hashCode().let { if (it < 0) -(it + 1) else it }

    fun text(item: Item, profile: ApplySettings, withCv: Boolean, variant: Int = variantOf(item)): String = buildString {
        val team = item.company?.takeIf { item.itemType != "post" }?.let { "$it hiring team" } ?: "hiring team"
        val where = seenOn(item)
        val role = when {
            item.itemType == "post" -> Roles.roleIn(item)?.let { "the $it role in your LinkedIn post" } ?: "the role in your LinkedIn post"
            where == "your careers page" -> "the ${item.title} position on your careers page"
            else -> "the ${item.title} position you posted on $where"
        }

        fun <T> pick(list: List<T>, step: Int) = list[(variant / step) % list.size]

        appendLine(pick(greetings, 1).format(team.replaceFirstChar(Char::uppercaseChar)))
        appendLine()
        appendLine(pick(openings, 3).format(role) + " " + profile.pitch.trim())
        appendLine()
        situation(item)?.let {
            appendLine(it)
            appendLine()
        }
        appendLine(pick(if (withCv) closingsWithCv else closings, 9))
        appendLine()
        appendLine(pick(signOffs, 27))
        listOf(profile.name, profile.phone, profile.gmail).filter { it.isNotBlank() }.forEach { appendLine(it) }
    }.trimEnd()
}

/**
 * The job title a hiring post names, and the subject it asks for. A post has
 * no title field — its "title" is just its first line ("🚀 We're hiring!") —
 * so the role is read from the text. Pure, so it is tested without a phone.
 */
object Roles {
    private const val NOUNS = "Developer|Engineer|Programmer|Architect|Specialist|Analyst|Designer|Consultant|Administrator|Tester|Intern|Scientist|Lead|Manager"

    /** "Senior Backend Engineer", "Node.js Developer", "Mid-Level PHP Developers": Title Case words ending in a role. */
    private val titled = Regex("((?:(?:[A-Z][\\w.+#/-]*|\\.NET)[ \\t]+){1,4})($NOUNS)s?\\b")

    /** "hiring a Laravel developer", "Position: senior php engineer", "looking for 2 backend developers". */
    private val cued = Regex(
        "(?:(?:hiring|looking for|seeking|searching for|in need of)\\b(?:[ \\t]+for)?[ \\t]*[:\\-–]?|(?:vacancy|opening|positions?|role|job title)[ \\t]*[:\\-–])[ \\t]*" +
            "([^\\n.,!:;|()\\[\\]?]{1,60}?\\b(?:${NOUNS.lowercase()})s?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that open a phrase but are not part of the title. */
    private val filler = setOf(
        "a", "an", "the", "our", "one", "two", "three", "new", "hiring", "we're", "we", "are", "now", "urgent", "urgently",
        "join", "talented", "experienced", "skilled", "passionate", "motivated", "strong", "great", "dedicated", "qualified",
        "is", "for", "multiple", "several", "open", "as",
    )

    /** A phrase holding one of these is a sentence, not a title. */
    private val notTitle = Regex("\\b(you|who|someone|people|candidates?|to|will|can|that|which|us)\\b", RegexOption.IGNORE_CASE)

    private val upper = mapOf(
        "php" to "PHP", "api" to "API", "qa" to "QA", "sre" to "SRE", "ui" to "UI", "ux" to "UX", "ai" to "AI",
        "ml" to "ML", "aws" to "AWS", "sql" to "SQL", ".net" to ".NET", "ios" to "iOS", "it" to "IT", "erp" to "ERP",
    )

    fun roleIn(item: Item): String? {
        val text = listOfNotNull(item.title, item.excerpt).joinToString("\n")
        return titled.findAll(text).firstNotNullOfOrNull { clean(it.groupValues[1] + it.groupValues[2]) }
            ?: cued.findAll(text).firstNotNullOfOrNull { clean(it.groupValues[1]) }
    }

    private fun clean(raw: String): String? {
        val words = raw.trim().split(Regex("\\s+")).toMutableList()
        while (words.isNotEmpty() && (words.first().lowercase() in filler || words.first().all { it.isDigit() || it == '+' })) words.removeAt(0)
        if (words.isEmpty() || words.size > 6) return null
        val phrase = words.joinToString(" ")
        // "requires a strong backend developer": an article or adjective mid-phrase means prose.
        if (notTitle.containsMatchIn(phrase) || words.any { it.lowercase() in filler }) return null
        // A bare "Lead" or "Manager" is too vague to name a job.
        if (words.size == 1 && words[0].lowercase().trimEnd('s') in setOf("lead", "manager")) return null
        return words.mapIndexed { i, word ->
            val singular = if (i == words.lastIndex) Regex("(?i)($NOUNS)s$").replace(word) { it.groupValues[1] } else word
            upper[singular.lowercase()] ?: singular.replaceFirstChar(Char::uppercaseChar)
        }.joinToString(" ")
    }

    /** What the posting says the subject must be; [exact] when it gives the whole line, not a word to include. */
    data class Requested(val text: String, val exact: Boolean)

    private val subjectLine = Regex(
        "subject(?:[ \\t]+line)?[ \\t]*(?::|-|should be|must be)[ \\t]*[\"“']?([^\"”'\\n]{2,80}?)[\"”']?[ \\t]*(?:\\n|$|\\.(?:\\s|$))",
        RegexOption.IGNORE_CASE,
    )
    private val subjectWord = Regex(
        "\\b(?:with|mention|mentioning|put|putting|write|writing|include|including|add|adding|use|using)[ \\t]+[\"“']?((?:[^\\s\"”'.,]+[ \\t]+){0,3}[^\\s\"”'.,]+)[\"”']?[ \\t]+(?:in|as)[ \\t]+(?:the[ \\t]+|your[ \\t]+)?(?:e-?mail[ \\t]+|mail[ \\t]+)?subject",
        RegexOption.IGNORE_CASE,
    )

    fun requestedSubject(item: Item): Requested? {
        val text = listOfNotNull(item.title, item.excerpt).joinToString("\n")
        subjectLine.find(text)?.let { return Requested(it.groupValues[1].trim(), exact = true) }
        subjectWord.find(text)?.let { match ->
            val words = match.groupValues[1].trim()
            if (words.lowercase() !in setOf("it", "this", "that", "the job title", "the role", "the position", "job title", "position", "role")) {
                return Requested(words, exact = false)
            }
        }
        return null
    }
}

/**
 * Which addresses a posting may be emailed at. Pure, so the rules are tested
 * without a phone: the loose extraction the filters use ("hr (at) acme dot
 * com") is normalised, then anything not plainly an inbox for people is
 * dropped.
 */
object Recipients {
    private val strict = Regex("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$")

    // Mailboxes nobody reads, or that are the wrong door for a CV.
    private val unwanted = Regex("^(no-?reply|do-?not-?reply|noreply|mailer-daemon|postmaster|abuse|privacy|legal|press|media|sales|billing|support|help|security)([._+-]|@)")

    fun normalise(raw: String): String? {
        val address = raw.lowercase()
            .replace(Regex("\\s*(\\(at\\)|\\[at\\]|\\s+at\\s+)\\s*"), "@")
            .replace(Regex("\\s*(\\(dot\\)|\\[dot\\])\\s*"), ".")
            .replace(Regex("\\s+"), "")
            .trim('.', ',', ';', ':')
        return address.takeIf { strict.matches(it) }
    }

    /**
     * @param recentlyEmailed whether an address was written to within the
     *        cool-down; asked per address so the store does the looking up.
     */
    fun eligible(
        item: Item,
        profile: ApplySettings,
        recentlyEmailed: (String) -> Boolean,
    ): List<String> {
        val blocked = profile.neverEmail.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val company = item.company?.lowercase().orEmpty()
        if (blocked.any { company.isNotEmpty() && company.contains(it) }) return emptyList()

        return item.contactEmails.mapNotNull(::normalise).distinct().filter { address ->
            val domain = address.substringAfter('@')
            !unwanted.containsMatchIn(address) &&
                address != profile.gmail.lowercase() &&
                blocked.none { it == address || it == domain || domain.endsWith(".$it") } &&
                !recentlyEmailed(address)
        }
    }
}
