package com.jobcrawler.app.sources

import com.jobcrawler.app.Filters

/**
 * What the CV offers, for sources that list jobs from abroad (jobright,
 * Relocate.me): those boards are not searched by keyword, so a job has to
 * be checked against the CV's stack and level here instead.
 */
object CvStack {
    /** The person has 1-3 years: a job asking for more than this is out of reach. */
    const val MAX_YEARS = 4

    /* The CV's stack, as shown on the matched line. At least one has to be there. */
    private val STACK = linkedMapOf(
        "PHP" to listOf("php"),
        "Laravel" to listOf("laravel"),
        "NestJS" to listOf("nestjs", "nest.js"),
        "Node.js" to listOf("node.js", "nodejs", "node"),
        "Go" to listOf("golang", "go lang"),
        "TypeScript" to listOf("typescript"),
    )

    // "Go" as a language, never "go live" or "go-to-market": case-sensitive and in a list.
    private val GO_RE = Regex("""(?:\b(?:in|with|using|and|or|of)\s+|[,(/]\s*)Go\b(?![-\s]+(?:to|live|beyond)\b)""")

    /* What else on the CV a posting may ask for; more of these ranks it higher. */
    private val BOOST = listOf(
        "mysql", "sql server", "mongodb", "redis", "rest", "rest api", "restful", "queue", "queues", "webhooks",
        "multi-tenant", "multi-tenancy", "docker", "aws", "jwt", "oauth", "payments", "payment", "fintech",
        "pos", "erp", "filament", "livewire", "microservices", "ai-assisted", "ai tools", "claude", "llm",
        "spec-driven", "api design", "postgresql", "sequelize", "typeorm", "phpunit",
    )

    // "3+ years of experience", "2-4 years of professional backend experience", "5 years' experience".
    private val YEARS_RE = Regex(
        """(\d{1,2})\s*\+?\s*(?:(?:-|–|to)\s*\d{1,2}\s*)?\+?\s*years?['’]?\s+(?:of\s+)?(?:[a-z/+#.-]+\s+){0,3}?(?:experience|exp\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** The CV's languages and frameworks [text] names, in the CV's order. */
    fun of(text: String): List<String> {
        val folded = Filters.fold(text)
        return STACK.filter { (name, terms) ->
            Filters.containsAny(folded, terms) || (name == "Go" && GO_RE.containsMatchIn(text))
        }.keys.toList()
    }

    /** How much of the rest of the CV [text] asks for. */
    fun score(text: String): Int {
        val folded = Filters.fold(text)
        return BOOST.count { Filters.containsTerm(folded, it) }
    }

    /** The lowest experience a posting names, or null when it names none. */
    fun minYears(text: String): Int? =
        YEARS_RE.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 1..20 }.minOrNull()
}
