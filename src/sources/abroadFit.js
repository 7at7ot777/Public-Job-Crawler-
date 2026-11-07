import { containsAny, fold } from '../text.js';
import { boostScore, minYears, stackOf } from './cvStack.js';

/*
 * Jobs abroad — Europe, the UK, Canada, Australia, and the US boards — judged
 * for a backend developer in Cairo: remote work open to Egypt, or a visa
 * sponsored; English only; the CV's stack at its level. Every abroad source
 * asks blocked() for the hard rules, and the pipeline ranks what is left by
 * score(), delivering only the best fits (MIN_SCORE, MAX_PER_RUN). Ported
 * from the Android app's AbroadFit.kt.
 */

/** Below this, a job is stored but not posted. */
export const MIN_SCORE = 55;

/** Abroad jobs posted per source run, best first. */
export const MAX_PER_RUN = 10;

export const REMOTE_LANE = '🌍 Remote, open to Egypt';
export const VISA_LANE = '✈️ Visa sponsorship';

/* Remote work a person in Cairo can take: the text has to say so. */
export const WORLDWIDE = [
  'worldwide', 'anywhere in the world', 'work from anywhere', 'remote anywhere', 'fully distributed',
  'globally distributed', 'global team', 'global remote', 'remote global', 'international candidates',
  'outside the us', 'outside the u.s.', 'outside of the us', 'any country', 'any time zone', 'any timezone',
  'all time zones', 'emea', 'mena', 'middle east', 'africa', 'egypt', 'europe', 'european time',
  'european hours', 'cet', 'cest', 'eet', 'gmt+2', 'gmt+3', 'utc+2', 'utc+3', 'gulf hours',
];

/* A remote job only for people already in the US. */
export const US_ONLY = [
  'us-based', 'u.s.-based', 'us based', 'based in the us', 'based in the u.s.', 'based in the united states',
  'within the us', 'within the u.s.', 'within the united states', 'us only', 'u.s. only', 'us residents',
  'u.s. residents', 'w2', 'w-2', 'us time zones', 'us hours',
];

export const VISA_TERMS = [
  'visa sponsorship available', 'visa sponsorship is available', 'will sponsor', 'we sponsor',
  'sponsorship available', 'offer visa sponsorship', 'provide visa sponsorship', 'h1b sponsorship',
  'h-1b sponsorship', 'relocation assistance', 'relocation support', 'relocation package', 'relocation provided',
];

/* What makes a job abroad reachable without a visa: remote, and open to someone in Egypt. */
export const OPEN_TO_EGYPT = WORLDWIDE;

/* What makes it reachable with one. */
export const VISA_WORDS = [
  ...VISA_TERMS,
  'visa sponsorship', 'sponsorship provided', 'visa support', 'visa provided', 'visa paid', 'relocation bonus',
  'skilled worker visa', 'skilled worker sponsorship', 'blue card', 'eu blue card', 'work permit provided',
  'tier 2', 'lmia', 'tss 482', '482 visa', 'subclass 482', 'global talent', 'highly skilled migrant',
  'kennismigrant', 'we sponsor visas', 'sponsor your visa', 'sponsor work visas',
  // Set by the sponsor_boards source on every job from a company known to sponsor.
  'known visa sponsor',
];

/*
 * Remote or not, only for people already there. The shared ineligible list
 * covers "must be based in" and the like. Nothing that describes the company
 * ("a UK-based fintech"): only the candidate.
 */
