package com.jobcrawler.app

/**
 * Post-crawl filtering, ported from src/filters.js with the lists from
 * src/config.js: the job rules, and the hiring-post rules the LinkedIn posts
 * source needs.
 *
 * An item is read across title + company + location + excerpt, because a job
 * card carries almost nothing while a detail page carries everything.
 */
object Filters {
    val DEFAULT_TECH = listOf(
        "laravel", "node", "nodejs", "node.js", "php", "backend", "back-end", "back end",
        // Go, never bare "go": that would match "go live" and "years ago".
        "golang", "go lang", "go developer", "go engineer", "go programming", "go language",
        // Application support ads rarely name the stack; the title rule keeps
        // this off helpdesk roles.
        "support",
    )

    /*
     * Every place a posting may name instead of "Egypt". Matched as whole
     * words; places that are also first names or everyday words are listed
     * only in their "el / al / city" form.
     */
    val DEFAULT_EGYPT_PLACES = listOf(
        "egypt", "cairo", "new cairo", "fifth settlement", "5th settlement", "first settlement",
        "1st settlement", "tagamoa", "el tagamoa", "el rehab", "al rehab", "rehab city", "madinaty",
        "el shorouk", "shorouk city", "obour", "administrative capital", "new administrative capital",
        "nasr city", "heliopolis", "masr el gedida", "maadi", "katameya", "mokattam", "zamalek",
        "10th of ramadan", "giza", "dokki", "mohandessin", "agouza", "al haram", "el haram",
        "6th of october", "6 october", "october city", "sheikh zayed", "smart village",
        "alexandria", "smouha", "borg el arab", "mansoura", "tanta", "zagazig", "ismailia",
        "port said", "suez", "damietta", "assiut", "minya", "sohag", "beni suef", "fayoum", "luxor",
        "aswan", "hurghada", "el gouna", "sharm el sheikh",
        "مصر", "القاهرة", "القاهرة الجديدة", "التجمع", "التجمع الخامس", "الرحاب", "مدينتي",
        "الشروق", "العبور", "العاصمة الإدارية", "مدينة نصر", "مصر الجديدة", "المعادي", "المقطم",
        "الزمالك", "الجيزة", "الدقي", "المهندسين", "6 أكتوبر", "السادس من أكتوبر", "الشيخ زايد",
        "الاسكندرية", "الإسكندرية", "المنصورة", "طنطا", "الزقازيق",
    )

    /* Where a Saudi or UAE hiring post says it is, as whole words. */
    val DEFAULT_GULF_PLACES = listOf(
        "saudi", "saudi arabia", "ksa", "riyadh", "jeddah", "dammam", "khobar", "al khobar", "dhahran",
        "mecca", "makkah", "medina", "madinah", "neom",
        "uae", "u.a.e", "united arab emirates", "emirates", "dubai", "abu dhabi", "sharjah", "ajman",
        "ras al khaimah", "fujairah", "al ain",
        "السعودية", "سعودية", "الرياض", "جدة", "الدمام", "الخبر", "الإمارات", "الامارات", "دبي", "أبوظبي",
        "ابوظبي", "الشارقة",
    )

    val DEFAULT_SENIORITY_EXCLUDE = listOf(
        "senior", "sr", "lead", "principal", "staff", "staff engineer", "founding", "head of",
        "director", "manager", "architect", "vp", "chief", "expert",
    )

    val DEFAULT_TITLE_TERMS = listOf(
        "laravel", "php", "symfony", "codeigniter", "backend", "back-end", "back end",
        "server side", "server-side", "node", "nodejs", "node.js", "nestjs", "express",
        "expressjs", "full stack", "full-stack", "fullstack", "mern", "mean stack",
        "software engineer", "software developer", "web developer", "web engineer",
        "application developer", "api developer", "api engineer", "application support",
        "golang", "go developer", "go engineer", "go backend",
    )

