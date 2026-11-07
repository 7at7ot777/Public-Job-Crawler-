import 'dotenv/config';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const bool = (value, fallback = false) => {
  if (value === undefined || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).trim().toLowerCase());
};

const int = (value, fallback) => {
  const parsed = Number.parseInt(value ?? '', 10);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const float = (value, fallback) => {
  const parsed = Number.parseFloat(value ?? '');
  return Number.isFinite(parsed) ? parsed : fallback;
};

/** Split a comma-separated env value into a trimmed, non-empty list. */
const list = (value, fallback = []) => {
  if (!value) return fallback;
  return String(value)
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean);
};

const resolve = (value, fallback) => {
  // Trim defensively: a .env authored on Windows can leave a trailing \r on the
  // value, which becomes part of the path on Linux and fails in confusing ways.
  const target = value && value.trim() !== '' ? value.trim() : fallback;
  return path.isAbsolute(target) ? target : path.join(projectRoot, target);
};

/*
 * Saudi Arabia and the UAE on the job boards and company ATS: the Android
 * app's switch, on by default. Read here, ahead of the sources, because it
 * widens their defaults (Wuzzuf's sitemaps, Tanqeeb's sites, Recruitera's
 * countries, Workable's locations). See config.boardsGulf below.
 */
const boardsGulf = bool(process.env.BOARDS_GULF, true);

/** Tanqeeb's per-country sites, by subdomain, and the market each one is. */
const TANQEEB_COUNTRIES = {
  egypt: 'Egypt',
  saudi: 'Saudi Arabia',
  uae: 'United Arab Emirates',
  qatar: 'Qatar',
  kuwait: 'Kuwait',
  bahrain: 'Bahrain',
  oman: 'Oman',
};

/** Search URL -> market, for the Tanqeeb sites this run should search. */
function tanqeebSites() {
  if (process.env.TANQEEB_SEARCH_URL) {
    return { [process.env.TANQEEB_SEARCH_URL]: process.env.TANQEEB_MARKET || 'Egypt' };
  }

  const wanted = list(process.env.TANQEEB_SITES, boardsGulf ? ['egypt', 'saudi', 'uae'] : ['egypt']);

  return Object.fromEntries(
    wanted
      .map((subdomain) => subdomain.toLowerCase())
      .filter((subdomain) => TANQEEB_COUNTRIES[subdomain])
      .map((subdomain) => [
        `https://${subdomain}.tanqeeb.com/jobs/search`,
        TANQEEB_COUNTRIES[subdomain],
      ]),
  );
}

// The technologies every source's items must mention (filters.tech below).
const filterTech = list(process.env.FILTER_TECH, [
  'laravel',
  'node',
  'nodejs',
  'node.js',
  'php',
  'backend',
  'back-end',
  'back end',
  // Application support is a searched keyword, and those ads rarely name the
  // stack. 'support' is the word they do use; the title rule below is what
  // keeps it off helpdesk roles that are not about running software.
  'support',
  // Go, never bare "go": that would match "go live" and "years ago".
  'golang',
  'go lang',
  'go developer',
  'go engineer',
  'go programming',
  'go language',
  // The remoteKeywords roles name no stack at all, so they have to name
  // themselves: without these the tech gate rejects every one of them.
  'data entry',
  'virtual assistant',
  'technical support',
  'data annotation',
  'data annotator',
  'data labeling',
  'data labelling',
  'ai trainer',
  'administrative assistant',
  'admin assistant',
  'content moderator',
  'content moderation',
  // Both, because the match is on a word boundary: 'transcription' does not
  // reach 'transcriptionist'.
  'transcription',
  'transcriptionist',
]);

/*
 * What every hiring-post source searches for, unless it is given its own list.
 * Each keyword is one more billed query per run.
 */
/*
 * Three, not five: every keyword is eleven billed queries a run, one per location
 * below. 'Laravel' and 'Node.js' were dropped because a post that names only
 * the framework is rare — an ad written for a channel almost always says what
 * the role is too — and because these three already carry the stack keywords
 * the title and tech rules are looking for.
 */
const postKeywords = list(process.env.LINKEDIN_POSTS_KEYWORDS, [
  'Backend',
  'Full Stack',
  'Software Engineer',
]);
/*
 * Egypt plus the Greater Cairo areas a post names instead of the country:
 * "Location: Maadi" never matches a search for "Egypt". Each one is another
 * billed query per keyword per run.
 */
const postLocations = list(process.env.LINKEDIN_POSTS_LOCATIONS, [
  'Egypt',
  'Cairo',
  'New Cairo',
  'Giza',
  'Maadi',
  'Dokki',
  'Nasr City',
  'Heliopolis',
  'Sheikh Zayed',
  '6th of October',
  'Fifth Settlement',
]);

/*
 * How a posts source turns its keywords and places into billed queries, and
 * which of those queries it may skip. Read per source, so x_posts can be tuned
 * apart from linkedin_posts; the prefix is the source's env prefix.
 *
 * queryMode 'pairs' is one query per keyword x place: 3 x 11 = 33 a run.
 * 'grouped' puts up to groupSize places in one query as an OR group —
 * "Backend" ("Egypt" OR "Cairo" OR …) — so the same lists cost 3 x 2 = 6.
 * Pairs stays the default until grouped has been measured against it: the
 * yield the store records per query (`--stats`) is that measurement.
 *
 * coldAfter / coldEveryDays: a query that has found no fresh post on
 * coldAfter runs in a row is asked only every coldEveryDays days until it
 * finds one again. 0 turns it off. A cold query is not dropped — a quiet
 * suburb can start posting — it is only asked less often.
 */
const postSearchPlan = (prefix) => ({
  queryMode: (process.env[`${prefix}_QUERY_MODE`] || 'pairs').trim().toLowerCase(),
  groupSize: Math.max(1, int(process.env[`${prefix}_GROUP_SIZE`], 6)),
  coldAfter: Math.max(0, int(process.env[`${prefix}_COLD_AFTER`], 5)),
  coldEveryDays: Math.max(1, int(process.env[`${prefix}_COLD_EVERY_DAYS`], 3)),
});

/*
 * How a posting says the work is in Arabic. Shared by the ai_data source's
 * language rule (filters.languages) and the "Arabic" label on its Matched
 * line (src/sources/aiDataParser.js). Gulf and Egyptian vendors advertise in
 * Arabic as often as in English, hence the Arabic spellings.
 */
export const arabicTerms = [
  'arabic',
  'msa',
  'egyptian dialect',
  'gulf dialect',
  'levantine',
  'عربي',
  'العربية',
  'اللغة العربية',
];

/*
 * Every place a posting may name instead of "Egypt": Greater Cairo's districts
 * and new cities, Alexandria, and the other cities that hire. Matched as whole
 * words. A place that is also a first name or an everyday word (Rehab,
 * Shorouk, Haram) is listed only in its "el / al / city" form, since names
 * turn up in recruiters' signatures; Faisal and Alex not at all, and neither
 * is a name shared with towns abroad (Garden City). Shared by the crawl-wide
 * location filter and the posts filter.
 */
const egyptPlaces = [
  'egypt',
  // Greater Cairo: east and the new cities.
  'cairo',
  'new cairo',
  'fifth settlement',
  '5th settlement',
  'first settlement',
  '1st settlement',
  'tagamoa',
  'el tagamoa',
  'el rehab',
  'al rehab',
  'rehab city',
  'madinaty',
  'el shorouk',
  'shorouk city',
  'obour',
  'administrative capital',
  'new administrative capital',
  'nasr city',
  'heliopolis',
  'masr el gedida',
  'maadi',
  'katameya',
  'mokattam',
  'zamalek',
  '10th of ramadan',
  // Greater Cairo: Giza side.
  'giza',
  'dokki',
  'mohandessin',
  'agouza',
  'al haram',
  'el haram',
  '6th of october',
  '6 october',
  'october city',
  'sheikh zayed',
  'smart village',
  // The rest of the country.
  'alexandria',
  'smouha',
  'borg el arab',
  'mansoura',
  'tanta',
  'zagazig',
  'ismailia',
  'port said',
  'suez',
  'damietta',
  'assiut',
  'minya',
  'sohag',
  'beni suef',
  'fayoum',
  'luxor',
  'aswan',
  'hurghada',
  'el gouna',
  'sharm el sheikh',
  // Arabic.
  'مصر',
  'القاهرة',
  'القاهرة الجديدة',
  'التجمع',
  'التجمع الخامس',
  'الرحاب',
  'مدينتي',
  'الشروق',
  'العبور',
  'العاصمة الإدارية',
  'مدينة نصر',
  'مصر الجديدة',
  'المعادي',
  'المقطم',
  'الزمالك',
  'الجيزة',
  'الدقي',
  'المهندسين',
  '6 أكتوبر',
  'السادس من أكتوبر',
  'الشيخ زايد',
  'الاسكندرية',
  'الإسكندرية',
  'المنصورة',
  'طنطا',
  'الزقازيق',
];

/*
 * How a hiring post is judged, whichever site it came from. LinkedIn and X
 * posts are searched for the same thing — local hiring in Egypt — so they share
 * one set of rules rather than three copies that drift apart.
 *
 * Only the keys that differ from `filters` below; everything else (seniority,
 * contacts) still comes from FILTER_*.
 */