export const REGION_LOCKED = [
  'uk only', 'u.k. only', 'eu only', 'europe only', 'eu residents', 'eu residents only', 'eu citizens',
  'eu citizenship', 'eu passport', 'eu work permit', 'uk residents', 'uk citizens',
  'candidates based in the uk', 'candidates based in the eu', 'candidates based in europe',
  'candidates based in canada', 'candidates based in australia',
  'residing in the eu', 'residing in europe', 'residing in the uk', 'residing in canada', 'residing in australia',
  'canadian residents', 'canadian citizen', 'canadian citizens', 'canadian citizenship',
  'australian residents', 'australian citizen', 'australian citizens', 'australian citizenship',
  'permanent resident', 'permanent residents', 'permanent residency', 'pr holder', 'pr holders',
  'citizen or pr', 'citizens or pr', 'citizens or permanent residents', 'valid work permit', 'existing work permit',
  'existing right to work', 'full working rights', 'unrestricted work rights', 'no relocation',
  'relocation not available', 'relocation is not available', 'not offering relocation',
  // US/Canada/North America: "remote" but only for people already there.
  'us and canada', 'u.s. and canada', 'us & canada', 'u.s. & canada', 'usa and canada',
  'united states and canada', 'us or canada', 'u.s. or canada', 'usa or canada',
  'united states or canada', 'us/canada', 'u.s./canada', 'usa/canada',
  'north america only', 'north american candidates', 'north american applicants',
  'based in north america', 'located in north america', 'within north america', 'must be in north america',
  'canada only', 'canadian applicants', 'canadian applicants only',
  'based in canada', 'located in canada', 'within canada', 'must be in canada',
];

// Not "polish": "attention to detail and polish" is English.
const LANGS =
  'german|dutch|french|swedish|danish|norwegian|finnish|italian|spanish|portuguese|czech|hungarian|romanian|greek|estonian|lithuanian|latvian|deutsch|nederlands|français|francais|svenska|dansk|norsk|suomi|polski';

// "fluent German", "business-level Dutch", "C1 French", "working knowledge of Swedish".
const LEVEL_THEN_LANG = new RegExp(
  `\\b(fluent|fluency in|native|native-level|business[- ]level|business fluent|professional|proficient|proficiency in|excellent|very good|good|strong|working knowledge of|c1|c2|b2)\\s+(?:command of\\s+|knowledge of\\s+|level\\s+|spoken\\s+and\\s+written\\s+)?(${LANGS})\\b`,
  'gi',
);

// "German is required", "Dutch language skills (C1) are a must", "French: mandatory".
const LANG_THEN_REQUIRED = new RegExp(
  `\\b(${LANGS})\\s+(?:language\\s+)?(?:skills?\\s+)?(?:\\(?(?:c1|c2|b2)\\)?\\s+)?(?:is\\s+|are\\s+|:\\s*)?(required|a must|mandatory|essential|needed|necessary)\\b`,
  'gi',
);

// "Fluent English and German", "fluency in German and English".
const WITH_ENGLISH = new RegExp(
  `\\b(fluent|fluency|native|proficient|proficiency|excellent)\\b[^.\\n]{0,30}\\b(english\\s+(?:and|&)\\s+(${LANGS})|(${LANGS})\\s+(?:and|&)\\s+english)\\b`,
  'gi',
);

const GERMAN_WORDS = /\b(deutschkenntnisse|sprachkenntnisse|fließend(?:es)? deutsch|verhandlungssicher)\b/i;

// A language that is a bonus is not a condition.
const ONLY_A_BONUS =
  /^[^.\n]{0,40}\b(nice to have|is a plus|a plus|bonus|an advantage|advantageous|preferred|not required|is not required|helpful|desirable)\b/i;

/** Whether the posting asks for a language other than English. */
export function needsOtherLanguage(text) {
  const body = String(text ?? '');
  if (GERMAN_WORDS.test(body)) return true;

  return [LEVEL_THEN_LANG, LANG_THEN_REQUIRED, WITH_ENGLISH].some((re) =>
    [...body.matchAll(re)].some((match) => !ONLY_A_BONUS.test(body.slice(match.index + match[0].length))),
  );
}