    val DEFAULT_BLOCKED_LOCATIONS = listOf(
        "india", "bengaluru", "bangalore", "hyderabad", "mumbai", "pune", "chennai", "delhi",
        "noida", "gurugram", "gurgaon", "kolkata", "pakistan", "karachi", "lahore", "islamabad",
        "rawalpindi", "nigeria", "lagos", "abuja",
    )

    // The lists above as the person left them in Settings: the defaults until edited.
    val tech get() = Criteria[Criteria.Kind.TECH]
    val egyptPlaces get() = Criteria[Criteria.Kind.EGYPT_PLACES]
    val seniorityExclude get() = Criteria[Criteria.Kind.TOO_SENIOR]
    val titleTerms get() = Criteria[Criteria.Kind.TITLES]
    val blockedLocations get() = Criteria[Criteria.Kind.BLOCKED_PLACES]
    val gulfPlaces get() = Criteria[Criteria.Kind.GULF_PLACES]

    /* Phrases that make a posting one a candidate in Egypt cannot take. */
    val DEFAULT_INELIGIBLE = listOf(
        // Work rights and visas.
        "authorized to work in", "authorised to work in", "legally authorized",
        "legally authorised", "work authorization", "work authorisation", "eligible to work in",
        "right to work in", "no visa sponsorship", "unable to sponsor",
        "not able to sponsor", "will not sponsor", "cannot sponsor", "without sponsorship",
        "us citizen", "u.s. citizen", "us citizenship", "u.s. citizenship", "green card",
        "security clearance", "clearance required",
        // Already living there.
        "must be based in", "must reside in", "must be located in", "must live in", "must be a resident",
        "currently in ksa", "currently in saudi", "currently in the uae", "currently in uae", "currently in dubai",
        "currently residing in", "currently living in",
        "inside the kingdom", "within the kingdom", "inside ksa", "inside saudi arabia", "inside the uae",
        "local candidates only", "locals only", "residents only",
        "must be in saudi arabia", "must be in ksa", "must be in the uae", "must be in dubai",
        "must be in riyadh", "must be in abu dhabi",
        "locally available", "must be locally available", "locally sponsored",
        // Iqama and visa status in the Gulf. Not "residence visa" or "iqama transfer": ads offer those.
        "transferable iqama", "iqama transferable", "transferrable iqama", "valid iqama",
        "iqama required", "iqama is required", "iqama mandatory", "must have iqama", "must have a valid iqama",
        "valid residence permit", "residence permit required", "valid residency", "residency required",
        "must have residency", "resident visa holder",
        "on own visa", "on your own visa", "own sponsorship", "valid uae visa",
        // Nationality.
        "saudi national", "saudi nationals", "saudis only", "saudi only", "saudization", "saudisation",
        "uae national", "uae nationals", "emirati", "emiratis only", "emiratisation", "emiratization",
        "qatari national", "qatari nationals", "gcc nationals", "gcc national", "nationals only",
        // The same, in Arabic.
        "للسعوديين فقط", "سعوديين فقط", "سعودي الجنسية", "سعودية الجنسية", "سعودة", "للمواطنين فقط",
        "مواطنين فقط", "إماراتي الجنسية", "اماراتي الجنسية", "مقيم في المملكة", "مقيمين في المملكة",
        "داخل المملكة", "إقامة قابلة للنقل", "اقامة قابلة للنقل", "إقامة سارية", "اقامة سارية",
        "إقامة مطلوبة", "اقامة مطلوبة", "يشترط وجود إقامة", "يشترط الإقامة",
        // Not a job.
        "co-founder", "cofounder",
    )

    /* Edited in Settings as "Can't apply"; the defaults until then. */
    val ineligible get() = Criteria[Criteria.Kind.INELIGIBLE]

    /**
     * Whether the text says the person cannot take the job. A residency
     * phrase that points at Egypt ("must be based in Cairo", "currently
     * residing in Egypt") is where the person already is, so it does not count.
     */
    fun cannotApply(text: String): Boolean = ineligible.any { term ->
        val needle = fold(term).trim()
        needle.isNotEmpty() && termRegex(needle).findAll(text).any { match ->
            val after = text.substring(match.range.last + 1, minOf(text.length, match.range.last + 1 + 40))
            !containsAny(after, egyptPlaces)
        }
    }