const postFilters = {
  remoteOnly: bool(process.env.LINKEDIN_POSTS_REMOTE_ONLY, false),
  // A post's title is its opening line, not the name of a role.
  titleTerms: [],

  /*
   * FILTER_TECH plus full stack, which is searched for here and so has to be
   * kept: a "Full Stack Developer" post naming no other stack word would
   * otherwise be found and then thrown away. Posts only — on a job board
   * the title rule decides instead.
   */
  tech: [
    ...filterTech,
    ...list(process.env.LINKEDIN_POSTS_EXTRA_TECH, [
      'full stack',
      'full-stack',
      'fullstack',
      'software engineer',
      'software engineers',
    ]),
  ],

  /*
   * Where the post has to be, judged on the post's own words.
   *
   * Every other source knows the market it searched and says so through
   * `market`, which settles the question. A search engine only biases
   * towards a market — a search naming Egypt returns "Senior Laravel
   * Developer. Location: Jordan" in first place — so there is nothing
   * here to take on trust, and the text has to say it.
   */
  locations: list(process.env.LINKEDIN_POSTS_FILTER_LOCATIONS, [...egyptPlaces, 'egyptian']),

  /*
   * Markets that are not this one, which decide a post that named no
   * market at all.
   *
   * Necessary because most Egyptian hiring posts never write "Egypt":
   * a Cairo company advertising to Cairo says "Location: Remote" and
   * leaves the rest to context, and a rule that demanded the word threw
   * away the real vacancies while keeping nothing. What the strays do
   * say is where they actually are — "Location: Jordan", "الرياض" — so
   * naming those is what separates them.
   */
  excludeLocations: list(process.env.LINKEDIN_POSTS_EXCLUDE_LOCATIONS, [
    'saudi',
    'saudi arabia',
    'ksa',
    'riyadh',
    'jeddah',
    'dammam',
    'khobar',
    'jordan',
    'amman',
    'lebanon',
    'beirut',
    'uae',
    'united arab emirates',
    'dubai',
    'abu dhabi',
    'sharjah',
    'qatar',
    'doha',
    'kuwait',
    'bahrain',
    'oman',
    'muscat',
    'iraq',
    'morocco',
    'tunisia',
    'algeria',
    'سعودية',
    'السعودية',
    'الرياض',
    'جدة',
    'الأردن',
    'الاردن',
    'عمان',
    'الإمارات',
    'الامارات',
    'دبي',
    'أبوظبي',
    'قطر',
    'الدوحة',
    'الكويت',
    'البحرين',
  ]),

  // A post is whatever its author wrote, and a search for hiring terms
  // returns people looking for work alongside people offering it.
  hiringOnly: bool(process.env.LINKEDIN_POSTS_HIRING_ONLY, true),
  /*
   * On here, unlike the crawl-wide default — which is why it reads its
   * own variable rather than FILTER_HIRING_STRICT: sharing that one
   * would let the crawl-wide setting silently switch this off.
   *
   * A `site:linkedin.com/posts laravel` search returns as much Laravel
   * *content* as Laravel hiring — release notes, tutorials, someone's
   * package announcement — and all of it passes the tech filter. Those
   * read as "said neither way", so keeping them means keeping the noise
   * this source exists to avoid. The cost is a genuine vacancy whose
   * text never reached us, which enrichment already makes uncommon: the
   * classifier reads the fetched post body, not just the snippet.
   */
  hiringStrict: bool(process.env.LINKEDIN_POSTS_HIRING_STRICT, true),
};