const ENGLISH = new Set(['the', 'and', 'to', 'of', 'with', 'you', 'we', 'for', 'our', 'is', 'are', 'will', 'your', 'a', 'an', 'this', 'that', 'as', 'on', 'be', 'have', 'who']);
const OTHER = new Set([
  // German
  'und', 'der', 'die', 'das', 'mit', 'für', 'wir', 'sie', 'ist', 'ein', 'eine', 'zu', 'auf', 'von', 'bei', 'dich', 'du', 'deine', 'unser', 'unsere', 'oder', 'nicht', 'werden',
  // Dutch
  'het', 'van', 'een', 'voor', 'met', 'wij', 'jij', 'jouw', 'zijn', 'ons', 'onze', 'naar', 'ook', 'bij', 'werken',
  // French
  'et', 'le', 'les', 'des', 'pour', 'avec', 'nous', 'vous', 'une', 'est', 'dans', 'votre', 'nos', 'sur', 'au', 'aux',
  // Spanish, Portuguese, Italian, Swedish
  'y', 'el', 'los', 'las', 'para', 'con', 'nosotros', 'del', 'una', 'uma', 'não', 'il', 'della', 'och', 'att', 'för', 'vi', 'är', 'som',
]);

/** Whether most of the text is English; a text too short to tell counts as English. */
export function isEnglish(text) {
  const words = String(text ?? '').toLowerCase().match(/[\p{L}']+/gu) ?? [];
  const en = words.filter((word) => ENGLISH.has(word)).length;
  const xx = words.filter((word) => OTHER.has(word)).length;
  if (en + xx < 20) return true;
  return en / (en + xx) >= 0.5;
}

/** Why a job abroad is out whatever its fit, or null: another language, or only for people already there. */
export function blocked(text) {
  if (needsOtherLanguage(text)) return 'language';
  if (!isEnglish(text)) return 'not-english';
  if (containsAny(fold(text), REGION_LOCKED)) return 'region-locked';
  return null;
}

const OPEN_RESTRICTION = [
  'worldwide', 'anywhere', 'global', 'globally', 'emea', 'mena', 'middle east', 'africa', 'egypt',
  'utc+2', 'utc+3', 'gmt+2', 'gmt+3', 'eet',
];

/**
 * Whether a feed's "who may apply" field takes someone in Egypt: nothing
 * said, or worldwide, EMEA, Africa, Egypt, UTC+2/+3. A list of other
 * countries ("Europe, LATAM, the U.S., Canada") or "USA" does not.
 */
export function openToEgypt(restriction) {
  const text = String(restriction ?? '').trim();
  if (text === '') return true;
  return containsAny(fold(text), OPEN_RESTRICTION);
}

/** Remote open to Egypt first (no visa needed), then a sponsored visa; null when neither. */
export function laneOf(text, remote = false) {
  const folded = fold(text);
  const remoteWork = remote || containsAny(folded, ['remote', 'fully remote', 'work from home', 'wfh']);

  if (remoteWork && containsAny(folded, OPEN_TO_EGYPT) && !containsAny(folded, US_ONLY)) return REMOTE_LANE;
  if (containsAny(folded, VISA_WORDS)) return VISA_LANE;
  return null;
}

const REGIONS = [
  ['🇨🇦 Canada', ['canada', 'toronto', 'vancouver', 'montreal', 'montréal', 'calgary', 'ottawa', 'edmonton', 'waterloo', 'ontario', 'british columbia', 'quebec', 'alberta']],
  ['🇦🇺 Australia', ['australia', 'sydney', 'melbourne', 'brisbane', 'perth', 'adelaide', 'canberra', 'new south wales', 'queensland']],
  ['🇬🇧 UK', ['united kingdom', 'uk', 'england', 'london', 'manchester', 'birmingham', 'edinburgh', 'glasgow', 'bristol', 'leeds', 'cambridge', 'oxford', 'scotland']],
  ['🇪🇺 Europe', [
    'european union', 'europe', 'germany', 'berlin', 'munich', 'münchen', 'hamburg', 'frankfurt', 'cologne', 'netherlands', 'amsterdam',
    'rotterdam', 'utrecht', 'eindhoven', 'the hague', 'ireland', 'dublin', 'france', 'paris', 'spain', 'madrid', 'barcelona', 'portugal',
    'lisbon', 'porto', 'italy', 'milan', 'rome', 'belgium', 'brussels', 'austria', 'vienna', 'switzerland', 'zurich', 'zürich', 'geneva',
    'sweden', 'stockholm', 'denmark', 'copenhagen', 'norway', 'oslo', 'finland', 'helsinki', 'poland', 'warsaw', 'krakow', 'kraków',
    'czech', 'prague', 'estonia', 'tallinn', 'lithuania', 'vilnius', 'latvia', 'riga', 'luxembourg', 'malta', 'cyprus', 'greece',
    'athens', 'romania', 'bucharest', 'hungary', 'budapest',
  ]],
];

/** Where a job is, as the channel shows it: Canada, Australia, the UK or Europe; null for anywhere else. */
export function abroadRegionOf(...parts) {
  const text = fold(parts.filter(Boolean).join(' \n '));
  return REGIONS.find(([, places]) => containsAny(text, places))?.[0] ?? null;
}

const STACK_POINTS = { Laravel: 20, PHP: 15, NestJS: 12, 'Node.js': 12, Go: 10, TypeScript: 5 };
const LEVEL_RE = /\b(junior|mid|mid-level|intermediate|associate|graduate|entry)\b/i;
const SENIOR_RE = /\b(senior|sr\.?|lead|principal|staff)\b/i;
const SHARP_LANE = ['emea', 'europe', 'european', 'cet', 'cest', 'eet', 'utc+2', 'utc+3', 'gmt+2', 'gmt+3', 'egypt', 'mena', 'middle east', 'africa'];
const SPONSORSHIP = ['visa sponsorship', 'will sponsor', 'we sponsor', 'sponsorship available', 'blue card', 'skilled worker', 'lmia', '482', 'visa paid', 'visa provided'];
const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));