    /*
     * Sources whose own category already says the job is tech, so neither
     * the title rule nor the stack rule applies: Recruitera's "IT/Software
     * Development" holds QA and data roles too, and the server sends those.
     */
    private val categorised = setOf("recruitera")

    /*
     * AI data labelling and assessment (sources/AiData.kt). Its own title list
     * decides instead of the stack titles, the stack rule does not apply (these
     * jobs name no stack), and the source has already placed each job.
     */
    private const val AI_DATA = "ai_data"

    /* Hiring posts searched in Saudi Arabia and the UAE: they have to name a place there instead of Egypt. */
    private const val GULF_POSTS = "gulf_posts"

    /*
     * Hiring posts. A post's title is its opening line, not a role, so the
     * title rule is off and full stack has to be in the stack list instead,
     * since it is searched for.
     */
    private val postTech get() = tech + listOf("full stack", "full-stack", "fullstack", "software engineer", "software engineers")

    /*
     * Where a post that names no Egyptian place is decided instead: most
     * Cairo companies write "Location: Remote" and leave the country to
     * context, so silence is kept and only another market rejects.
     */
    val postExcludeLocations = listOf(
        "saudi", "saudi arabia", "ksa", "riyadh", "jeddah", "dammam", "khobar", "jordan", "amman",
        "lebanon", "beirut", "uae", "united arab emirates", "dubai", "abu dhabi", "sharjah", "qatar",
        "doha", "kuwait", "bahrain", "oman", "muscat", "iraq", "morocco", "tunisia", "algeria",
        "سعودية", "السعودية", "الرياض", "جدة", "الأردن", "الاردن", "عمان", "الإمارات", "الامارات",
        "دبي", "أبوظبي", "قطر", "الدوحة", "الكويت", "البحرين",
    )

    /*
     * Hiring posts vs job-seeking ones, from src/filters.js hiringIntent: a
     * seeker writes in the first person singular about being available, an
     * employer in the plural about wanting someone. These reject outright.
     */
    private val seekerDisqualifiers = listOf(
        "#opentowork", "#openforwork", "#open_to_work", "#lookingforjob", "#lookingforajob",
        "i am looking for", "i'm looking for", "i am currently looking", "i'm currently looking",
        "i am seeking", "i'm seeking", "i am available", "i'm available", "open to work", "open for work",
        "my cv", "my resume", "my experience is", "refer me",
        "بدور على", "بدور علي", "بدوّر على", "أبحث عن عمل", "ابحث عن عمل", "أبحث عن فرصة", "ابحث عن فرصة",
        "أبحث عن وظيفة", "ابحث عن وظيفة", "خبرتي", "سيرتي الذاتية", "متاح للعمل", "متاحة للعمل",
        "أنا حالياً", "انا حاليا",
    )

    private val employerTerms = listOf(
        "we are hiring", "we're hiring", "we are looking for", "we're looking for", "we need",
        "our team is looking", "our client is looking", "join our team", "join our growing team", "join us",
        "is hiring", "are hiring", "open position", "open positions", "open role", "job opening",
        "we have an opening", "vacancy", "vacancies", "send your cv", "send your updated cv",
        "send your resume", "send your updated resume", "share your cv", "send cv to", "apply now",
        "to apply", "interested candidates", "qualified candidates", "if you are interested",
        "dm me your cv", "send me your cv",
        "مطلوب", "مطلوبين", "نبحث عن", "وظيفة شاغرة", "وظائف شاغرة", "للتقديم", "التقديم عن طريق",
        "ارسل السيرة الذاتية", "ترشيح",
    )

