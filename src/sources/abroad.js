import config from '../config.js';
import { containsAny, fold } from '../text.js';
import { withoutLevelRange } from '../filters.js';
import * as AbroadFit from './abroadFit.js';
import { MAX_YEARS, minYears, stackOf } from './cvStack.js';

/*
 * Shared by every source of jobs abroad (remote feeds, Jobgether, sponsor
 * boards, Hacker News, the job APIs, Relocate.me, LinkedIn worldwide): the
 * Android app's AbroadJobs.judge and titleFits, plus the run tallies.
 */

/** A card worth opening: a backend title, not too senior, nowhere blocked. */
export function titleFits(item, filters = config.filters) {
  const title = fold(item.title);
  const placed = fold([item.title, item.location].filter(Boolean).join(' '));

  return (
    containsAny(title, filters.titleTerms) &&
    !containsAny(withoutLevelRange(title), filters.seniorityExclude) &&
    !containsAny(placed, filters.blockedLocations)
  );
}

/**
 * The job filled in from its page, and why it is dropped, if it is. The lane
 * becomes its market, so Egypt's location rule lets it through.
 *
 * @returns {{item: object, rejected: string|null}}
 */
export function judge(card, description, criteria = {}, remote = false) {
  const body = description ?? '';
  const text = `${card.title}\n${card.location ?? ''}\n${body}`;
  const stack = stackOf(`${card.title}\n${body}`);
  const years = minYears(body);
  const type = criteria['employment type'] ?? null;
  const lane = AbroadFit.laneOf(text, remote);
  const region = AbroadFit.abroadRegionOf(card.location, body);

  const item = {
    ...card,
    itemType: 'job',
    market: lane,
    keyword: stack.length ? stack.join(', ') : (card.keyword ?? null),
    seniority: criteria['seniority level'] ?? card.seniority ?? null,
    employmentType: type,
    workplace: lane === AbroadFit.REMOTE_LANE ? 'Remote' : 'On-site, visa sponsored',
    remote: lane === AbroadFit.REMOTE_LANE,
    excerpt: [lane, region, years != null ? `Needs ${years}+ years` : null, body || null]
      .filter(Boolean)
      .join('\n')
      .slice(0, 4000),
  };

  let rejected = null;
  if (!description || description.trim() === '') rejected = 'no-description';
  else if (stack.length === 0) rejected = 'stack';
  else if (lane == null) rejected = 'no-lane';
  // A sponsored visa is for a place: one in the regions searched.
  else if (lane === AbroadFit.VISA_LANE && region == null) rejected = 'region';
  else if (/intern/i.test(type ?? '')) rejected = 'internship';
  else if (/part/i.test(type ?? '')) rejected = 'part-time';
  else if (years != null && years > MAX_YEARS) rejected = 'years';
  else rejected = AbroadFit.blocked(text);

  return { item, rejected };
}

const BOARD_NAMES = {
  himalayas: 'Himalayas',
  jobicy: 'Jobicy',
  remotive: 'Remotive',
  wwr: 'We Work Remotely',
  remoteok: 'RemoteOK',
  workingnomads: 'Working Nomads',
  arbeitnow: 'Arbeitnow',
  landingjobs: 'Landing.jobs',
  rocketship: 'Remote Rocketship',
  jobgether: 'Jobgether',
  sponsor: 'Company board, known visa sponsor',
  hn: 'Hacker News, Who is hiring',
};

export const boardName = (feed) => BOARD_NAMES[feed] ?? feed;

/**
 * A feed's job as an item, judged. Remote jobs have to be open to Egypt by
 * the feed's own word first; what the feed says goes into the place, so the
 * lane check reads "Worldwide" or "EMEA" too.
 *
 * @param {{feed: string, id: string, title: string, company?: string, url: string, location?: string,
 *          restriction?: string, description: string, postedAt?: string, remote?: boolean}} job
 */
export function judgeFeedJob(job, source) {
  const remote = job.remote ?? true;
  const place =
    [job.location, job.restriction ? `Open to: ${job.restriction}` : null].filter(Boolean).join(' · ') ||
    (remote ? 'Remote, worldwide' : null);

  const card = {
    source,
    externalId: `${job.feed}:${job.id}`,
    itemType: 'job',
    title: job.title,
    url: job.url,
    company: job.company ?? null,
    location: place,
    postedAt: job.postedAt ?? null,
    board: boardName(job.feed),
  };

  if (!titleFits(card)) return { item: card, rejected: 'title' };
  if (remote && !AbroadFit.openToEgypt(job.restriction)) return { item: card, rejected: 'region-locked' };

  return judge(card, job.description, {}, remote);
}

/** A run's tallies: why a job was not judged is as telling as why it was dropped. */
export function newCounts() {
  return { listed: 0, seen: 0, old: 0, judged: 0, kept: [], rejected: {} };
}

export function tally(judged, counts) {
  if (judged.rejected == null) counts.kept.push(judged.item);
  else counts.rejected[judged.rejected] = (counts.rejected[judged.rejected] ?? 0) + 1;
}

/** "Remote feeds: 527 listed, 507 seen before, 0 too old, 20 judged, 0 kept". */
export function summarize(counts) {
  return {
    listed: counts.listed,
    seenBefore: counts.seen,
    tooOld: counts.old,
    judged: counts.judged,
    kept: counts.kept.length,
    dropped: counts.rejected,
  };
}

/** A posting without a date is kept: an unknown age is not an old one. */
export function isOlderThan(postedAt, maxAgeDays, now = Date.now()) {
  if (!postedAt || !(maxAgeDays > 0)) return false;
  const at = Date.parse(postedAt);
  return Number.isFinite(at) && now - at > maxAgeDays * 86_400_000;
}

/** ISO instants, plain ISO dates and RFC 1123 dates as an ISO instant; null for anything else. */
export function toIso(value) {
  const text = String(value ?? '').trim();
  if (text === '' || text === 'null') return null;
  const at = Date.parse(text);
  return Number.isFinite(at) ? new Date(at).toISOString() : null;
}

/** JSON or null, never a throw: a feed answering HTML is a feed with no jobs. */
export function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

/** A JSON value as a trimmed string; null for null, empty and "null". */
export function str(value) {
  if (value == null) return null;
  const text = String(value).replace(/\s+/g, ' ').trim();
  return text === '' || text === 'null' ? null : text;
}

export const arr = (value) => (Array.isArray(value) ? value : []);

const NEXT_DATA = /<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/;

/** A Next.js page's own data (props.pageProps), or null. */
export function nextData(page) {
  const json = String(page ?? '').match(NEXT_DATA)?.[1];
  return json ? (parseJson(json)?.props?.pageProps ?? null) : null;
}