/**
 * The chance the person is taken, 0-100: how much of the CV's stack the job
 * names (Laravel and PHP most), whether its title is that stack, the rest of
 * the CV it asks for, a level in reach, and how plainly it says it takes
 * someone from Egypt.
 */
export function fitScore(title, body, market) {
  const text = `${title ?? ''}\n${body ?? ''}`;
  const folded = fold(text);

  let points = Math.min(stackOf(text).reduce((sum, name) => sum + (STACK_POINTS[name] ?? 0), 0), 40);

  if (stackOf(title).length > 0) points += 10;
  else if (containsAny(fold(title), ['backend', 'back-end', 'back end', 'full stack', 'full-stack', 'fullstack'])) points += 5;

  points += Math.min(boostScore(text) * 3, 15);

  const years = minYears(text);
  if (LEVEL_RE.test(title ?? '')) points += 15;
  else if (years != null && years <= 3) points += 15;
  else if (years === 4) points += 8;
  else if (years == null) points += 5;

  const lane = market ?? '';
  if (lane.startsWith('🌍') && containsAny(folded, SHARP_LANE)) points += 15;
  else if (lane.startsWith('✈️') && containsAny(folded, SPONSORSHIP)) points += 15;
  else if (lane.startsWith('🌍') || lane.startsWith('✈️')) points += 5;

  if (containsAny(folded, ['relocation package', 'relocation support', 'relocation assistance'])) points += 5;
  if (SENIOR_RE.test(body ?? '')) points -= 5;

  return clamp(points, 0, 100);
}

export const scoreItem = (item) => fitScore(item.title, item.excerpt, item.market);

/** The jobs worth sending, best first: at least MIN_SCORE, at most MAX_PER_RUN. */
export function best(items, { minScore = MIN_SCORE, maxPerRun = MAX_PER_RUN } = {}) {
  return items
    .map((item) => [item, scoreItem(item)])
    .filter(([, score]) => score >= minScore)
    .sort((a, b) => b[1] - a[1])
    .slice(0, maxPerRun)
    .map(([item]) => item);
}