export const config = {
  projectRoot,

  enabled: bool(process.env.CRAWLER_ENABLED, false),

  keywords: list(process.env.JOB_KEYWORDS, [
    'Laravel Developer',
    'Node.js Developer',
    'Backend Developer',
    'PHP Developer',
    'Full Stack Developer',
    'Application Support',
    'Application Developer',
  ]),

  /*
   * Keywords searched only as remote work, and only in the borderless markets
   * below — never in the local ones.
   *
   * These are not the stack: they are the roles worth taking from anywhere,
   * where an employer in another country has no reason to care where the
   * candidate sits. Searching them in Egypt or the Gulf would return the local
   * on-site version of the same job, which is not what is wanted, and would
   * cost a request per market to find out.
   */
  remoteKeywords: list(process.env.JOB_REMOTE_KEYWORDS, [
    'Data Entry',
    'Virtual Assistant',
    'Technical Support',
    'Data Annotation',
    'Administrative Assistant',
    'Content Moderator',
    'Transcriptionist',
  ]),

  /*
   * Where remoteKeywords are searched. Separate from remoteLocations, which
   * applies to the stack keywords and is switched off, so turning one back on
   * does not silently turn on the other.
   *
   * Empty: the search is Egypt only, and these are borderless searches. Set
   * JOB_REMOTE_KEYWORD_LOCATIONS=Worldwide to search remoteKeywords again.
   */
  remoteKeywordLocations: list(process.env.JOB_REMOTE_KEYWORD_LOCATIONS, []),

  /*
   * Markets searched with no work-type filter, so they return on-site,
   * hybrid and remote postings alike.
   *
   * Egypt only, on-site, hybrid and remote alike. A remote search for Egypt
   * would only return a subset of what this one already does, so it is not
   * repeated below. Each entry is another keyword x location crawl, paged
   * LINKEDIN_MAX_PAGES deep, so keep the list short: the Gulf markets were
   * dropped, and with them half the pass, which is what lets the container
   * crawl every 10 minutes instead of every 30.
   */
  locations: list(process.env.JOB_LOCATIONS, [
    'Egypt',
    // Named with their country: "Cairo" alone also matches Cairo, Illinois.
    // Both are inside the Egypt search above, so these only buy the denser
    // city result sets LinkedIn returns for a metro query.
    'Cairo, Egypt',
    'Giza, Egypt',
    // Districts and a satellite city, for the same reason as the cities above:
    // LinkedIn returns a denser, more local result set for a narrower place.
    // Six locations is past the five a 10-minute rotation fits in r3600, so
    // the rotation's window grows to r4200 on its own (see rotationWindow).
    'Maadi, Cairo, Egypt',
    'Heliopolis, Cairo, Egypt',
    'Obour City, Egypt',
    // The Android app's additions. Ten places: the rotation's window widens on its own.
    'Alexandria, Egypt',
    'Sheraton, Cairo, Egypt',
    'Mohandessin, Giza, Egypt',
    'Dokki, Giza, Egypt',
    // The Gulf, back on: LinkedIn's Saudi Arabia and UAE results, tagged #Gulf.
    // They still have to pass gulf-local-only (relocation, sponsorship or
    // all-nationalities wording). With the rotation on, a run is still one
    // place; twelve places only widen the rotation's window (rotationWindow).
    'Saudi Arabia',
    'United Arab Emirates',
  ]),

  /*
   * Searched for remote work only (LinkedIn f_WT=2).
   *
   * Borderless searches only. A US or EU "remote" posting is remote *inside*
   * that country and screens out anyone without the right to work there, so
   * no foreign country is searched by name; what these return is placed by
   * FILTER_LOCATIONS, read out of the posting, because the search named
   * nowhere a candidate is sure to fit.
   */
  remoteLocations: list(process.env.JOB_REMOTE_LOCATIONS, ['Worldwide', 'EMEA']),

  http: {
    timeoutMs: int(process.env.HTTP_TIMEOUT_MS, 15000),
    // Applied before every request so pacing holds across the keyword loops,
    // not just within one pagination sequence.
    delayMs: int(process.env.REQUEST_DELAY_MS, 2500),
    maxRetries: int(process.env.MAX_RETRIES, 3),
    backoffBaseMs: int(process.env.BACKOFF_BASE_MS, 4000),
    userAgent:
      process.env.USER_AGENT && process.env.USER_AGENT.trim() !== ''
        ? process.env.USER_AGENT
        : 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
  },

  /*
   * Sources are keyed by the value stored in items.source. Adding a provider
   * means writing a module that exports the same shape and registering it in
   * src/sources/index.js — nothing else in the pipeline changes.
   */
  sources: {
    linkedin_jobs: {
      enabled: bool(process.env.LINKEDIN_JOBS_ENABLED, true),
      // Public guest endpoints: no login, no cookies, no account.
      searchUrl: 'https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search',
      detailUrl: 'https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/',
      timeWindow: process.env.LINKEDIN_TIME_WINDOW || 'r3600',
      // Every 15 minutes in --watch, ahead of the hourly CRON_SCHEDULE: new
      // postings are worth sending while they are fresh. The hour-wide
      // timeWindow overlaps every tick, and dedup keeps repeats out.
      cron: process.env.LINKEDIN_JOBS_CRON || '*/10 * * * *',
      // Used only for the crawl that runs at startup. A restart loses the
      // dedup database on hosts without a volume, and any downtime means
      // missed hours, so the first pass looks further back than the hourly
      // ticks do. Dedup still prevents anything being sent twice.
      startupTimeWindow: process.env.LINKEDIN_STARTUP_TIME_WINDOW || 'r86400',
      pageSize: 25,
      maxPages: int(process.env.LINKEDIN_MAX_PAGES, 4),
      /*
       * LinkedIn f_E: 1 internship, 2 entry, 3 associate, 4 mid-senior,
       * 5 director. Empty string disables the server-side filter.
       *
       * Mid-senior is included because employers here routinely tag a two-
       * to four-year role as 4, and this filter runs on LinkedIn's side —
       * anything it drops never reaches the crawler's own seniority rules,
       * which can read the title and decide. Director is the only level left
       * out, being the one that is never a fit.
       */
      experienceLevels: process.env.LINKEDIN_EXPERIENCE_LEVELS ?? '1,2,3,4',
      // One extra request per posting, but gives the filters a description to
      // read instead of just a title.
      fetchDetails: bool(process.env.LINKEDIN_FETCH_DETAILS, true),
      // f_WT: 1 on-site, 2 remote, 3 hybrid. Applied to remoteLocations only.
      remoteWorkType: process.env.LINKEDIN_REMOTE_WORK_TYPE ?? '2',
      /*
       * Remote searches are paged less deeply than local ones. They multiply
       * fastest — every market times every keyword — and one rate-limited
       * endpoint serves them all, so depth is traded for breadth.
       */
      remoteMaxPages: int(process.env.LINKEDIN_REMOTE_MAX_PAGES, 2),
      /*
       * f_E for the remoteKeywords searches only: 1 internship, 2 entry.
       *
       * These are the roles asked for with no experience, and the main
       * LINKEDIN_EXPERIENCE_LEVELS is set for the stack, which reaches up to
       * associate and mid-senior. Narrowing here is free — it happens on
       * LinkedIn's side, so nothing filtered out costs a request.
       */
      remoteKeywordExperience: process.env.LINKEDIN_REMOTE_KEYWORD_EXPERIENCE ?? '1,2',
      /*
       * One location per run instead of all of them: each run searches every
       * keyword in the next location, so a full cycle takes one run per
       * location and every run makes the same number of requests however
       * many locations are added. Off by default.
       *
       * The cycle sets how far back each search must look, so the window is
       * widened automatically to (locations + 1) x the run interval whenever
       * that exceeds LINKEDIN_TIME_WINDOW. See rotationWindow() in
       * src/sources/linkedinJobs.js.
       */
      rotateLocations: bool(process.env.LINKEDIN_ROTATE_LOCATIONS, false),
      /*
       * Minutes between runs, which the rotation's window is computed from.
       * The container's CRAWL_INTERVAL_MINUTES (10 in the Dockerfile) unless
       * set; set it to match LINKEDIN_JOBS_CRON when running --watch.
       */
      rotateIntervalMinutes: int(
        process.env.LINKEDIN_ROTATE_INTERVAL_MINUTES || process.env.CRAWL_INTERVAL_MINUTES,
        10,
      ),
    },

    /*
     * Hiring posts, found through a search engine's index rather than through
     * LinkedIn: logged-out post search does not exist, and every free way past
     * that wall needs an account cookie. Indexing lag means a post shows up
     * hours to days late, so this source runs wider time windows than the jobs
     * source and leans on dedup to stay quiet.
     */
    linkedin_posts: {
      enabled: bool(process.env.LINKEDIN_POSTS_ENABLED, true),

      /*
       * This source hunts a different market than the job boards do: local
       * hiring posts in Egypt, not remote work abroad. So it carries its own
       * keyword and location lists instead of inheriting JOB_KEYWORDS and the
       * remote markets, which would be dozens of billed queries per run aimed
       * at the wrong thing.
       *
       * Keep both lists short. Every keyword x location pair is one paid
       * query per page, per run.
       */
      keywords: postKeywords,
      locations: postLocations,

      /*
       * Its own schedule, because its rhythm is not the crawl's. A search
       * engine indexes a post hours to days after it is written, so asking
       * hourly buys nothing but billed queries — and this is the one source
       * that costs money per request.
       *
       * 03:30, read in config.schedule.timezone (Africa/Cairo) rather than in
       * whatever the host thinks local time is, because node-cron is handed
       * that timezone explicitly.
       */
      cron: process.env.LINKEDIN_POSTS_CRON || '30 3 * * *',

      /*
       * The same ad arrives again under a new link: a repost, a reshare, a
       * second recruiter pasting it. Its id and its author both change, so the
       * post's own words are compared instead (see postSimilarity.js).
       *
       * threshold is the share of the shorter post's words found in the other.
       * Lower it and more reposts are caught, but so are genuinely different
       * ads that happen to read alike. windowDays 0 turns the check off.
       */
      dedup: {
        windowDays: int(process.env.LINKEDIN_POSTS_DEDUP_DAYS, 30),
        threshold: float(process.env.LINKEDIN_POSTS_DEDUP_THRESHOLD, 0.8),
      },

      /*
       * The market here is local, so a post being on-site is not a reason to
       * drop it — which it is everywhere else, where the whole point is remote
       * work abroad. Only the keys that differ; everything else (tech,
       * seniority, contacts) still comes from FILTER_*.
       */
      filters: postFilters,

      /*
       * Which engine finds the permalinks: 'serper', 'searxng' or
       * 'duckduckgo'.
       *
       * Serper is the default because it is the only one that works from a
       * datacentre address — it is a paid Google API, sub-cent per query, and
       * answers JSON. SearXNG is free but has to run somewhere beside the
       * crawler and still scrapes upstreams that block cloud addresses.
       * DuckDuckGo needs nothing installed and blocks most non-browser
       * addresses outright.
       */
      engine: process.env.LINKEDIN_POSTS_ENGINE || 'serper',

      serperUrl: process.env.SERPER_URL || 'https://google.serper.dev/search',
      serperApiKey: process.env.SERPER_API_KEY || '',
      // Google's country code: what actually scopes results to a market. The
      // location term in the query only biases them.
      serperCountry: process.env.LINKEDIN_POSTS_COUNTRY || 'eg',
      serperLanguage: process.env.LINKEDIN_POSTS_LANGUAGE || 'en',

      searxngUrl: process.env.SEARXNG_URL || 'http://localhost:8080',
      // SearXNG UI language, e.g. en, en-US, ar. 'all' leaves it unset.
      language: process.env.LINKEDIN_POSTS_LANGUAGE || 'en',
      /*
       * `site:` keeps the engine on post permalinks; `hiring` biases the
       * ranking towards advertisements without demanding them.
       *
       * Deliberately this plain. Google's operators are available here and
       * measuring them showed they cannot be used: an OR group of employer
       * phrases ("we are hiring" OR "we're hiring" OR …) returned nothing at
       * all, and a single negative term — `-"#OpenToWork"` — took a query that
       * answered with ten results down to zero. Both are exact-phrase matches
       * against a page Google has indexed by its own rules, and on a corpus
       * this narrow they match nothing and eliminate everything.
       *
       * So the whole hiring-vs-seeking decision is made after the fact, by
       * hiringIntent() in filters.js, which reads the post body rather than
       * whatever phrasing Google happened to index.
       */
      queryTemplate:
        process.env.LINKEDIN_POSTS_QUERY ||
        'site:linkedin.com/posts "{keyword}" "{location}" hiring',
      /*
       * d (past day), w (week), m (month), '' (any age); each engine is handed
       * its own spelling.
       *
       * A week, not a day, and not by preference: `qdr:d` returns zero results
       * for this search. A niche this narrow does not get indexed daily, so a
       * day-old window is empty far more often than it is fresh. The run still
       * happens daily — dedup is what makes the overlap free, so each run
       * delivers only what was indexed since the last one.
       */
      timeWindow: process.env.LINKEDIN_POSTS_TIME_WINDOW ?? 'w',
      startupTimeWindow: process.env.LINKEDIN_POSTS_STARTUP_TIME_WINDOW ?? 'w',
      /*
       * The real freshness cut, and an exact one: Google dates a result by when
       * it indexed the page, so its week filter returns posts from last year —
       * but the activity id in every permalink encodes when the post was
       * written (see activityDate), so age is read from the post itself, not the
       * engine. Three days, not one, because Google indexes a post two to three
       * days after it is written and a tighter cut on this route is empty almost
       * always; the week-wide engine window stays for recall, and this drops the
       * stale majority it returns.
       */
      maxAgeDays: int(process.env.LINKEDIN_POSTS_MAX_AGE_DAYS, 3),
      // DuckDuckGo region bias, e.g. eg-en or xa-ar. Empty leaves it to the
      // engine, and SearXNG ignores it.
      region: process.env.LINKEDIN_POSTS_REGION ?? '',
      maxPages: int(process.env.LINKEDIN_POSTS_MAX_PAGES, 1),
      // One paced request per post, so the cap is what keeps enrichment from
      // eating the whole run.
      fetchDetails: bool(process.env.LINKEDIN_POSTS_FETCH_DETAILS, true),
      maxDetails: int(process.env.LINKEDIN_POSTS_MAX_DETAILS, 20),
      ...postSearchPlan('LINKEDIN_POSTS'),
    },

    /*
     * Hiring posts on X, found the same way as the LinkedIn ones: a search
     * engine finds the status permalinks. Unlike LinkedIn, X serves nothing to
     * a logged-out client, so there is no enrichment step — the search snippet
     * is all the filters get, which makes this the low-recall, zero-risk route.
     * x_session below is the other one.
     */
    x_posts: {
      // Off by default: every keyword x location pair is another paid query.
      enabled: bool(process.env.X_POSTS_ENABLED, false),
      keywords: list(process.env.X_POSTS_KEYWORDS, postKeywords),
      locations: list(process.env.X_POSTS_LOCATIONS, postLocations),
      // Daily, with linkedin_posts, for the same reason: billed per query,
      // reading an index that updates daily.
      cron: process.env.X_POSTS_CRON || '30 3 * * *',
      filters: postFilters,

      engine: process.env.X_POSTS_ENGINE || process.env.LINKEDIN_POSTS_ENGINE || 'serper',
      serperUrl: process.env.SERPER_URL || 'https://google.serper.dev/search',
      serperApiKey: process.env.SERPER_API_KEY || '',
      serperCountry: process.env.X_POSTS_COUNTRY || process.env.LINKEDIN_POSTS_COUNTRY || 'eg',
      serperLanguage: process.env.LINKEDIN_POSTS_LANGUAGE || 'en',
      searxngUrl: process.env.SEARXNG_URL || 'http://localhost:8080',
      language: process.env.LINKEDIN_POSTS_LANGUAGE || 'en',
      region: process.env.LINKEDIN_POSTS_REGION ?? '',

      // Plain, like the LinkedIn template and for the same reason: extra
      // operators on a corpus this narrow eliminate everything. Profile pages
      // that match are dropped by the source, which keeps /status/ links only.
      queryTemplate: process.env.X_POSTS_QUERY || 'site:x.com "{keyword}" "{location}" hiring',
      timeWindow: process.env.X_POSTS_TIME_WINDOW ?? 'w',
      startupTimeWindow: process.env.X_POSTS_STARTUP_TIME_WINDOW ?? 'w',
      // Freshness cut in days, read from each result's own date (the status id
      // encodes it), so it is exact rather than a guess at the engine's index
      // time. Three, not one, because Serper reads Google's index and Google
      // indexes an X post two to three days after it is written — a one-day cut
      // on this route is empty almost always. x_session, which reads X's live
      // search, is the source that can be truly same-day.
      maxAgeDays: int(process.env.X_POSTS_MAX_AGE_DAYS, 3),
      maxPages: int(process.env.X_POSTS_MAX_PAGES, 1),
      ...postSearchPlan('X_POSTS'),
    },

    /*
     * Hiring posts on X, read through the search X's own web app calls, signed
     * in as a real account by its browser cookies.
     *
     * Free, fresh and full-text — and against X's terms. Use a throwaway
     * account, never a personal one: X locks accounts it thinks are automated,
     * and the auth_token cookie is full control of the account (posting, DMs,
     * settings, past 2FA). Set the cookies in the host's secret settings, never
     * in a committed file.
     *
     * Fragile by nature: X rotates the GraphQL query id and the feature flags
     * the endpoint demands. Both are here so a rotation is an env edit, and the
     * client names which one broke instead of reporting an empty crawl.
     */
    x_session: {
      // Also off whenever either cookie is missing; see isEnabled().
      enabled: bool(process.env.X_SESSION_ENABLED, false),
      authToken: (process.env.X_AUTH_TOKEN ?? '').trim(),
      ct0: (process.env.X_CT0 ?? '').trim(),
      keywords: list(process.env.X_SESSION_KEYWORDS, postKeywords),
      locations: list(process.env.X_SESSION_LOCATIONS, postLocations),
      // Every six hours: fresh enough for posts, and few enough searches a day
      // that the account does not look like a bot.
      cron: process.env.X_SESSION_CRON || '0 */6 * * *',
      filters: postFilters,

      /*
       * X's own search syntax, which unlike Google's honours OR groups and
       * negatives. {location} is a plain term, not quoted: Egyptian posts often
       * name a city rather than the country, and the location filter reads the
       * post afterwards anyway. `since:` is appended from the time window.
       */
      queryTemplate:
        process.env.X_SESSION_QUERY ||
        '"{keyword}" (hiring OR "we are hiring" OR vacancy OR مطلوب) {location} -is:retweet',
      // Days back to search, via `since:`. One day for fresh posts only; the
      // startup run looks a little further, and maxAgeDays below is the exact
      // cut since `since:` is only date-granular.
      timeWindow: int(process.env.X_SESSION_TIME_WINDOW_DAYS, 1),
      startupTimeWindow: int(process.env.X_SESSION_STARTUP_TIME_WINDOW_DAYS, 2),
      // Exact age cap, read from each post's own timestamp (created_at, or the
      // status id). One day: only fresh posts, whatever `since:` let through.
      maxAgeDays: int(process.env.X_SESSION_MAX_AGE_DAYS, 1),
      maxPages: int(process.env.X_SESSION_MAX_PAGES, 1),
      pageSize: int(process.env.X_SESSION_PAGE_SIZE, 20),
      /*
       * A hard ceiling on requests per run, whatever the keyword lists grow to.
       * X allows roughly 50 searches per 15 minutes per account; staying far
       * below that is what keeps the account unremarkable.
       */
      maxRequests: int(process.env.X_SESSION_MAX_REQUESTS, 10),
      // Slower than the shared pace: a person does not search every 2.5s.
      delayMs: int(process.env.X_SESSION_DELAY_MS, 8000),

      apiBase: process.env.X_SESSION_API_BASE || 'https://x.com/i/api/graphql',
      // Rotated by X every few weeks. Find the current one in DevTools →
      // Network on x.com/search: the request named SearchTimeline.
      searchQueryId: process.env.X_SEARCH_QUERY_ID || 'AIdc203rPpK_k_2KWSdm7g',
      // The public token X's web app ships to every browser — not a secret.
      bearer:
        process.env.X_SESSION_BEARER ||
        'AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA',
      // JSON object. Empty uses the built-in set in xSessionClient.js; set it
      // when X starts answering "The following features cannot be null".
      features: process.env.X_SESSION_FEATURES || '',
    },

    /*
     * Wuzzuf, read through its sitemap. Its search pages sit behind Cloudflare
     * and answer 403 to anything that is not a browser; the sitemap and the
     * job pages are open, and robots.txt allows both.
     *
     * Every URL in that sitemap carries the same <lastmod> — the time the file
     * was generated — so it says nothing about which jobs are new. Freshness
     * comes from each page instead, and the slug filter below is what keeps a
     * run from fetching thousands of pages to find a handful.
     */
    wuzzuf: {
      // Egypt and Saudi Arabia only, both wanted markets; each job is placed
      // by the sitemap it came from.
      enabled: bool(process.env.WUZZUF_ENABLED, true),
      // Sitemap -> the country its jobs are in.
      sitemaps: {
        'https://wuzzuf.net/sitemap-job-1.xml': 'Egypt',
        // BOARDS_GULF: Wuzzuf's Saudi jobs too.
        ...(boardsGulf ? { 'https://wuzzuf.net/sitemap-saudi-job-1.xml': 'Saudi Arabia' } : {}),
      },
      /*
       * As the Android app does: read the sitemap index each run and take,
       * per market above, the job sitemap changed most recently. The list
       * above is the fallback when the index names none.
       */
      discover: bool(process.env.WUZZUF_DISCOVER_SITEMAPS, true),
      indexUrl: process.env.WUZZUF_SITEMAP_INDEX || 'https://wuzzuf.net/sitemap.xml',
      /*
       * A Wuzzuf job URL ends in the role, the company and the city, so the
       * slug is enough to decide whether a page is worth opening. Kept
       * separate from FILTER_TECH because it matches URL fragments, where
       * "node" appears as "nodejs" or "node-js" and never as a word.
       */
      slugTerms: list(process.env.WUZZUF_SLUG_TERMS, [
        'backend',
        'back-end',
        'laravel',
        'php',
        'nodejs',
        'node-js',
        'full-stack',
        'fullstack',
        'software-engineer',
        'software-developer',
        'web-developer',
      ]),
      /*
       * Pages fetched per run, after the slug filter and the store check.
       *
       * Ten rather than a larger number because each page is half a megabyte:
       * ten keeps a solo run near 80 MB, which is what the 128 MB heap cap on
       * this host is sized for. Raise it with the host, not on its own. The
       * ledger means a backlog is worked through across runs rather than in
       * one, so a low cap costs time, not coverage.
       */
      maxDetails: int(process.env.WUZZUF_MAX_DETAILS, 10),
      // Wuzzuf states an age, not a date. Anything older is dropped.
      maxAgeDays: int(process.env.WUZZUF_MAX_AGE_DAYS, 7),
      /*
       * A Wuzzuf job page is 500-670 KB — an order of magnitude past a
       * LinkedIn fragment — and the shared 15s default times out on them
       * often enough to end a run early, reported as a block with no HTTP
       * status behind it.
       */
      timeoutMs: int(process.env.WUZZUF_TIMEOUT_MS, 30000),
    },

    /*
     * Tanqeeb aggregates other boards — its cards name LinkedIn, Bayt, Wuzzuf
     * and Naukri Gulf — which makes it the only route to Bayt's listings that
     * does not mean defeating Cloudflare. Expect heavy overlap with the
     * LinkedIn source; the store's fingerprint check is what absorbs it.
     */
    tanqeeb: {
      enabled: bool(process.env.TANQEEB_ENABLED, true),
      /*
       * Search URL -> the country its results are in. Tanqeeb runs one site
       * per country, so its results need no location guessing.
       *
       * TANQEEB_SITES picks a subset by subdomain ("egypt,uae"); setting
       * TANQEEB_SEARCH_URL instead searches that one URL as TANQEEB_MARKET.
       */
      sites: tanqeebSites(),
      /*
       * One page per keyword per site: a search page is ~450 KB, and asking
       * for a second returned nothing the first had not already when this was
       * measured — while raising a run's peak from 87 MB to 106 MB.
       */
      maxPages: int(process.env.TANQEEB_MAX_PAGES, 1),
      maxAgeDays: int(process.env.TANQEEB_MAX_AGE_DAYS, 7),
      /*
       * A card carries no employer name, and the store's cross-source dedup is
       * built on company + title — so without this, every job Tanqeeb shares
       * with LinkedIn arrives twice.
       */
      fetchDetails: bool(process.env.TANQEEB_FETCH_DETAILS, true),
      maxDetails: int(process.env.TANQEEB_MAX_DETAILS, 10),
    },

    /*
     * Naukrigulf, read through the JSON endpoint its own pages call.
     *
     * Its HTML is a shell: 43 KB of script that fetches this endpoint and
     * draws the results client-side, with no job markup to parse. The
     * endpoint answers unauthenticated, robots.txt allows it, and one
     * response carries the employer, the description and a posted date — so
     * unlike Tanqeeb and Wuzzuf, nothing has to be opened per posting.
     *
     * Searches name no country. The endpoint takes a Location, but running
     * every keyword against every market multiplies requests for results the
     * location filter picks out of one unfiltered page just as well.
     */
    naukrigulf: {
      // Off: Gulf listings, and the search is Egypt only. Placed by
      // FILTER_LOCATIONS when turned on, since the search names no country.
      enabled: bool(process.env.NAUKRIGULF_ENABLED, false),
      searchUrl:
        process.env.NAUKRIGULF_SEARCH_URL || 'https://www.naukrigulf.com/spapi/jobapi/search',
      /*
       * The ids the site's own front-end sends with every call. Not a
       * credential and not a session — without them the endpoint answers 404
       * rather than 403. They are here rather than hardcoded so a change on
       * their side is an env edit, not a redeploy.
       */
      appId: process.env.NAUKRIGULF_APP_ID || '205',
      systemId: process.env.NAUKRIGULF_SYSTEM_ID || '2323',
      /*
       * Results come back newest-first, so one page per keyword is normally
       * the whole of what is new since the last pass. 50 is the largest page
       * the endpoint honours.
       */
      pageSize: int(process.env.NAUKRIGULF_PAGE_SIZE, 50),
      maxPages: int(process.env.NAUKRIGULF_MAX_PAGES, 1),
      maxAgeDays: int(process.env.NAUKRIGULF_MAX_AGE_DAYS, 7),
      // A JSON page of 50 jobs is ~150 KB and answers fast; the shared
      // default is plenty, and it is here to be raised if that changes.
      timeoutMs: int(process.env.NAUKRIGULF_TIMEOUT_MS, 15000),
    },

    /*
     * Recruitera, the ATS behind some eighty employers' career pages — Paymob,
     * Fawry, Banque Misr, ElSewedy, Hassan Allam — read through the public
     * JSON API those pages call. Companies come from the sitemap, so a new
     * employer is picked up without being listed here.
     *
     * Local hiring, like the posts sources: a hybrid role in Cairo is the
     * point, not a reason to drop it. The category decides what counts as
     * tech, and the job's own country decides where it is.
     */
    recruitera: {
      enabled: bool(process.env.RECRUITERA_ENABLED, true),
      sitemapUrl: process.env.RECRUITERA_SITEMAP_URL || 'https://app.recruitera.ai/sitemap.xml',
      apiBase: process.env.RECRUITERA_API_BASE || 'https://app.recruitera.ai/api/public/v1',
      // Career-site slugs to crawl even if the sitemap leaves them out, e.g.
      // "paymob" from app.recruitera.ai/careers/paymob.
      extraSites: list(process.env.RECRUITERA_EXTRA_SITES, []),
      // Slugs never crawled. Any slug starting "test" is skipped regardless,
      // and generated QA titles are dropped wherever they appear.
      // recutira is Recruitera's own demo site, labelled "orange".
      excludeSites: list(process.env.RECRUITERA_EXCLUDE_SITES, ['recutira']),
      // Recruitera's own job categories, by name. Every site shares one
      // vocabulary; these are its two tech ones.
      categories: list(process.env.RECRUITERA_CATEGORIES, [
        'IT/Software Development',
        'Engineering - Telecom/Technology',
      ]),
      // ISO country codes, read off each job: Egypt only. Not the company's
      // country: an Egyptian company's London role is dropped, a foreign
      // one's Cairo role is kept.
      countries: list(process.env.RECRUITERA_COUNTRIES, boardsGulf ? ['EG', 'SA', 'AE'] : ['EG']).map(
        (code) => code.toUpperCase(),
      ),
      // Cursor pages of 12 per category per site; no site comes close to 5.
      maxPages: int(process.env.RECRUITERA_MAX_PAGES, 5),
      // Job pages opened per run, after the store and the ledger have had
      // their say. Anything past it waits for the next run, not lost.
      maxDetails: int(process.env.RECRUITERA_MAX_DETAILS, 60),
      // Recruitera keeps jobs listed for months. A job whose date says it is
      // older than this is dropped; one with no date at all is still sent.
      maxAgeDays: int(process.env.RECRUITERA_MAX_AGE_DAYS, 7),
      timeoutMs: int(process.env.RECRUITERA_TIMEOUT_MS, 15000),
      /*
       * Only the keys that differ from `filters`; seniority, ineligible and
       * blocked locations still come from FILTER_*.
       *
       * tech and titleTerms are empty because the category already says the
       * job is tech — FILTER_TECH would narrow it to the Laravel/Node stack.
       * RECRUITERA_FILTER_TITLE narrows it again for anyone who wants that.
       */
      filters: {
        remoteOnly: bool(process.env.RECRUITERA_REMOTE_ONLY, false),
        tech: [],
        titleTerms: list(process.env.RECRUITERA_FILTER_TITLE, []),
        locations: [],
      },
    },

    /*
     * jobs.workable.com, the cross-company board of every Workable customer,
     * read through the JSON endpoint its own search page calls. One request
     * per keyword x location page, and the response carries the employer, the
     * description and the posted date.
     */
    workable: {
      enabled: bool(process.env.WORKABLE_ENABLED, true),
      searchUrl: process.env.WORKABLE_SEARCH_URL || 'https://jobs.workable.com/api/v1/jobs',
      // The job sources' lists unless given its own. Each keyword x location
      // is one request a page.
      keywords: list(process.env.WORKABLE_KEYWORDS, null),
      locations: list(process.env.WORKABLE_LOCATIONS, null),
      // Searched after them while BOARDS_GULF is on, one page each.
      gulfLocations: list(process.env.WORKABLE_GULF_LOCATIONS, ['Saudi Arabia', 'United Arab Emirates']),
      // 20 jobs a page. Searches are narrowed to day_range on Workable's
      // side, so one page is usually the whole of it.
      maxPages: int(process.env.WORKABLE_MAX_PAGES, 2),
      // Sent as day_range, so Workable only returns jobs this recent.
      maxAgeDays: int(process.env.WORKABLE_MAX_AGE_DAYS, 7),
      timeoutMs: int(process.env.WORKABLE_TIMEOUT_MS, 15000),
    },

    /*
     * VerteX Technologies' own vacancies (vertextech-eg.com), read through
     * its sitemap: one request, then one page per vacancy never seen before.
     * Undated, so the first run records what is open and sends nothing.
     */
    vertex: {
      enabled: bool(process.env.VERTEX_ENABLED, true),
      sitemapUrl: process.env.VERTEX_SITEMAP_URL || 'https://vertextech-eg.com/sitemap.xml',
      // New vacancy pages opened per run; the rest wait for the next one.
      maxDetails: int(process.env.VERTEX_MAX_DETAILS, 20),
      baseline: bool(process.env.VERTEX_BASELINE, true),
      timeoutMs: int(process.env.VERTEX_TIMEOUT_MS, 15000),
    },

    /*
     * Career boards of named companies on Lever, Greenhouse, Ashby, BambooHR,
     * Recruitee, Teamtailor, SuccessFactors, Breezy and Zoho Recruit, read
     * through each platform's public feed. For the employers no board search
     * reaches; Workable customers are already covered by the workable source.
     */
    ats: {
      enabled: bool(process.env.ATS_ENABLED, true),
      // platform:slug, from the board's own address: jobs.lever.co/klivvr is
      // lever:klivvr. An optional third part names the company for feeds
      // that do not: bamboohr:instabug:Instabug. A Teamtailor slug is its
      // subdomain or its own host, and so is a Zoho one (zohomea.zohorecruit.in
      // and vxlabs.zohorecruit.eu are off .com); a SuccessFactors one is host/companyId[/unitId/Place]
      // (see atsParser).
      boards: list(process.env.ATS_BOARDS, [
        'lever:klivvr:Klivvr',
        'ashby:thndr:Thndr',
        'greenhouse:tamara',
        'bamboohr:instabug:Instabug',
        'bamboohr:bosta:Bosta',
        'recruitee:elmenus',
        'teamtailor:careers.axisapp.com:Axis',
        'teamtailor:propertyfinder:Property Finder',
        'teamtailor:supersub:Supersub',
        'teamtailor:convertedin:Convertedin',
        'teamtailor:dubizzle:Dubizzle',
        'successfactors:career5.successfactors.eu/capgemitecP3/15638549/Egypt:Capgemini',
        'lever:Yassir:Yassir',
        'lever:aleph:Aleph',
        'ashby:hirehangar:Hire Hangar',
        'ashby:talentsafari:Talent Safari',
        'greenhouse:agoda',
        'greenhouse:decimainternational',
        'greenhouse:wppmedia',
        'teamtailor:issinternational:ISS International',
        'breezy:almentor',
        'breezy:digisay',
        'breezy:manara',
        'breezy:takwene',
        'breezy:ontrac-solutions-llc',
        'breezy:plat4mation',
        'zoho:careers.migrationit.com:MigrationIT',
        'zoho:fixedmea:Fixed Solutions',
        'zoho:fawrymicrofinance:FawryMSME Finance',
        'zoho:leadintop:LeadInTop',
        'zoho:qutip:QuTip',
        'zoho:snap-14:SNAP14 Production',
        'zoho:zohomea.zohorecruit.in:Zoho MEA',
        'zoho:coorb:CoorB',
        'zoho:gotocme:CME',
        'zoho:centrocdx:Centro CDX',
        'zoho:dotit:Dot IT',
        'zoho:sajoom:Impact Sustainability',
        'zoho:seenapay:Seena Pay',
        'zoho:vxlabs.zohorecruit.eu:VxLabs',
        'zoho:garageilla:Garage ILLA',
        'zoho:nacita:Nacita',
        'zoho:proven-sa:Proven Arabia',
        'zoho:luckyegypt:Lucky',
        // Remote from anywhere / EMEA, Egypt included.
        'greenhouse:canonical:Canonical',
        'ashby:constructor:Constructor',
        'greenhouse:automatticcareers:Automattic',
        'greenhouse:xapo61:Xapo Bank',
        // Saudi / UAE employers (the Android app's list): kept with BOARDS_GULF on.
        // A Workday slug is host/tenant/site[?search]; an Oracle HCM one is host/siteNumber.
        'smartrecruiters:ArchirodonGroup:Archirodon',
        'workday:wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi:Yokogawa',
        'oracle:eghj.fa.em2.oraclecloud.com/CX_1:NHC',
      ]),
      // Boards keep jobs open for months. Older than this is dropped; an
      // undated job (BambooHR dates none) is kept and dedup sends it once.
      maxAgeDays: int(process.env.ATS_MAX_AGE_DAYS, 14),
      // Per-board cutoffs that override maxAgeDays, as platform:slug:days
      // (the board's own platform:slug from ATS_BOARDS, without its name).
      // MigrationIT keeps filled and months-old openings on its page, so only
      // the last three days of it are wanted.
      boardMaxAgeDays: list(process.env.ATS_BOARD_MAX_AGE_DAYS, [
        'zoho:careers.migrationit.com:3',
      ]),
      // Per request. Capgemini's SuccessFactors listing is 15 MB and takes
      // 20-40 seconds; the JSON feeds answer in one or two.
      timeoutMs: int(process.env.ATS_TIMEOUT_MS, 90000),
    },

    /*
     * AI data work: labelling, annotation, transcription, and rating or
     * evaluating model output, on audio, images, video and text, Arabic
     * especially. Searched on jobs.workable.com with its own keywords, plus
     * any vendor boards named in AI_DATA_BOARDS (see src/sources/aiData.js).
     */
    ai_data: {
      enabled: bool(process.env.AI_DATA_ENABLED, true),
      searchUrl: process.env.AI_DATA_SEARCH_URL || 'https://jobs.workable.com/api/v1/jobs',
      // Each keyword x location is one request a page.
      keywords: list(process.env.AI_DATA_KEYWORDS, [
        'Data Annotation',
        'Arabic Annotator',
        'Audio Annotation',
        'Image Annotation',
        'Image Labeling',
        'Transcription',
        'AI Trainer',
        'Arabic AI Trainer',
        'Arabic Linguist',
        'AI Rater',
      ]),
      // "Anywhere" searches with no location: where most of this work is
      // listed. What it returns is kept only if remote (see placeWanted).
      locations: list(process.env.AI_DATA_LOCATIONS, ['Egypt', 'Anywhere']),
      /*
       * Vendor career boards, platform:slug[:Name] as in ATS_BOARDS. Empty by
       * default: name the vendors you trust, e.g.
       * AI_DATA_BOARDS=lever:toloka:Toloka,greenhouse:welocalize:Welocalize
       * A wrong or renamed slug only logs a warning and is skipped.
       */
      boards: list(process.env.AI_DATA_BOARDS, []),
      // Where an on-site job may be. Remote ones are kept wherever they are.
      places: list(process.env.AI_DATA_PLACES, egyptPlaces),
      maxPages: int(process.env.AI_DATA_MAX_PAGES, 1),
      maxAgeDays: int(process.env.AI_DATA_MAX_AGE_DAYS, 7),
      // Its own pace: Workable and the ATS feeds are not LinkedIn, and the
      // crawl-wide REQUEST_DELAY_MS (15 s in the container) would make
      // sixteen searches take four minutes.
      delayMs: int(process.env.AI_DATA_DELAY_MS, 2000),
      timeoutMs: int(process.env.AI_DATA_TIMEOUT_MS, 20000),
      /*
       * Only the keys that differ from `filters`. Blocked locations,
       * ineligible phrases and seniority still come from FILTER_*.
       *
       * The title decides: a vendor's board lists engineers and sales too,
       * and a Workable search for "Transcription" returns medical scribes
       * alongside labelling gigs. tech is empty because these jobs name no
       * stack; locations is empty because the source has already placed
       * each job (placeWanted); remoteOnlyTitles is empty because an
       * on-site Arabic annotation desk in Cairo is wanted here.
       *
       * Generalist work only: titleExclude drops the specialist "AI trainer"
       * gigs tied to a field that needs a degree (medicine, pharmacy, law,
       * finance, the sciences, civil / mechanical / electrical engineering).
       * Software and coding gigs are kept, as is image labelling that names
       * no such field. And Arabic, or Arabic with English:
       * an ad naming another language (otherLanguages) is dropped unless it
       * names Arabic too (languages). An ad naming no language at all is a
       * general one and is kept. English is in neither list on purpose.
       */
      filters: {
        remoteOnly: bool(process.env.AI_DATA_REMOTE_ONLY, false),
        tech: [],
        locations: [],
        remoteOnlyTitles: [],
        titleTerms: list(process.env.AI_DATA_FILTER_TITLE, [
          'annotation',
          'annotator',
          'annotators',
          'data labeling',
          'data labelling',
          'data labeler',
          'data labeller',
          'labeling specialist',
          'labelling specialist',
          'image labeling',
          'image labelling',
          'image labeler',
          'image labeller',
          'image tagging',
          'image tagger',
          'photo tagging',
          'video labeling',
          'video labelling',
          'video tagging',
          'data tagging',
          'tagger',
          'transcription',
          'transcriptionist',
          'transcriber',
          'ai trainer',
          'ai tutor',
          'data trainer',
          'ai rater',
          'rater',
          'search quality',
          'ai evaluator',
          'evaluator',
          'ai data',
          'speech data',
          'audio data',
          'image data',
          'data collection',
          'rlhf',
          'prompt writer',
          'linguist',
          'language expert',
          'language specialist',
          'وسم البيانات',
          'وسم الصور',
          'تصنيف الصور',
          'تصنيف البيانات',
          'تعليق توضيحي',
          'تفريغ',
          'تقييم البيانات',
          'مدرب ذكاء اصطناعي',
        ]),
        // Matched against the title only: a body's "our engineering team" or
        // "no medical background needed" says nothing about the role.
        // Image labelling with no field named stays: "Medical Image
        // Annotator" goes, "Image Labeler" is kept.
        // Arabic terms match inside longer words, so they are the ones that
        // cannot collide with an everyday word ('طبي' is inside 'تطبيق').
        titleExclude: list(process.env.AI_DATA_FILTER_TITLE_EXCLUDE, [
          // Engineering by discipline, not the word itself: software and
          // coding gigs ("AI Trainer - Software Engineering") are wanted, and
          // so is an "Image Annotation Engineer". The ones that need a degree
          // in another field are not.
          'civil engineering',
          'civil engineer',
          'mechanical engineering',
          'mechanical engineer',
          // Bare: "Mechanical CAD&Simulation Engineer" names no "mechanical engineer".
          'mechanical',
          'cad',
          'electrical engineering',
          'electrical engineer',
          'chemical engineering',
          'chemical engineer',
          'biomedical',
          'math',
          'maths',
          'mathematics',
          'physics',
          'chemistry',
          'biology',
          'stem',
          'phd',
          'medical',
          'medicine',
          'doctor',
          'physician',
          'nurse',
          'nursing',
          'pharmacy',
          'pharmacist',
          'pharmacology',
          'pharmaceutical',
          'healthcare',
          'clinical',
          'radiology',
          'dental',
          'dentist',
          'legal',
          'law',
          'lawyer',
          'attorney',
          'finance',
          'financial',
          'accounting',
          'accountant',
          'economics',
          'هندسة مدنية',
          'هندسة ميكانيكية',
          'هندسة كهربائية',
          'مهندس مدني',
          'مهندس ميكانيكا',
          'مهندس كهرباء',
          'طبيب',
          'طبية',
          'صيدل',
          'تمريض',
          'قانون',
          'محاسب',
        ]),
        languages: list(process.env.AI_DATA_LANGUAGES, arabicTerms),
        // 'polish' is left out: far more often a verb than a language.
        otherLanguages: list(process.env.AI_DATA_OTHER_LANGUAGES, [
          'french',
          'german',
          'spanish',
          'italian',
          'portuguese',
          'dutch',
          'russian',
          'turkish',
          'persian',
          'farsi',
          'hebrew',
          'hindi',
          'urdu',
          'bengali',
          'punjabi',
          'tamil',
          'telugu',
          'japanese',
          'korean',
          'chinese',
          'mandarin',
          'cantonese',
          'vietnamese',
          'thai',
          'indonesian',
          'malay',
          'tagalog',
          'filipino',
          'swahili',
          'greek',
          'swedish',
          'norwegian',
          'danish',
          'finnish',
          'czech',
          'romanian',
          'hungarian',
          'ukrainian',
          'الفرنسية',
          'الألمانية',
          'الالمانية',
          'الإسبانية',
          'الاسبانية',
        ]),
      },
    },

    /*
     * ---- Ported from the Android app ----
     *
     * Each has its own pace (delayMs), not LinkedIn's REQUEST_DELAY_MS. The
     * worldwide ones post only jobs that are remote and open to Egypt, or
     * visa-sponsored, in English, at the CV's stack and level, and only the
     * best CV fits (ABROAD_MIN_SCORE, ABROAD_MAX_PER_RUN). Their rhythm comes
     * from docker-crawl-loop.sh's lanes (CRAWL_PERIODIC_SOURCES) or --watch's
     * per-source cron below.
     */

    // Dr.Job (drjobpro.com, drjobs.ae): Egypt, and Saudi/UAE with BOARDS_GULF.
    drjob: {
      enabled: bool(process.env.DRJOB_ENABLED, true),
      keywords: list(process.env.DRJOB_KEYWORDS, null),
      maxAgeDays: int(process.env.DRJOB_MAX_AGE_DAYS, 7),
      maxDetails: int(process.env.DRJOB_MAX_DETAILS, 10),
      delayMs: int(process.env.DRJOB_DELAY_MS, 2500),
      timeoutMs: int(process.env.DRJOB_TIMEOUT_MS, 20000),
    },

    // Himalayas, Jobicy, Remotive, We Work Remotely, RemoteOK, Working Nomads, Arbeitnow, Landing.jobs, Remote Rocketship.
    remote_feeds: {
      enabled: bool(process.env.REMOTE_FEEDS_ENABLED, true),
      // Feed ids switched off: himalayas, jobicy, remotive, wwr, remoteok, rocketship.
      off: list(process.env.REMOTE_FEEDS_OFF, []),
      maxAgeDays: int(process.env.REMOTE_FEEDS_MAX_AGE_DAYS, 14),
      delayMs: int(process.env.REMOTE_FEEDS_DELAY_MS, 1500),
      timeoutMs: int(process.env.REMOTE_FEEDS_TIMEOUT_MS, 30000),
      cron: process.env.REMOTE_FEEDS_CRON || '15 */3 * * *',
    },

    // Jobgether's public search API, made for crawlers: remote jobs open to Egypt.
    jobgether: {
      enabled: bool(process.env.JOBGETHER_ENABLED, true),
      keywords: list(process.env.JOBGETHER_KEYWORDS, [
        'Backend Developer', 'PHP Developer', 'Laravel Developer', 'Node.js Developer', 'Full Stack Developer',
      ]),
      maxAgeDays: int(process.env.JOBGETHER_MAX_AGE_DAYS, 14),
      maxDetails: int(process.env.JOBGETHER_MAX_DETAILS, 15),
      // robots.txt asks for two seconds between requests.
      delayMs: Math.max(2000, int(process.env.JOBGETHER_DELAY_MS, 2000)),
      timeoutMs: int(process.env.JOBGETHER_TIMEOUT_MS, 20000),
      cron: process.env.JOBGETHER_CRON || '25 */3 * * *',
    },

    // Greenhouse, Lever and Ashby boards of companies in Europe and the UK known to sponsor visas.
    sponsor_boards: {
      enabled: bool(process.env.SPONSOR_BOARDS_ENABLED, true),
      boards: list(process.env.SPONSOR_BOARDS, [
        'greenhouse:monzo:Monzo', 'greenhouse:deliveroo:Deliveroo', 'greenhouse:tide:Tide',
        'greenhouse:gocardless:GoCardless', 'greenhouse:wise:Wise', 'greenhouse:intercom:Intercom',
        'greenhouse:adyen:Adyen', 'greenhouse:catawiki:Catawiki', 'greenhouse:celonis:Celonis',
        'greenhouse:n26:N26', 'greenhouse:hellofresh:HelloFresh', 'greenhouse:getyourguide:GetYourGuide',
        'greenhouse:contentful:Contentful', 'greenhouse:sumup:SumUp', 'greenhouse:trivago:trivago',
        'greenhouse:datadog:Datadog', 'greenhouse:cloudflare:Cloudflare', 'greenhouse:gitlab:GitLab',
        'greenhouse:elastic:Elastic', 'lever:spotify:Spotify', 'lever:swile:Swile', 'lever:veepee:Veepee',
        'ashby:paddle:Paddle', 'ashby:pennylane:Pennylane', 'ashby:synthesia:Synthesia',
        'ashby:supabase:Supabase', 'ashby:linear:Linear', 'ashby:doctolib:Doctolib',
      ]),
      maxAgeDays: int(process.env.SPONSOR_BOARDS_MAX_AGE_DAYS, 30),
      delayMs: int(process.env.SPONSOR_BOARDS_DELAY_MS, 1000),
      timeoutMs: int(process.env.SPONSOR_BOARDS_TIMEOUT_MS, 30000),
      cron: process.env.SPONSOR_BOARDS_CRON || '35 */3 * * *',
    },

    // Hacker News' monthly "Who is hiring?" thread; "VISA" in an ad's header counts as sponsorship.
    hn_hiring: {
      enabled: bool(process.env.HN_HIRING_ENABLED, true),
      delayMs: int(process.env.HN_HIRING_DELAY_MS, 1000),
      timeoutMs: int(process.env.HN_HIRING_TIMEOUT_MS, 20000),
      cron: process.env.HN_HIRING_CRON || '45 8 * * *',
    },

    // Adzuna, Jooble and Careerjet for the UK, the EU, Australia and Canada; each only once its free key is set.
    job_apis: {
      enabled: bool(process.env.JOB_APIS_ENABLED, true),
      adzunaId: process.env.ADZUNA_APP_ID || '',
      adzunaKey: process.env.ADZUNA_APP_KEY || '',
      joobleKey: process.env.JOOBLE_API_KEY || '',
      careerjetKey: process.env.CAREERJET_API_KEY || '',
      maxAgeDays: int(process.env.JOB_APIS_MAX_AGE_DAYS, 7),
      delayMs: int(process.env.JOB_APIS_DELAY_MS, 1000),
      timeoutMs: int(process.env.JOB_APIS_TIMEOUT_MS, 20000),
      cron: process.env.JOB_APIS_CRON || '5 9,21 * * *',
    },

    // jobright.ai's US category pages: remote-from-anywhere or H1B-sponsored jobs that fit the CV.
    jobright: {
      enabled: bool(process.env.JOBRIGHT_ENABLED, true),
      maxAgeDays: int(process.env.JOBRIGHT_MAX_AGE_DAYS, 1),
      delayMs: int(process.env.JOBRIGHT_DELAY_MS, 2000),
      timeoutMs: int(process.env.JOBRIGHT_TIMEOUT_MS, 20000),
      cron: process.env.JOBRIGHT_CRON || '50 * * * *',
    },

    // relocate.me: Europe and Japan, the move and the visa paid.
    relocateme: {
      enabled: bool(process.env.RELOCATEME_ENABLED, true),
      maxPages: int(process.env.RELOCATEME_MAX_PAGES, 5),
      // Relocation jobs stay open for months; older than this, likely filled.
      maxAgeDays: int(process.env.RELOCATEME_MAX_AGE_DAYS, 60),
      delayMs: int(process.env.RELOCATEME_DELAY_MS, 2000),
      timeoutMs: int(process.env.RELOCATEME_TIMEOUT_MS, 20000),
      cron: process.env.RELOCATEME_CRON || '55 */3 * * *',
    },

    /*
     * LinkedIn "Worldwide" with the remote filter: jobs open to anyone, or to
     * EMEA/Africa/Egypt/UTC+2. On, as in the Android app. Every request comes
     * out of the same LinkedIn patience the Egypt search needs, so it runs in
     * the periodic lane (hourly), not every pass; LINKEDIN_WORLDWIDE_ENABLED=false
     * turns it off.
     */
    linkedin_worldwide: {
      enabled: bool(process.env.LINKEDIN_WORLDWIDE_ENABLED, true),
      maxDetails: int(process.env.LINKEDIN_WORLDWIDE_MAX_DETAILS, 8),
      cron: process.env.LINKEDIN_WORLDWIDE_CRON || '40 * * * *',
    },

    /*
     * Glassdoor listings through Google (Serper): eight paid searches a run
     * (ten with BOARDS_GULF), under SERPER_DAILY_CAP. Off by default, as in
     * the Android app; run it once a day (CRAWL_DAILY_SOURCES).
     */
    glassdoor: {
      enabled: bool(process.env.GLASSDOOR_ENABLED, false),
      cron: process.env.GLASSDOOR_CRON || '10 7 * * *',
    },

    /*
     * Companies' own careers sites, the Android app's "Add a careers site":
     * one entry per company, id|Company|sitemap URL|job URL regex[|Location[|Market]].
     * A site's first run records what is open and sends none of it.
     */
    career_sites: {
      enabled: bool(process.env.CAREER_SITES_ENABLED, true),
      sites: (process.env.CAREER_SITES || '').split(/\n|;;/).map((s) => s.trim()).filter(Boolean),
      maxDetails: int(process.env.CAREER_SITES_MAX_DETAILS, 20),
      delayMs: int(process.env.CAREER_SITES_DELAY_MS, 1500),
      timeoutMs: int(process.env.CAREER_SITES_TIMEOUT_MS, 20000),
    },

    // Arabic freelance boards: gigs that name the CV's stack, posted under #Freelance.
    mostaql: {
      enabled: bool(process.env.MOSTAQL_ENABLED, true),
      maxAgeDays: int(process.env.MOSTAQL_MAX_AGE_DAYS, 3),
      maxDetails: int(process.env.MOSTAQL_MAX_DETAILS, 8),
      delayMs: int(process.env.MOSTAQL_DELAY_MS, 2000),
      timeoutMs: int(process.env.MOSTAQL_TIMEOUT_MS, 20000),
      cron: process.env.MOSTAQL_CRON || '*/30 * * * *',
      filters: { matcher: 'freelance', remoteOnlyTitles: [] },
    },
    khamsat: {
      enabled: bool(process.env.KHAMSAT_ENABLED, true),
      maxAgeDays: int(process.env.KHAMSAT_MAX_AGE_DAYS, 3),
      delayMs: int(process.env.KHAMSAT_DELAY_MS, 2000),
      timeoutMs: int(process.env.KHAMSAT_TIMEOUT_MS, 20000),
      cron: process.env.KHAMSAT_CRON || '*/30 * * * *',
      filters: { matcher: 'freelance', remoteOnlyTitles: [] },
    },
  },

  telegram: {
    enabled: bool(process.env.TELEGRAM_ENABLED, false),
    apiBase: process.env.TELEGRAM_API_BASE || 'https://api.telegram.org',
    botToken: process.env.TELEGRAM_BOT_TOKEN || '',
    chatId: process.env.TELEGRAM_CHAT_ID || '',
    timeoutMs: int(process.env.TELEGRAM_TIMEOUT_MS, 15000),
    sendDelayMs: int(process.env.TELEGRAM_SEND_DELAY_MS, 4000),
    maxMessageLength: 4096,
    // Commands such as /posts that run a source now. Only these user ids may
    // use them; anyone else is told their id and nothing more.
    commandsEnabled: bool(process.env.TELEGRAM_COMMANDS_ENABLED, true),
    commandUserIds: list(process.env.TELEGRAM_COMMAND_USER_IDS),
    // Shared with docker-crawl-loop.sh, which reads requests from it.
    controlDir: process.env.CRAWL_CONTROL_DIR || '/tmp/crawl-control',

    /*
     * A group with Topics: the topic each region's jobs are posted to (a topic
     * link, chatId:topicId, or a bare topic id inside TELEGRAM_CHAT_ID).
     * Blank: that region posts to TELEGRAM_CHAT_ID. See src/topics.js.
     */
    topics: {
      EGYPT: process.env.TELEGRAM_TOPIC_EGYPT || '',
      GULF: process.env.TELEGRAM_TOPIC_GULF || '',
      WORLDWIDE: process.env.TELEGRAM_TOPIC_WORLDWIDE || '',
      FREELANCE: process.env.TELEGRAM_TOPIC_FREELANCE || '',
    },
  },

  /*
   * Ranking and delivery, as the Android app's Settings → Ranking and muting.
   */
  ranking: {
    /*
     * Digest mode (off by default): jobs scoring at least digestThreshold go
     * out on their own; the rest wait and arrive as one message every
     * digestEveryHours, best first, grouped by region.
     */
    digest: bool(process.env.DIGEST_ENABLED, false),
    digestThreshold: Math.max(0, Math.min(100, int(process.env.DIGEST_THRESHOLD, 70))),
    digestEveryHours: Math.max(1, int(process.env.DIGEST_EVERY_HOURS, 3)),
    /*
     * Jobs abroad (worldwide sources): only those with a CV fit of at least
     * abroadMinScore are posted, the best abroadMaxPerRun a run. The rest are
     * stored, marked skipped with their score.
     */
    abroadMinScore: Math.max(0, int(process.env.ABROAD_MIN_SCORE, 55)),
    abroadMaxPerRun: Math.max(1, int(process.env.ABROAD_MAX_PER_RUN, 10)),
    // Friday's summary of the week, after 10:00 in schedule.timezone.
    weekly: bool(process.env.WEEKLY_SUMMARY_ENABLED, true),
    // A source that used to find jobs and parses nothing for this many clean runs in a row: one alert a day.
    quietStreak: Math.max(1, int(process.env.QUIET_SOURCE_STREAK, 3)),
  },

  /*
   * Saudi Arabia and the UAE on the job boards and company ATS (the Android
   * app's switch, on by default): Workable also searches both countries,
   * Tanqeeb reads its saudi. and uae. sites, Wuzzuf its Saudi sitemaps,
   * Recruitera keeps SA/AE jobs, Dr.Job its Saudi and UAE pages, and the
   * company boards place Riyadh/Dubai jobs in their country. Those jobs are
   * posted like Egypt's, tagged #Gulf, and must say they take someone
   * relocating (filters.gulfRequireOpen).
   */
  boardsGulf,

  /*
   * Post-crawl filtering, applied in the pipeline to every source.
   * An item must match tech AND location (and be remote, if remoteOnly), must
   * not match an excluded seniority term, and (per requireContact) may need an
   * email or phone.
   */
  filters: {
    enabled: bool(process.env.FILTER_ENABLED, true),

    tech: filterTech,

    /*
     * Egypt only: the country and the cities and districts a posting names
     * instead of it (egyptPlaces above). No borderless terms — a "Worldwide"
     * or "EMEA" posting is not a job in Egypt.
     *
     * Only consulted for a posting whose search named no market — everything
     * that came out of a named search (a JOB_LOCATIONS country, a Wuzzuf
     * sitemap, a Tanqeeb country site) is already placed by the search itself
     * (see locationAllowed). So this list is what judges an ATS board or a
     * Naukrigulf result: the posting has to name somewhere in Egypt.
     */
    locations: list(process.env.FILTER_LOCATIONS, egyptPlaces),

    /*
     * Off: on-site and hybrid jobs in Egypt and the Gulf are wanted as much as
     * remote ones. Where a remote posting is the only kind that could fit — a
     * Worldwide or EMEA search — LinkedIn already applies f_WT=2. Turn it on
     * to drop anything that does not say remote somewhere.
     */
    remoteOnly: bool(process.env.FILTER_REMOTE_ONLY, false),

    /*
     * Hiring posts only, never job-seeking ones. Applies to items of type
     * 'post' and is inert for everything else: a job board's listing is an
     * advertisement by definition, so there is nothing to decide.
     */
    hiringOnly: bool(process.env.FILTER_HIRING_ONLY, true),
    // true also drops a post whose snippet said neither way. Off by default —
    // a snippet cut short mid-sentence is the usual cause, and the post behind
    // it is often a real vacancy.
    hiringStrict: bool(process.env.FILTER_HIRING_STRICT, false),

    seniorityInclude: list(process.env.FILTER_SENIORITY_INCLUDE, [
      'junior',
      'jr',
      'mid',
      'mid-level',
      'midlevel',
      'mid level',
      'intermediate',
      'entry level',
      'entry-level',
    ]),

    /*
     * Off for every source by default; a source turns them on in its own
     * `filters` (ai_data does). titleExclude rejects a title naming any term;
     * the language rule rejects an item naming one of otherLanguages unless
     * it also names one of languages.
     */
    titleExclude: [],
    languages: [],
    otherLanguages: [],

    seniorityExclude: list(process.env.FILTER_SENIORITY_EXCLUDE, [
      'senior',
      'sr',
      'lead',
      'principal',
      'staff',
      'staff engineer',
      'founding',
      'head of',
      'director',
      'manager',
      'architect',
      'vp',
      'chief',
      'expert',
    ]),

    /*
     * The job title has to name the stack or a role the stack fits: backend,
     * full stack, or a generic software/web engineer title. Generic titles are
     * kept on purpose — many Laravel and Node roles are advertised as
     * "Software Engineer" — and FILTER_TECH still requires the posting to
     * mention the stack, so a "Salesforce Developer" does not get through.
     * Empty disables the rule.
     */
    titleTerms: list(process.env.FILTER_TITLE, [
      'laravel',
      'php',
      'symfony',
      'codeigniter',
      'backend',
      'back-end',
      'back end',
      'server side',
      'server-side',
      'node',
      'nodejs',
      'node.js',
      'nestjs',
      'express',
      'expressjs',
      'full stack',
      'full-stack',
      'fullstack',
      'mern',
      'mean stack',
      'software engineer',
      'software developer',
      'web developer',
      'web engineer',
      'application developer',
      'api developer',
      'api engineer',
      'application support',
      'golang',
      'go developer',
      'go engineer',
      'go backend',
      'data entry',
      'virtual assistant',
      'technical support',
      'data annotation',
      'data annotator',
      'data labeling',
      'data labelling',
      'ai trainer',
      'administrative assistant',
      'admin assistant',
      'content moderator',
      'content moderation',
      'transcription',
      'transcriptionist',
    ]),

    /*
     * Title terms that are only wanted remote.
     *
     * The general roles are searched Worldwide with f_WT=2, so the search
     * itself settles it — but the title terms above are global, and without
     * this an on-site Content Moderator in Cairo passes every gate through a
     * source that never asked for remote work. A stack role is exempt: an
     * on-site Laravel job in Cairo is wanted, a transcription desk is not.
     *
     * Matched against the title only, so a developer ad that happens to
     * mention transcription work is unaffected. Empty disables the rule.
     */
    remoteOnlyTitles: list(process.env.FILTER_REMOTE_ONLY_TITLES, [
      'technical support',
      'data annotation',
      'data annotator',
      'data labeling',
      'data labelling',
      'ai trainer',
      'administrative assistant',
      'admin assistant',
      'content moderator',
      'content moderation',
      'transcription',
      'transcriptionist',
    ]),

    /*
     * Countries never wanted, whatever the search. Matched against the title
     * and the posting's own location only — a description mentioning "our
     * team in India" says nothing about where the hire sits.
     */
    blockedLocations: list(process.env.FILTER_BLOCKED_LOCATIONS, [
      'india',
      'bengaluru',
      'bangalore',
      'hyderabad',
      'mumbai',
      'pune',
      'chennai',
      'delhi',
      'noida',
      'gurugram',
      'gurgaon',
      'kolkata',
      'pakistan',
      'karachi',
      'lahore',
      'islamabad',
      'rawalpindi',
      'nigeria',
      'lagos',
      'abuja',
    ]),

    /*
     * Phrases that make a posting one a candidate in Egypt cannot take:
     * residency or work-authorisation requirements, citizenship, clearance,
     * nationalisation quotas, and co-founder "roles". An application to any of
     * these is screened out by the knockout questions before anyone reads it.
     */
    ineligible: list(process.env.FILTER_INELIGIBLE, [
      // Work rights and visas.
      'authorized to work in', 'authorised to work in', 'legally authorized',
      'legally authorised', 'work authorization', 'work authorisation', 'eligible to work in',
      'right to work in', 'no visa sponsorship', 'unable to sponsor',
      'not able to sponsor', 'will not sponsor', 'cannot sponsor', 'without sponsorship',
      'us citizen', 'u.s. citizen', 'us citizenship', 'u.s. citizenship', 'green card',
      'security clearance', 'clearance required',
      // Already living there. Followed by an Egyptian place, these are ignored
      // ("must be based in Cairo" is where the person already is).
      'must be based in', 'must reside in', 'must be located in', 'must live in', 'must be a resident',
      'currently in ksa', 'currently in saudi', 'currently in the uae', 'currently in uae', 'currently in dubai',
      'currently residing in', 'currently living in',
      'inside the kingdom', 'within the kingdom', 'inside ksa', 'inside saudi arabia', 'inside the uae',
      'local candidates only', 'locals only', 'residents only',
      'must be in saudi arabia', 'must be in ksa', 'must be in the uae', 'must be in dubai',
      'must be in riyadh', 'must be in abu dhabi',
      'locally available', 'must be locally available', 'locally sponsored',
      // Iqama and visa status in the Gulf. Not "residence visa" or "iqama transfer": ads offer those.
      'transferable iqama', 'iqama transferable', 'transferrable iqama', 'valid iqama',
      'iqama required', 'iqama is required', 'iqama mandatory', 'must have iqama', 'must have a valid iqama',
      'valid residence permit', 'residence permit required', 'valid residency', 'residency required',
      'must have residency', 'resident visa holder',
      'on own visa', 'on your own visa', 'own sponsorship', 'valid uae visa',
      // Nationality.
      'saudi national', 'saudi nationals', 'saudis only', 'saudi only', 'saudization', 'saudisation',
      'uae national', 'uae nationals', 'emirati', 'emiratis only', 'emiratisation', 'emiratization',
      'qatari national', 'qatari nationals', 'gcc nationals', 'gcc national', 'nationals only',
      // The same, in Arabic.
      'للسعوديين فقط', 'سعوديين فقط', 'سعودي الجنسية', 'سعودية الجنسية', 'سعودة', 'للمواطنين فقط',
      'مواطنين فقط', 'إماراتي الجنسية', 'اماراتي الجنسية', 'مقيم في المملكة', 'مقيمين في المملكة',
      'داخل المملكة', 'إقامة قابلة للنقل', 'اقامة قابلة للنقل', 'إقامة سارية', 'اقامة سارية',
      'إقامة مطلوبة', 'اقامة مطلوبة', 'يشترط وجود إقامة', 'يشترط الإقامة',
      // Not a job.
      'co-founder', 'cofounder',
    ]),

    /*
     * The places that count as Egypt for the ineligible rule's exception:
     * "must be based in" followed by one of these is where the person already
     * is. Separate from `locations`, which a source may empty.
     */
    egyptPlaces,

    /*
     * A Saudi/UAE job (market Saudi Arabia / United Arab Emirates) has to say
     * it takes someone relocating — relocation, a sponsored visa or iqama, open
     * to all nationalities — or it is dropped as local-only.
     */
    gulfRequireOpen: bool(process.env.FILTER_GULF_REQUIRE_OPEN, true),

    // The most years of experience applied with; a posting asking for more is
    // dropped as 'experience'. 0 lets any through.
    maxYears: Math.max(0, int(process.env.FILTER_MAX_YEARS, 0)),

    // false: keep items that never state a level (most genuine junior posts).
    // true:  drop anything that does not explicitly say junior/mid.
    seniorityStrict: bool(process.env.FILTER_SENIORITY_STRICT, false),

    // 'all'  — keep only items containing an email or phone number
    // 'none' — keep everything that passed the other filters
    requireContact: (process.env.FILTER_REQUIRE_CONTACT || 'none').toLowerCase(),
  },

  limits: {
    maxItemsPerRun: int(process.env.MAX_ITEMS_PER_RUN, 100),
    retryAfterMinutes: int(process.env.RETRY_AFTER_MINUTES, 30),
    maxSendAttempts: int(process.env.MAX_SEND_ATTEMPTS, 5),
    /*
     * An empty database is seeded, not sent: see Pipeline.run().
     *
     * On by default because the failure it prevents is loud — a host with no
     * persistent disk re-sends the whole window on every restart — while the
     * cost is quiet and bounded: the first pass after a wipe delivers nothing,
     * and the next one is normal. Set false to send on an empty database.
     */
    seedFreshDatabase: bool(process.env.SEED_FRESH_DATABASE, true),
  },

  /*
   * Serper is the one remote billed per request, and the key has no spend cap
   * of its own, so the crawler keeps one. Shared by every source that searches
   * through it (linkedin_posts, x_posts).
   */
  serper: {
    apiKey: process.env.SERPER_API_KEY || '',
    /*
     * Billed queries a day, across sources, counted in schedule.timezone. A
     * source that reaches it stops and delivers what it found. The default
     * covers a daily run of both posts sources in pairs mode (33 + 33) with
     * room for a manual run; 0 means no cap.
     */
    dailyCap: Math.max(0, int(process.env.SERPER_DAILY_CAP, 100)),
    /*
     * Its own pace. Serper is an API, not a site watching for bots, so the
     * crawl-wide REQUEST_DELAY_MS (tuned for LinkedIn's guest pages) only
     * made a run of 33 queries wait eight minutes for nothing.
     */
    delayMs: Math.max(0, int(process.env.SERPER_DELAY_MS, 1000)),
    /*
     * Credits left below which a run sends an alert, read once a run from
     * Serper's account endpoint (free; no credit is spent). 0 turns it off.
     */
    lowBalance: Math.max(0, int(process.env.SERPER_LOW_BALANCE, 300)),
    accountUrl: process.env.SERPER_ACCOUNT_URL || 'https://google.serper.dev/account',
  },

  /*
   * Operational alerts — out of Serper credits, the daily cap reached — as a
   * Telegram message, at most once a day per kind. To the channel unless
   * TELEGRAM_ALERT_CHAT_ID names another chat (a private one with the bot).
   */
  alerts: {
    enabled: bool(process.env.ALERTS_ENABLED, true),
    chatId: (process.env.TELEGRAM_ALERT_CHAT_ID || '').trim() || null,
  },

  schedule: {
    cron: process.env.CRON_SCHEDULE || '0 * * * *',
    timezone: process.env.TZ || 'Africa/Cairo',
  },

  databasePath: resolve(process.env.DATABASE_PATH, './data/crawler.sqlite'),

  log: {
    level: (process.env.LOG_LEVEL || 'info').toLowerCase(),
    file: resolve(process.env.LOG_FILE, './logs/crawler.log'),

    /*
     * The decision log: every rejected, duplicate and accepted item, with the
     * reason, in the database's `decisions` table. Read it with --rejected,
     * GET /decisions or the bot's /rejected. Accepted rows can be turned off
     * alone, since they repeat what the channel already shows.
     */
    decisions: bool(process.env.LOG_DECISIONS, true),
    decisionsAccepted: bool(process.env.LOG_DECISIONS_ACCEPTED, true),
    decisionsRetentionDays: int(process.env.DECISIONS_RETENTION_DAYS, 14),
  },
};

export default config;