    private val seekerTerms = listOf(
        "looking for a job", "looking for a new job", "looking for a new opportunity",
        "looking for new opportunities", "looking for an opportunity", "open to opportunities",
        "open to new opportunities", "immediately available", "available immediately", "ready to join",
        "attached my cv", "here is my cv", "kindly refer", "any referral", "any leads", "i have experience",
        "years of experience", "fresh graduate", "recent graduate", "seeking a position", "seeking a new",
        "help me find", "appreciate any",
        "خريج", "حديث التخرج", "محتاج شغل", "ياريت حد", "برجاء الترشيح",
    )

    // A mailbox named for hiring, which is where an employer sends CVs.
    private val hiringMailboxRe = Regex(
        """(?<![a-z0-9._%+-])(?:hiring|careers?|jobs|hr|recruitment|recruiting|talent)@[a-z0-9.-]+\.[a-z]{2,}""",
        RegexOption.IGNORE_CASE,
    )

    // Searches that name no country: where the posting is has to be read.
    private val broadMarkets = setOf("remote", "worldwide", "anywhere", "emea", "")

    /*
     * A Saudi/UAE job is only reachable from Cairo if it says it takes someone
     * relocating: relocation, a sponsored work visa or iqama, or open to all
     * nationalities. A Gulf job that says none of this is treated as local-only
     * and dropped (evaluate, "gulf-local-only"). Phrase forms on purpose, so a
     * negation like "no relocation" or "no visa sponsorship" is not read as a yes
     * (and the explicit residency/nationality locks are already out via cannotApply).
     */
    private val gulfOpen = listOf(
        "relocation assistance", "relocation support", "relocation package", "relocation provided",
        "relocation offered", "relocation covered", "we relocate", "will relocate you",
        "visa sponsorship", "visa sponsor", "sponsor your visa", "we sponsor", "sponsorship provided",
        "sponsorship available", "work visa provided", "residence visa provided", "we provide iqama",
        "iqama provided", "residence visa", "iqama transfer", "visa covered", "iqama covered",
        "open to all nationalities", "all nationalities", "all nationalities welcome",
        "any nationality", "open to international", "international candidates", "overseas candidates",
        "candidates from abroad", "expats welcome", "expatriates",
        // Arabic: relocation / sponsorship / open to all nationalities.
        "استقدام", "نوفر الإقامة", "توفير الإقامة", "نكفل الإقامة", "تأشيرة عمل",
        "جميع الجنسيات", "كافة الجنسيات", "لجميع الجنسيات", "للمقيمين وغير المقيمين",
    )

    private val apostrophes = Regex("[‘’‛ʼ′]")

    private val emailRe = Regex(
        """[a-z0-9._%+-]+\s*(?:@|\(at\)|\[at\]|\s+at\s+)\s*[a-z0-9.-]+\s*(?:\.|\(dot\)|\[dot\])\s*[a-z]{2,}""",
        RegexOption.IGNORE_CASE,
    )

    private val phonePatterns = listOf(
        // Egypt mobile: 01[0125] + 8 more digits.
        Regex("""(?:\+?2[\s.-]?)?01[0125](?:[\s.-]?\d){8}(?!\d)"""),
        // Saudi mobile.
        Regex("""(?:\+?966[\s.-]?5|05)(?:[\s.-]?\d){8}(?!\d)"""),
        // Generic international fallback.
        Regex("""\+\d[\d\s().-]{8,16}\d(?!\d)"""),
    )

    /*
     * A level attached to a role in the body — "senior developer" — while
     * "reporting to a senior colleague" does not match.
     */
    private val seniorRoleRe = Regex(
        """\b(?:senior|sr\.?|lead|principal|staff|head)\s+(?:[a-z.+#/-]+\s+){0,3}?(?:engineer|developer|programmer|architect|dev)\b""",
        RegexOption.IGNORE_CASE,
    )

