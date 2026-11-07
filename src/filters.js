import config from './config.js';
import { containsAny, containsTerm, firstMatch, fold, termRegex } from './text.js';
import { isGulf } from './gulf.js';
import { years as yearsAsked } from './extract.js';
import { muteReason } from './mutes.js';
import { fitsFreelance } from './sources/freelance.js';

export { containsAny, firstMatch, fold };

/*
 * Post-crawl filtering.
 *
 * Applied centrally in the pipeline, so every source — jobs today, a paid posts
 * provider later — is filtered by the same rules with no extra work.
 *
 * An item is searched across title + company + location + excerpt, because a
 * job card carries almost nothing while a hiring post carries everything in its
 * body.
 */

// Deliberately loose: hiring posts write addresses as "mail me at x (at) y.com"
// as often as they write a clean address.
const EMAIL_RE =
  /[a-z0-9._%+-]+\s*(?:@|\(at\)|\[at\]|\s+at\s+)\s*[a-z0-9.-]+\s*(?:\.|\(dot\)|\[dot\])\s*[a-z]{2,}/gi;

/*
 * Egypt   +20 / 0 then 10|11|12|15 then 8 digits
 * Saudi   +966 / 0 then 5 then 8 digits
 * Generic international fallback: + then 10-15 digits with separators.
 */
const PHONE_PATTERNS = [
  // Egypt mobile: 01[0125] + 8 more digits, separators anywhere.
  /(?:\+?2[\s.-]?)?01[0125](?:[\s.-]?\d){8}(?!\d)/g,
  // Saudi mobile: 05 + 8 more digits, or +9665 + 8.
  /(?:\+?966[\s.-]?5|05)(?:[\s.-]?\d){8}(?!\d)/g,
  // Generic international fallback.
  /\+\d[\d\s().-]{8,16}\d(?!\d)/g,
];

/** Everything the filters read, folded once. */
function haystack(item) {
  return fold(
    [item.title, item.company, item.location, item.excerpt, item.seniority]
      .filter(Boolean)
      .join(' \n '),
  );
}

/**
 * The same text with the item's own `location` left out, for deciding where a
 * posting is.
 *
 * A posts source has no location to report and fills the field with the market
 * it searched for, which makes the location filter circular: it would be
 * confirming that a search for Egypt was a search for Egypt. Everything else
 * reads a location off the posting and says so through `market`, which settles
 * the question before this is reached.
 */
function locationHaystack(item) {
  return fold([item.title, item.company, item.excerpt].filter(Boolean).join(' \n '));
}

/**
 * What the role is called, as opposed to what its description mentions.
 *
 * Seniority is judged on this and not on the body, because a junior ad talks
 * about senior people constantly — "you will report to the Engineering
 * Manager", "our architect will mentor you", "expert knowledge not required"
 * — and matching those words against a description rejected most genuine
 * junior roles.
 */
function roleName(item) {
  // The title alone: LinkedIn's "Mid-Senior level" label is judged by the rule
  // above, and including it here would match the "senior" exclude term and
  // reject every posting carrying that label.
  return fold(item.title ?? '');
}

/*
 * The exception to that rule: an ad with a plain title ("Backend Developer")
 * whose body says what it is really after. Tight on purpose — it wants a
 * level attached to a role, so "senior developer" matches while "reporting to
 * a senior colleague" does not.
 */
