package com.jobcrawler.app.sources

import com.jobcrawler.app.Filters
import com.jobcrawler.app.Item

/**
 * Jobs abroad — Europe, the UK, Canada, Australia, and the US boards — judged
 * for a backend developer in Cairo: remote work open to Egypt, or a visa
 * sponsored; English only; the CV's stack at its level. Every abroad source
 * asks [blocked] for the hard rules, and the pipeline ranks what is left by
 * [score], delivering only the best fits (see [MIN_SCORE], [MAX_PER_RUN]).
 * Pure, so it is tested without a phone.
 */
object AbroadFit {
    /** Below this, a job is stored but neither posted nor emailed. */
    const val MIN_SCORE = 55

    /** Abroad jobs posted and emailed per source run, best first. */
    const val MAX_PER_RUN = 10

    const val REMOTE_LANE = "🌍 Remote, open to Egypt"
    const val VISA_LANE = "✈️ Visa sponsorship"

    /* What makes a job abroad reachable without a visa: remote, and open to someone in Egypt. */
    val OPEN_TO_EGYPT = Jobright.WORLDWIDE

    /* What makes it reachable with one. */
    val VISA_WORDS = Jobright.VISA_TERMS + listOf(
        "visa sponsorship", "sponsorship provided", "visa support", "visa provided", "visa paid", "relocation bonus",
        "skilled worker visa", "skilled worker sponsorship", "blue card", "eu blue card", "work permit provided",
        "tier 2", "lmia", "tss 482", "482 visa", "subclass 482", "global talent", "highly skilled migrant",
        "kennismigrant", "we sponsor visas", "sponsor your visa", "sponsor work visas",
        // Set by SponsorBoards on every job from a company known to sponsor.
        "known visa sponsor",
    )

    /*
     * Remote or not, only for people already there. Filters.ineligible covers
     * "must be based in" and the like. Nothing that describes the company
     * ("a UK-based fintech", "our office is located in Berlin"): only the candidate.
     */
    val REGION_LOCKED = listOf(
        "uk only", "u.k. only", "eu only", "europe only", "eu residents", "eu residents only", "eu citizens",
        "eu citizenship", "eu passport", "eu work permit", "uk residents", "uk citizens",
        "candidates based in the uk", "candidates based in the eu", "candidates based in europe",
        "candidates based in canada", "candidates based in australia",
        "residing in the eu", "residing in europe", "residing in the uk", "residing in canada", "residing in australia",
        "canadian residents", "canadian citizen", "canadian citizens", "canadian citizenship",
        "australian residents", "australian citizen", "australian citizens", "australian citizenship",
        "permanent resident", "permanent residents", "permanent residency", "pr holder", "pr holders",
        "citizen or pr", "citizens or pr", "citizens or permanent residents", "valid work permit", "existing work permit",
        "existing right to work", "full working rights", "unrestricted work rights", "no relocation",
        "relocation not available", "relocation is not available", "not offering relocation",
        // US/Canada/North America: "remote" but only for people already there, not open to Egypt.
        // Restriction-flavoured on purpose — bare "north america"/"canada" would catch company or
        // customer geography ("our customers are across North America") on a genuinely open role.
        "us and canada", "u.s. and canada", "us & canada", "u.s. & canada", "usa and canada",
        "united states and canada", "us or canada", "u.s. or canada", "usa or canada",
        "united states or canada", "us/canada", "u.s./canada", "usa/canada",
        "north america only", "north american candidates", "north american applicants",
        "based in north america", "located in north america", "within north america", "must be in north america",
        "canada only", "canadian applicants", "canadian applicants only",
        "based in canada", "located in canada", "within canada", "must be in canada",
    )

    // Not "polish": "attention to detail and polish" is English.
    private const val LANGS = "german|dutch|french|swedish|danish|norwegian|finnish|italian|spanish|portuguese|czech|hungarian|romanian|greek|estonian|lithuanian|latvian|deutsch|nederlands|français|francais|svenska|dansk|norsk|suomi|polski"

