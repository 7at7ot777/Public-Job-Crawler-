import * as Gulf from './gulf.js';

/*
 * Where a job is: Egypt, the Gulf (Saudi Arabia and the UAE), anywhere else
 * in the world, or a freelance gig. Every Telegram message ends with its tag,
 * so the one channel can be searched by region, and each region can be posted
 * to its own forum topic (src/topics.js).
 */
export const REGION = {
  EGYPT: { name: 'EGYPT', label: 'Egypt', tag: '#Egypt', flag: '🇪🇬', order: 0 },
  GULF: { name: 'GULF', label: 'Saudi & UAE', tag: '#Gulf', flag: '🇸🇦', order: 1 },
  WORLDWIDE: { name: 'WORLDWIDE', label: 'Worldwide', tag: '#Worldwide', flag: '🌍', order: 2 },
  FREELANCE: { name: 'FREELANCE', label: 'Freelance', tag: '#Freelance', flag: '💼', order: 3 },
};

export const SHOWN = [REGION.EGYPT, REGION.GULF, REGION.WORLDWIDE, REGION.FREELANCE];

/* Sources whose every job is abroad: remote from anywhere, or a visa. */
export const WORLDWIDE_SOURCES = new Set([
  'jobright',
  'relocateme',
  'remote_feeds',
  'jobgether',
  'hn_hiring',
  'sponsor_boards',
  'job_apis',
  'linkedin_worldwide',
  'abroad_jobs',
]);

/* Sources whose every job is in Saudi Arabia or the UAE (or the wider Gulf). */
export const GULF_SOURCES = new Set(['gulf_posts', 'linkedin_jobs_gulf', 'naukrigulf']);

/* Arabic freelance boards: gigs, not jobs. */
export const FREELANCE_SOURCES = new Set(['mostaql', 'khamsat']);

/*
 * Filed under Worldwide, but not every job is abroad: AI data work is mostly
 * remote ("Anywhere"), yet it also searches Egypt, so each job is placed by
 * where it is.
 */
const MOSTLY_REMOTE = new Set(['ai_data']);

const EGYPT_RE = /\b(egypt|cairo|giza|alexandria)\b|مصر|القاهرة/i;
const isEgypt = (place) => place != null && EGYPT_RE.test(place);

/** The region a source is filed under. */
export function home(sourceKey) {
  if (MOSTLY_REMOTE.has(sourceKey) || WORLDWIDE_SOURCES.has(sourceKey)) return REGION.WORLDWIDE;
  if (GULF_SOURCES.has(sourceKey)) return REGION.GULF;
  if (FREELANCE_SOURCES.has(sourceKey)) return REGION.FREELANCE;
  return REGION.EGYPT;
}

/**
 * A job outside Egypt and the Gulf: every job of a worldwide source, and a
 * Glassdoor one in a lane abroad — not a Saudi/UAE Glassdoor listing.
 */
export function isAbroad(item) {
  return (
    WORLDWIDE_SOURCES.has(item.source) ||
    (item.source === 'glassdoor' && item.market != null && !Gulf.isGulf(item.market))
  );
}

/** Items come from a source (camelCase) or SQLite (snake_case); the region reads either. */
function fields(item) {
  return {
    source: item.source,
    market: item.market ?? null,
    location: item.location ?? null,
  };
}

/**
 * The region of one job. The Gulf wins over the source's lane: a Saudi
 * listing on a worldwide board is still a Gulf job.
 */
export function regionOf(rawItem) {
  const item = fields(rawItem);

  // A gig is a gig wherever its client is: a Mostaql project naming Riyadh stays freelance.
  if (FREELANCE_SOURCES.has(item.source)) return REGION.FREELANCE;
  if (GULF_SOURCES.has(item.source) || Gulf.isGulf(item.market)) return REGION.GULF;

  const abroad = isAbroad(item);

  // An Egypt source that names a Gulf place without setting the market (company boards).
  if (Gulf.countryOf(item.location) != null && !isEgypt(item.location)) return REGION.GULF;
  if (abroad) return REGION.WORLDWIDE;
  if (MOSTLY_REMOTE.has(item.source) && !isEgypt(item.location)) return REGION.WORLDWIDE;

  const market = item.market ?? '';
  if (market.startsWith('🌍') || market.startsWith('✈️')) return REGION.WORLDWIDE;

  return REGION.EGYPT;
}

/** A stored value back to a region; null or unknown reads as null. */
export function parseRegion(value) {
  return REGION[value] ?? null;
}