const SENIOR_ROLE_RE =
  /\b(?:senior|sr\.?|lead|principal|staff|head)\s+(?:[a-z.+#/-]+\s+){0,3}?(?:engineer|developer|programmer|architect|dev)\b/i;

/*
 * A level range in a title — "Middle / Senior", "Mid-Senior", "Junior to Mid"
 * — is open to the lower level, so its senior half is not a reason to reject.
 * Only the range is taken out: "Mid/Senior Tech Lead" still says "lead".
 */
const LOWER_LEVEL = '(?:mid-level|junior|jr\\.?|middle|medior|mid|intermediate)';
const UPPER_LEVEL = '(?:senior|sr\\.?)';
const RANGE_JOIN = '\\s*(?:/|-|–|&|\\+|\\bto\\b|\\bor\\b)\\s*';
const LEVEL_RANGE_RE = new RegExp(
  `(?<![a-z0-9])(?:${LOWER_LEVEL}${RANGE_JOIN}${UPPER_LEVEL}|${UPPER_LEVEL}${RANGE_JOIN}${LOWER_LEVEL})(?![a-z0-9])`,
  'gi',
);

/** The text with any "Middle / Senior"-style range removed. */
export const withoutLevelRange = (text) => String(text ?? '').replace(LEVEL_RANGE_RE, ' ');

/**
 * The first ineligible phrase the text says, or null. A residency phrase that
 * points at Egypt ("must be based in Cairo", "currently residing in Egypt") is
 * where the person already is, so it does not count.
 */
export function cannotApply(text, terms, egyptPlaces = []) {
  for (const term of terms ?? []) {
    const needle = fold(term).trim();
    if (needle === '') continue;

    const re = new RegExp(termRegex(needle).source, 'gi');

    for (const match of text.matchAll(re)) {
      const end = match.index + match[0].length;
      if (!containsAny(text.slice(end, end + 40), egyptPlaces)) return term;
    }
  }

  return null;
}

/*
 * A Saudi/UAE job is only reachable from Cairo if it says it takes someone
 * relocating: relocation, a sponsored work visa or iqama, or open to all
 * nationalities. A Gulf job that says none of this is treated as local-only
 * ("gulf-local-only"). Phrase forms on purpose, so "no relocation" or "no visa
 * sponsorship" is not read as a yes.
 */
export const GULF_OPEN = [
  'relocation assistance', 'relocation support', 'relocation package', 'relocation provided',
  'relocation offered', 'relocation covered', 'we relocate', 'will relocate you',
  'visa sponsorship', 'visa sponsor', 'sponsor your visa', 'we sponsor', 'sponsorship provided',
  'sponsorship available', 'work visa provided', 'residence visa provided', 'we provide iqama',
  'iqama provided', 'residence visa', 'iqama transfer', 'visa covered', 'iqama covered',
  'open to all nationalities', 'all nationalities', 'all nationalities welcome',
  'any nationality', 'open to international', 'international candidates', 'overseas candidates',
  'candidates from abroad', 'expats welcome', 'expatriates',
  'استقدام', 'نوفر الإقامة', 'توفير الإقامة', 'نكفل الإقامة', 'تأشيرة عمل',
  'جميع الجنسيات', 'كافة الجنسيات', 'لجميع الجنسيات', 'للمقيمين وغير المقيمين',
];

/**
 * Cheap pre-check on a search card, before a detail page is spent on it
 * (the Android app's Filters.couldPass). Rejects only on what a description
 * cannot overturn: where the job is, and what the title says.
 */
export function couldPass(item, filters = filtersFor(item.source)) {
  const title = fold(item.title ?? '');
  const placed = fold([item.title, item.location].filter(Boolean).join(' '));
  const card = fold([item.title, item.company, item.location].filter(Boolean).join(' '));

  if (filters.blockedLocations?.length && containsAny(placed, filters.blockedLocations)) return false;
  if (filters.titleTerms?.length && !containsAny(title, filters.titleTerms)) return false;
  if (filters.locations?.length && !locationAllowed(item, card, filters)) return false;
  return !containsAny(withoutLevelRange(title), filters.seniorityExclude ?? []);
}

/** A short list for the decision log, so a 40-term filter does not flood a row. */
const listed = (terms, max = 8) =>
  terms.length > max
    ? `${terms.slice(0, max).join(', ')}, +${terms.length - max} more`
    : terms.join(', ');

export function extractEmails(text) {
  return [...new Set((text.match(EMAIL_RE) ?? []).map((value) => value.trim()))];
}

export function extractPhones(text) {
  const found = [];

  for (const pattern of PHONE_PATTERNS) {
    for (const match of text.match(pattern) ?? []) {
      const digits = match.replace(/\D/g, '');
      // Reject things that are really dates, salaries or years.
      if (digits.length >= 9 && digits.length <= 15) found.push(match.trim());
    }
  }

  return [...new Set(found)];
}

/*
 * Searches that name no country: whatever they return has to be read out of
 * the posting itself, because the search said nothing about where it is.
 */
// EMEA is here too: LinkedIn's EMEA geo is every country in the region, so its
// results include postings remote only inside Spain or Germany.
const BROAD_MARKETS = new Set(['remote', 'worldwide', 'anywhere', 'emea', '']);

/**
 * Where the posting is, decided by the search that found it where possible.
 *
 * A card names the country only sometimes — "Cairo, Egypt" does, "Chicago, IL"
 * does not — so a text rule alone rejects most of what a US or UK search
 * returns. When the search itself named a market, that is the answer, and only
 * a borderless search like "Remote" falls back to reading the text.
 */
export function locationAllowed(item, text = null, filters = config.filters) {
  const market = (item.market ?? '').trim().toLowerCase();

  if (market !== '' && !BROAD_MARKETS.has(market)) return true;

  const body = text ?? haystack(item);

  if (containsAny(body, filters.locations)) return true;

  /*
   * Nothing said where it is. For a source that searched a named market this
   * is the normal case rather than a failure — a company advertising to its
   * own city writes "Location: Remote" and never names the country, and
   * rejecting that loses most of what the search legitimately found.
   *
   * So when the source offers a list of markets that are *not* wanted, the
   * question becomes the other one: does this posting name somewhere else?
   * A post reading "Senior Laravel Developer. Location: Jordan" is rejected;
   * one that names nowhere is kept, on the strength of the search that found
   * it. Absent that list, silence is still a rejection.
   */
  if (!filters.excludeLocations?.length) return false;

  return !containsAny(body, filters.excludeLocations);
}

/*
 * How a posting says it is remote when no search filter has already settled
 * it. Deliberately narrow — "remote" as a word, plus the handful of phrases
 * that mean the same thing — because half a description mentions the word in
 * passing ("our remote-first culture" is a fine match; "remote branch" is not
 * a phrase job ads use).
 */
const REMOTE_RE =
  /\b(?:remote|remotely|fully[- ]remote|work from home|work[- ]from[- ]home|wfh|home[- ]based|telecommute|distributed team)\b/i;

// A hybrid role is on-site work with fewer days, and its ad says "remote" as
// often as a remote one does. Without this every hybrid posting reads as a
// match on the text path.
const HYBRID_RE = /\bhybrid\b/i;

/*
 * Hiring posts vs. job-seeking posts.
 *
 * A search for `"Laravel" "Egypt" hiring` returns both in one list, and on a
 * market where far more people are looking for work than advertising it, the
 * seekers are the majority of what comes back. The word "hiring" does not
 * separate them: a seeker writes "any hiring managers, please DM me" using
 * the same vocabulary as the employer they are addressing.
 *
 * What does separate them is grammatical voice — who wants whom. An employer
 * writes in the first person plural about wanting someone; a seeker writes in
 * the first person singular about being available.
 */

/*
 * Things only a job seeker writes, which reject outright rather than scoring.
 *
 * Two kinds. The hashtags LinkedIn itself attaches to a seeker's profile,
 * which mean nothing else on the platform. And statements in the first person
 * singular about wanting work — "I am looking for", "أنا بدور على" — which an
 * employer has no reason to write at all.
 *
 * These override employer phrasing rather than being weighed against it,
 * because a seeker's post is full of employer vocabulary: it is addressed to
 * employers, lists the roles it wants, and quotes the adverts it is answering.
 *
 * #Hiring is deliberately absent and is worth nothing either way — seekers tag
 * it to reach recruiters as often as employers tag it to be found.
 */
const SEEKER_DISQUALIFIERS = [
  '#opentowork',
  '#openforwork',
  '#open_to_work',
  '#lookingforjob',
  '#lookingforajob',
  'i am looking for',
  "i'm looking for",
  'i am currently looking',
  "i'm currently looking",
  'i am seeking',
  "i'm seeking",
  'i am available',
  "i'm available",
  'open to work',
  'open for work',
  'my cv',
  'my resume',
  'my experience is',
  'refer me',
  // Arabic. The colloquial forms matter more than the formal ones here: an
  // Egyptian seeker writes "بدور على", not "أبحث عن".
  'بدور على',
  'بدور علي',
  'بدوّر على',
  'أبحث عن عمل',
  'ابحث عن عمل',
  'أبحث عن فرصة',
  'ابحث عن فرصة',
  'أبحث عن وظيفة',
  'ابحث عن وظيفة',
  'خبرتي',
  'سيرتي الذاتية',
  'متاح للعمل',
  'متاحة للعمل',
  'أنا حالياً',
  'انا حاليا',
];

/**
 * First person plural, wanting someone. Arabic included: this is Egypt.
 *
 * Note what is *not* here. "فرصة عمل" — job opportunity — reads like an advert
 * and is not one: a seeker writes "بدور على فرصة عمل جديدة" far more often
 * than an employer announces one. Anything both sides say is worse than
 * useless, because it scores for the wrong side half the time.
 */
const EMPLOYER_TERMS = [
  'we are hiring',
  "we're hiring",
  'we are looking for',
  "we're looking for",
  'we need',
  'our team is looking',
  'our client is looking',
  'join our team',
  'join our growing team',
  'join us',
  'is hiring',
  'are hiring',
  'open position',
  'open positions',
  'open role',
  'job opening',
  'we have an opening',
  'vacancy',
  'vacancies',
  'send your cv',
  'send your updated cv',
  'send your resume',
  'send your updated resume',
  'share your cv',
  'send cv to',
  'apply now',
  'to apply',
  'interested candidates',
  'qualified candidates',
  'if you are interested',
  'dm me your cv',
  'send me your cv',
  // Arabic, in the spellings a hiring post actually uses.
  'مطلوب',
  'مطلوبين',
  'نبحث عن',
  'وظيفة شاغرة',
  'وظائف شاغرة',
  'للتقديم',
  'التقديم عن طريق',
  'ارسل السيرة الذاتية',
  'ترشيح',
];

/*
 * A mailbox named for hiring — hiring@, careers@, hr@ — which is where an
 * employer sends CVs and nowhere a seeker gives out. Counted as one more
 * employer term; it cannot overrule a seeker's own words.
 */
const HIRING_MAILBOX_RE =
  /(?<![a-z0-9._%+-])(?:hiring|careers?|jobs|hr|recruitment|recruiting|talent)@[a-z0-9.-]+\.[a-z]{2,}/i;

/**
 * Weaker seeker signals — each ambiguous alone, telling in aggregate. Weighed
 * against the employer terms rather than deciding on their own.
 */
const SEEKER_TERMS = [
  'looking for a job',
  'looking for a new job',
  'looking for a new opportunity',
  'looking for new opportunities',
  'looking for an opportunity',
  'open to opportunities',
  'open to new opportunities',
  'immediately available',
  'available immediately',
  'ready to join',
  'attached my cv',
  'here is my cv',
  'kindly refer',
  'any referral',
  'any leads',
  'i have experience',
  'years of experience',
  'fresh graduate',
  'recent graduate',
  'seeking a position',
  'seeking a new',
  'help me find',
  'appreciate any',
  // Arabic.
  'خريج',
  'حديث التخرج',
  'محتاج شغل',
  'ياريت حد',
  'برجاء الترشيح',
];

/**
 * Whether a post is an employer advertising or a person job-hunting.
 *
 * Three stages, because the two sides do not differ symmetrically. A seeker's
 * post is written *to* employers and is therefore full of employer vocabulary,
 * while an employer has no reason to write a seeker's first-person-singular
 * statements — so those are decisive on their own and are checked first.
 * What is left is weighed, and a genuine tie goes to the employer.
 *
 * @returns {'hiring'|'seeking'|'unclear'}
 */
export function hiringIntent(item, text = null) {
  const body = text ?? haystack(item);

  if (containsAny(body, SEEKER_DISQUALIFIERS)) return 'seeking';

  const employer =
    EMPLOYER_TERMS.filter((term) => containsTerm(body, term)).length +
    (HIRING_MAILBOX_RE.test(body) ? 1 : 0);
  const seeker = SEEKER_TERMS.filter((term) => containsTerm(body, term)).length;

  if (employer === 0 && seeker === 0) {
    // Said neither way — as a post about Laravel itself does, and as a snippet
    // cut short before the deciding sentence does.
    return 'unclear';
  }

  return seeker > employer ? 'seeking' : 'hiring';
}

/**
 * The filters that apply to one source.
 *
 * Every source is filtered by the same rules by default, because the point of
 * filtering centrally is that a new source needs no filtering code. A source
 * whose target genuinely differs — the posts source hunts a local market where
 * the job sources hunt remote work abroad — states only the keys it differs on.
 */
export function filtersFor(sourceKey) {
  const overrides = config.sources[sourceKey]?.filters;
  return overrides ? { ...config.filters, ...overrides } : config.filters;
}

/**
 * Whether the posting is remote.
 *
 * `item.remote` is set by a source that asked its provider for remote work
 * only — LinkedIn's f_WT=2 — and that answer is trusted outright. Anything
 * else has to say so in its own words.
 */
export function isRemote(item, text = null) {
  if (item.remote === true) return true;

  const body = text ?? haystack(item);

  if (HYBRID_RE.test(body)) return false;

  return REMOTE_RE.test(body);
}

/**
 * Decide whether an item should be stored and sent.
 *
 * @returns {{pass: boolean, reason: string|null, detail: string|null,
 *            contacts: {emails: string[], phones: string[]}}}
 */
export function evaluate(item) {
  const filters = filtersFor(item.source);
  const text = haystack(item);

  const contacts = {
    emails: extractEmails(text),
    phones: extractPhones(text),
  };

  // Every rejection carries what triggered it, for the decision log.
  const reject = (reason, detail = null) => ({ pass: false, reason, detail, contacts });

  if (!filters.enabled) {
    return { pass: true, reason: null, detail: null, contacts };
  }

  /*
   * LinkedIn's own "Seniority level" criterion, where the posting states one.
   * Its vocabulary is fixed: Internship, Entry level, Associate, Mid-Senior
   * level, Director, Executive.
   *
   * Only the top two are rejected outright. "Mid-Senior level" is not a
   * decision — employers here routinely tag a two-year role with it — so it is
   * handed to the title rules below, which can read what the job is actually
   * called. Rejecting it here would also undo f_E=4, which asks LinkedIn for
   * those postings in the first place.
   */
  if (item.seniority) {
    const level = item.seniority.toLowerCase();

    if (/director|executive/.test(level)) {
      return reject('seniority-level', `seniority: ${item.seniority}`);
    }
  }

  // Where the posting itself says it sits: the card's location and the title.
  // The description is left out, since "our team in India" is not a location.
  const placed = fold([item.title, item.location].filter(Boolean).join(' \n '));

  if (filters.blockedLocations?.length && containsAny(placed, filters.blockedLocations)) {
    return reject('location-blocked', `matched: ${firstMatch(placed, filters.blockedLocations)}`);
  }

  // What the person muted from Telegram (/mute_company, /mute_word).
  const muted = muteReason(item);
  if (muted) return reject(muted, item.company ?? item.title);

  /*
   * Arabic freelance gigs: the English title and stack lists would drop them
   * all, so the freelance matcher (English + Arabic stack) decides instead.
   */
  const freelance = filters.matcher === 'freelance';

  if (freelance) {
    if (!fitsFreelance(text)) return reject('stack', 'no CV stack, in English or Arabic');
  } else if (filters.titleTerms?.length && !containsAny(roleName(item), filters.titleTerms)) {
    return reject('title', `role "${roleName(item)}" has none of: ${listed(filters.titleTerms)}`);
  }

  if (filters.titleExclude?.length && containsAny(roleName(item), filters.titleExclude)) {
    return reject('title-excluded', `matched: ${firstMatch(roleName(item), filters.titleExclude)}`);
  }

  // A posting for another language's speakers, unless it wants a wanted
  // language too. One naming no language at all is a general one and passes.
  if (
    filters.otherLanguages?.length &&
    containsAny(text, filters.otherLanguages) &&
    !containsAny(text, filters.languages ?? [])
  ) {
    return reject('language', `matched: ${firstMatch(text, filters.otherLanguages)}`);
  }

  // Residency, authorisation, citizenship, nationality quotas: the knockout
  // questions reject the application before anyone reads it.
  // "Must be based in Cairo" is where the person already is: not a reason.
  const ineligible = cannotApply(text, filters.ineligible, filters.egyptPlaces ?? []);
  if (ineligible) return reject('ineligible', `matched: ${ineligible}`);

  if (filters.remoteOnly && !isRemote(item, text)) {
    return reject('not-remote', 'remote-only source');
  }

  /*
   * The same rule for one group of roles rather than the whole source: the
   * general roles — support, annotation, admin, moderation, transcription —
   * are only worth having from anywhere, while an on-site Laravel job in
   * Cairo is wanted. remoteOnly cannot express that, since it is per source
   * and every source carries both kinds.
   */
  if (
    filters.remoteOnlyTitles?.length &&
    containsAny(roleName(item), filters.remoteOnlyTitles) &&
    !isRemote(item, text)
  ) {
    const term = firstMatch(roleName(item), filters.remoteOnlyTitles);
    return reject('not-remote', `remote-only role: ${term}`);
  }

  if (!freelance && filters.tech.length > 0 && !containsAny(text, filters.tech)) {
    return reject('tech', `none of: ${listed(filters.tech)}`);
  }

  // A post claims no location of its own, so the field it does carry — the
  // market that was searched for — must not be what answers the question.
  const placeText = item.itemType === 'post' ? locationHaystack(item) : text;

  if (filters.locations.length > 0 && !locationAllowed(item, placeText, filters)) {
    const named = filters.excludeLocations?.length
      ? firstMatch(placeText, filters.excludeLocations)
      : null;
    return reject(
      'location',
      named ? `names excluded location: ${named}` : `none of: ${listed(filters.locations)}`,
    );
  }

  /*
   * A Saudi/UAE job has to say it takes someone relocating from Egypt
   * (relocation, a sponsored visa or iqama, open to all nationalities); a
   * silent one is local-only. The explicit residency and nationality locks are
   * already gone through the ineligible rule.
   */
  if (item.itemType !== 'post' && filters.gulfRequireOpen && isGulf(item.market) && !containsAny(text, GULF_OPEN)) {
    return reject('gulf-local-only', `${item.market}: no relocation, sponsorship or all-nationalities wording`);
  }

  /*
   * Posts only. A job board's listing is an advertisement by definition, so
   * there is nothing to decide; a post is whatever its author wrote, and half
   * of what a search for hiring terms returns is people looking for work.
   */
  if (item.itemType === 'post' && filters.hiringOnly) {
    const intent = hiringIntent(item, text);

    if (intent === 'seeking') {
      return reject('seeking-work', 'author is looking for work');
    }

    // Nothing in the snippet said either way. Dropped only on request, since
    // a truncated snippet is the usual cause and the post behind it may well
    // be a real vacancy.
    if (intent === 'unclear' && filters.hiringStrict) {
      return reject('intent-unclear', 'no hiring or seeking signal (strict)');
    }
  }

  // Seniority is a two-sided test: an explicitly senior role is rejected even
  // if the body also says "junior" somewhere. Read from the role name, plus the
  // one body pattern that names a senior role outright.
  // A level range ("Mid/Senior") is open to the lower level: only the range is taken out.
  const role = withoutLevelRange(roleName(item));

  if (filters.seniorityExclude.length > 0 && containsAny(role, filters.seniorityExclude)) {
    return reject('seniority-excluded', `matched: ${firstMatch(role, filters.seniorityExclude)}`);
  }

  const body = withoutLevelRange(item.excerpt ?? '');

  if (SENIOR_ROLE_RE.test(body)) {
    const phrase = body.match(SENIOR_ROLE_RE)?.[0];
    return reject('seniority-excluded-body', `body: ${phrase}`);
  }

  // The body is read here on purpose: "we are looking for a junior" in a
  // description is exactly the signal this test wants.
  if (filters.seniorityInclude.length > 0 && !containsAny(text, filters.seniorityInclude)) {
    // Nothing said either way. Strict mode drops it; otherwise keep it, since
    // plenty of genuine junior roles never spell out a level.
    if (filters.seniorityStrict) {
      return reject('seniority-unstated', 'no junior/entry signal (strict)');
    }
  }

  // A senior title is already gone, so this is "5+ years" in an otherwise fitting ad.
  if (filters.maxYears > 0) {
    const asked =
      item.yearsMin ?? yearsAsked([item.title, item.excerpt].filter(Boolean).join('\n'))?.min ?? null;

    if (asked != null && asked > filters.maxYears) {
      return reject('experience', `asks ${asked}+ years, more than ${filters.maxYears}`);
    }
  }

  const needsContact = filters.requireContact === 'all';

  if (needsContact && contacts.emails.length === 0 && contacts.phones.length === 0) {
    return reject('no-contact', 'no email or phone found');
  }

  return { pass: true, reason: null, detail: null, contacts };
}

export default evaluate;