    /*
     * A level range in a title — "Middle / Senior", "Mid-Senior", "Junior to Mid" —
     * is open to the lower level, so its senior half is not a reason to reject.
     * Only the range is taken out: "Mid/Senior Tech Lead" still says "lead".
     */
    private const val LOWER_LEVEL = """(?:mid-level|junior|jr\.?|middle|medior|mid|intermediate)"""
    private const val UPPER_LEVEL = """(?:senior|sr\.?)"""
    private const val RANGE_JOIN = """\s*(?:/|-|–|&|\+|\bto\b|\bor\b)\s*"""
    private val levelRangeRe = Regex(
        "(?<![a-z0-9])(?:$LOWER_LEVEL$RANGE_JOIN$UPPER_LEVEL|$UPPER_LEVEL$RANGE_JOIN$LOWER_LEVEL)(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )

    /** The text with any "Middle / Senior"-style range removed. */
    fun withoutLevelRange(text: String): String = levelRangeRe.replace(text, " ")

    private val termCache = HashMap<String, Regex>()

    data class Verdict(val pass: Boolean, val reason: String?, val emails: List<String>, val phones: List<String>)

    fun fold(text: String): String = text.lowercase().replace(apostrophes, "'")

    private fun haystack(item: Item) = fold(
        listOfNotNull(item.title, item.company, item.location, item.excerpt, item.seniority)
            .joinToString(" \n "),
    )

    /** Word-ish boundary match, so "node" does not hit "nodemon". */
    fun containsTerm(text: String, term: String): Boolean {
        val needle = fold(term).trim()
        if (needle.isEmpty()) return false
        return termRegex(needle).containsMatchIn(text)
    }

    private fun termRegex(needle: String): Regex = synchronized(termCache) {
        termCache.getOrPut(needle) {
            Regex("(?<![a-z0-9])${Regex.escape(needle)}(?![a-z0-9])", RegexOption.IGNORE_CASE)
        }
    }

    fun containsAny(text: String, terms: List<String>) = terms.any { containsTerm(text, it) }

    fun extractEmails(text: String) = emailRe.findAll(text).map { it.value.trim() }.distinct().toList()

    fun extractPhones(text: String): List<String> {
        val found = LinkedHashSet<String>()
        for (pattern in phonePatterns) {
            for (match in pattern.findAll(text)) {
                val digits = match.value.count { it.isDigit() }
                // Reject things that are really dates, salaries or years.
                if (digits in 9..15) found.add(match.value.trim())
            }
        }
        return found.toList()
    }

    /**
     * Where the posting is, decided by the search that found it where possible.
     * Only a borderless search falls back to reading the text.
     */
    fun locationAllowed(item: Item, text: String? = null): Boolean {
        val market = (item.market ?: "").trim().lowercase()
        if (market !in broadMarkets) return true
        return containsAny(text ?: haystack(item), egyptPlaces)
    }

    /**
     * Cheap pre-check on a search card, before a detail page is spent on it.
     * Rejects only on what a description cannot overturn: where the job is,
     * and what the title says.
     */
    fun couldPass(item: Item, requireTitle: Boolean = true): Boolean {
        val title = fold(item.title)
        val placed = fold(listOfNotNull(item.title, item.location).joinToString(" "))
        val card = fold(listOfNotNull(item.title, item.company, item.location).joinToString(" "))

        if (containsAny(placed, blockedLocations)) return false
        if (requireTitle && !containsAny(title, titleTerms)) return false
        if (!locationAllowed(item, card)) return false
        return !containsAny(withoutLevelRange(title), seniorityExclude)
    }

    /**
     * "seeking", "hiring" or "unclear". A seeker's own first-person words
     * decide alone; what is left is weighed, and a tie goes to the employer.
     */
    fun hiringIntent(text: String): String {
        if (containsAny(text, seekerDisqualifiers)) return "seeking"

        val employer = employerTerms.count { containsTerm(text, it) } + if (hiringMailboxRe.containsMatchIn(text)) 1 else 0
        val seeker = seekerTerms.count { containsTerm(text, it) }

        if (employer == 0 && seeker == 0) return "unclear"
        return if (seeker > employer) "seeking" else "hiring"
    }

    /**
     * @param maxYears the most years of experience the person will apply
     *   with (Settings); 0 lets any through.
     */
    fun evaluate(item: Item, maxYears: Int = 0): Verdict {
        val text = haystack(item)
        val emails = extractEmails(text)
        val phones = extractPhones(text)

        fun reject(reason: String) = Verdict(false, reason, emails, phones)

        // LinkedIn's own criterion. "Mid-Senior level" is left to the title
        // rules: employers here tag two-year roles with it.
        item.seniority?.lowercase()?.let {
            if (Regex("director|executive").containsMatchIn(it)) return reject("seniority-level")
        }

        // Where the posting itself says it sits; "our team in India" in a
        // description is not a location.
        val placed = fold(listOfNotNull(item.title, item.location).joinToString(" \n "))
        if (containsAny(placed, blockedLocations)) return reject("location-blocked")

        // What the person muted from a job's screen: a company, or words in a title.
        Mutes.reasonFor(item)?.let { return reject(it) }

        val role = fold(item.title)
        val post = item.itemType == "post"
        val anyRole = post || item.source in categorised
        val aiData = item.source == AI_DATA
        // Arabic freelance gigs: the English title and stack lists would drop them all, so the
        // freelance matcher (English + Arabic stack) decides instead.
        val freelance = item.source in com.jobcrawler.app.sources.Freelance.KEYS

        if (aiData) {
            if (!containsAny(role, com.jobcrawler.app.sources.AiData.TITLE_TERMS)) return reject("title")
            com.jobcrawler.app.sources.AiData.scopeReason(role, text)?.let { return reject(it) }
        } else if (freelance) {
            if (!com.jobcrawler.app.sources.Freelance.fits(text)) return reject("stack")
        } else if (!anyRole && !containsAny(role, titleTerms)) {
            return reject("title")
        }
        if (cannotApply(text)) return reject("ineligible")
        if (!aiData && !freelance && item.source !in categorised && !containsAny(text, if (post) postTech else tech)) return reject("tech")

        if (post) {
            // The post's own words, not the market the search named: a search
            // naming Egypt returns "Location: Jordan" too.
            val placeText = fold(listOfNotNull(item.title, item.company, item.excerpt).joinToString(" \n "))
            if (item.source == GULF_POSTS) {
                // Silence is not kept here: an unplaced post is most likely in Egypt, and the Egypt search has it.
                if (!containsAny(placeText, gulfPlaces)) return reject("location")
            } else if (!containsAny(placeText, egyptPlaces) && containsAny(placeText, postExcludeLocations)) {
                return reject("location")
            }

            // Half of what a hiring search returns is people looking for
            // work; a post that says neither way is usually a tutorial.
            when (hiringIntent(text)) {
                "seeking" -> return reject("seeking-work")
                "unclear" -> return reject("intent-unclear")
            }
        } else if (!aiData) {
            if (!locationAllowed(item, text)) return reject("location")
            // A Saudi/UAE job has to say it takes someone relocating from Egypt (relocation, a
            // sponsored visa/iqama, or open to all nationalities); a silent one is local-only.
            // The explicit residency/nationality locks are already gone above via cannotApply.
            if (Gulf.isGulf(item.market) && !containsAny(text, gulfOpen)) return reject("gulf-local-only")
        }

        // Judged on the role name: a junior ad talks about senior people.
        // "Arabic Language Expert" is a labelling gig, not a senior role.
        val tooSenior = if (aiData) seniorityExclude - "expert" else seniorityExclude
        if (containsAny(withoutLevelRange(role), tooSenior)) return reject("seniority-excluded")
        if (seniorRoleRe.containsMatchIn(withoutLevelRange(item.excerpt ?: ""))) return reject("seniority-excluded-body")

        // Last: a senior title is already gone, so this is "5+ years" in an otherwise fitting ad.
        if (maxYears > 0) {
            val asked = item.yearsMin ?: Extract.years(listOfNotNull(item.title, item.excerpt).joinToString("\n"))?.min
            if (asked != null && asked > maxYears) return reject("experience")
        }

        return Verdict(true, null, emails, phones)
    }
}