    // "fluent German", "business-level Dutch", "C1 French", "working knowledge of Swedish".
    private val levelThenLang = Regex(
        """\b(fluent|fluency in|native|native-level|business[- ]level|business fluent|professional|proficient|proficiency in|excellent|very good|good|strong|working knowledge of|c1|c2|b2)\s+(?:command of\s+|knowledge of\s+|level\s+|spoken\s+and\s+written\s+)?($LANGS)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "German is required", "Dutch language skills (C1) are a must", "French: mandatory".
    private val langThenRequired = Regex(
        """\b($LANGS)\s+(?:language\s+)?(?:skills?\s+)?(?:\(?(?:c1|c2|b2)\)?\s+)?(?:is\s+|are\s+|:\s*)?(required|a must|mandatory|essential|needed|necessary)\b""",
        RegexOption.IGNORE_CASE,
    )

    // "Fluent English and German", "fluency in German and English".
    private val withEnglish = Regex(
        """\b(fluent|fluency|native|proficient|proficiency|excellent)\b[^.\n]{0,30}\b(english\s+(?:and|&)\s+($LANGS)|($LANGS)\s+(?:and|&)\s+english)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val germanWords = Regex("""\b(deutschkenntnisse|sprachkenntnisse|fließend(?:es)? deutsch|verhandlungssicher)\b""", RegexOption.IGNORE_CASE)

    // A language that is a bonus is not a condition.
    private val onlyABonus = Regex("""^[^.\n]{0,40}\b(nice to have|is a plus|a plus|bonus|an advantage|advantageous|preferred|not required|is not required|helpful|desirable)\b""", RegexOption.IGNORE_CASE)

    /** Whether the posting asks for a language other than English. */
    fun needsOtherLanguage(text: String): Boolean {
        if (germanWords.containsMatchIn(text)) return true
        return listOf(levelThenLang, langThenRequired, withEnglish).any { re ->
            re.findAll(text).any { match -> !onlyABonus.containsMatchIn(text.substring(match.range.last + 1)) }
        }
    }

    private val english = setOf("the", "and", "to", "of", "with", "you", "we", "for", "our", "is", "are", "will", "your", "a", "an", "this", "that", "as", "on", "be", "have", "who")
    private val other = setOf(
        // German
        "und", "der", "die", "das", "mit", "für", "wir", "sie", "ist", "ein", "eine", "zu", "auf", "von", "bei", "dich", "du", "deine", "unser", "unsere", "oder", "nicht", "werden",
        // Dutch
        "het", "van", "een", "voor", "met", "wij", "jij", "jouw", "zijn", "ons", "onze", "naar", "ook", "bij", "werken",
        // French
        "et", "le", "les", "des", "pour", "avec", "nous", "vous", "une", "est", "dans", "votre", "nos", "sur", "au", "aux",
        // Spanish, Portuguese, Italian, Swedish
        "y", "el", "los", "las", "para", "con", "nosotros", "del", "una", "uma", "não", "il", "della", "och", "att", "för", "vi", "är", "som",
    )

    /** Whether most of the text is English; a text too short to tell counts as English. */
    fun isEnglish(text: String): Boolean {
        val words = Regex("""[\p{L}']+""").findAll(text.lowercase()).map { it.value }.toList()
        val en = words.count { it in english }
        val xx = words.count { it in other }
        if (en + xx < 20) return true
        return en.toDouble() / (en + xx) >= 0.5
    }

    /** Why a job abroad is out whatever its fit, or null: another language, or only for people already there. */
    fun blocked(text: String): String? = when {
        needsOtherLanguage(text) -> "language"
        !isEnglish(text) -> "not-english"
        Filters.containsAny(Filters.fold(text), REGION_LOCKED) -> "region-locked"
        else -> null
    }

    private val openRestriction = listOf(
        "worldwide", "anywhere", "global", "globally", "emea", "mena", "middle east", "africa", "egypt",
        "utc+2", "utc+3", "gmt+2", "gmt+3", "eet",
    )

    /**
     * Whether a feed's "who may apply" field takes someone in Egypt: nothing
     * said, or worldwide, EMEA, Africa, Egypt, UTC+2/+3. A list of other
     * countries ("Europe, LATAM, the U.S., Canada") or "USA" does not.
     */
    fun openToEgypt(restriction: String?): Boolean {
        val text = restriction?.trim()?.ifEmpty { null } ?: return true
        return Filters.containsAny(Filters.fold(text), openRestriction)
    }

    /** Remote open to Egypt first (no visa needed), then a sponsored visa; null when neither. */
    fun laneOf(text: String, remote: Boolean): String? {
        val folded = Filters.fold(text)
        val remoteWork = remote || Filters.containsAny(folded, listOf("remote", "fully remote", "work from home", "wfh"))
        if (remoteWork && Filters.containsAny(folded, OPEN_TO_EGYPT) && !Filters.containsAny(folded, Jobright.US_ONLY)) return REMOTE_LANE
        if (Filters.containsAny(folded, VISA_WORDS)) return VISA_LANE
        return null
    }

    private val regions = listOf(
        "🇨🇦 Canada" to listOf("canada", "toronto", "vancouver", "montreal", "montréal", "calgary", "ottawa", "edmonton", "waterloo", "ontario", "british columbia", "quebec", "alberta"),
        "🇦🇺 Australia" to listOf("australia", "sydney", "melbourne", "brisbane", "perth", "adelaide", "canberra", "new south wales", "queensland"),
        "🇬🇧 UK" to listOf("united kingdom", "uk", "england", "london", "manchester", "birmingham", "edinburgh", "glasgow", "bristol", "leeds", "cambridge", "oxford", "scotland"),
        "🇪🇺 Europe" to listOf(
            "european union", "europe", "germany", "berlin", "munich", "münchen", "hamburg", "frankfurt", "cologne", "netherlands", "amsterdam",
            "rotterdam", "utrecht", "eindhoven", "the hague", "ireland", "dublin", "france", "paris", "spain", "madrid", "barcelona", "portugal",
            "lisbon", "porto", "italy", "milan", "rome", "belgium", "brussels", "austria", "vienna", "switzerland", "zurich", "zürich", "geneva",
            "sweden", "stockholm", "denmark", "copenhagen", "norway", "oslo", "finland", "helsinki", "poland", "warsaw", "krakow", "kraków",
            "czech", "prague", "estonia", "tallinn", "lithuania", "vilnius", "latvia", "riga", "luxembourg", "malta", "cyprus", "greece",
            "athens", "romania", "bucharest", "hungary", "budapest",
        ),
    )

    /** Where a job is, as the channel shows it: Canada, Australia, the UK or Europe; null for anywhere else. */
    fun regionOf(vararg parts: String?): String? {
        val text = Filters.fold(parts.filterNotNull().joinToString(" \n "))
        return regions.firstOrNull { (_, places) -> Filters.containsAny(text, places) }?.first
    }

    private val stackPoints = mapOf("Laravel" to 20, "PHP" to 15, "NestJS" to 12, "Node.js" to 12, "Go" to 10, "TypeScript" to 5)
    private val levelRe = Regex("""\b(junior|mid|mid-level|intermediate|associate|graduate|entry)\b""", RegexOption.IGNORE_CASE)
    private val seniorRe = Regex("""\b(senior|sr\.?|lead|principal|staff)\b""", RegexOption.IGNORE_CASE)
    private val sharpLane = listOf("emea", "europe", "european", "cet", "cest", "eet", "utc+2", "utc+3", "gmt+2", "gmt+3", "egypt", "mena", "middle east", "africa")
    private val sponsorship = listOf("visa sponsorship", "will sponsor", "we sponsor", "sponsorship available", "blue card", "skilled worker", "lmia", "482", "visa paid", "visa provided")

    /**
     * The chance the person is taken, 0-100: how much of the CV's stack the
     * job names (Laravel and PHP most), whether its title is that stack,
     * the rest of the CV it asks for, a level in reach, and how plainly it
     * says it takes someone from Egypt.
     */
    fun score(title: String, body: String?, market: String?): Int {
        val text = "$title\n${body.orEmpty()}"
        val folded = Filters.fold(text)
        var points = CvStack.of(text).sumOf { stackPoints[it] ?: 0 }.coerceAtMost(40)

        points += when {
            CvStack.of(title).isNotEmpty() -> 10
            Filters.containsAny(Filters.fold(title), listOf("backend", "back-end", "back end", "full stack", "full-stack", "fullstack")) -> 5
            else -> 0
        }
        points += (CvStack.score(text) * 3).coerceAtMost(15)

        val years = CvStack.minYears(text)
        points += when {
            levelRe.containsMatchIn(title) -> 15
            years != null && years <= 3 -> 15
            years == 4 -> 8
            years == null -> 5
            else -> 0
        }

        val lane = market.orEmpty()
        points += when {
            lane.startsWith("🌍") && Filters.containsAny(folded, sharpLane) -> 15
            lane.startsWith("✈️") && Filters.containsAny(folded, sponsorship) -> 15
            lane.startsWith("🌍") || lane.startsWith("✈️") -> 5
            else -> 0
        }
        if (Filters.containsAny(folded, listOf("relocation package", "relocation support", "relocation assistance"))) points += 5
        if (seniorRe.containsMatchIn(body.orEmpty())) points -= 5

        return points.coerceIn(0, 100)
    }

    fun score(item: Item): Int = score(item.title, item.excerpt, item.market)

    /** The jobs worth sending, best first: at least [MIN_SCORE], at most [MAX_PER_RUN]. */
    fun best(items: List<Item>): List<Item> =
        items.map { it to score(it) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .take(MAX_PER_RUN)
            .map { it.first }
}
