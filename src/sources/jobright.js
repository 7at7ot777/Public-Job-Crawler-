import logger from '../logger.js';
import { containsAny, fold } from '../text.js';
import BaseSource from './baseSource.js';
import { blocked, US_ONLY, VISA_TERMS, WORLDWIDE } from './abroadFit.js';
import { arr, isOlderThan, nextData, str, toIso } from './abroad.js';
import { boostScore, MAX_YEARS, stackOf } from './cvStack.js';

export const KEY = 'jobright';
const BASE = 'https://jobright.ai';

/**
 * jobright.ai, read through its public category pages (robots.txt allows
 * everything under /jobs/ and /remote-jobs/), ported from the Android app's
 * Jobright.kt.
 *
 * jobright lists US jobs only, so a job is kept for one of two reasons, its
 * lane: remote work open outside the US (the text names the world, EMEA,
 * Europe's or Egypt's time zones), or a visa (tagged "H1B Sponsor Likely" /
 * "H1B Sponsored", or the text offers sponsorship or relocation). On top of
 * the lane, a job has to name the CV's stack and fit 1-3 years.
 *
 * The pages ship their jobs in __NEXT_DATA__, 16-20 newest first, so the
 * source reads many short pages: the nationwide pages every run, the city
 * pages in turn, starting where the last run stopped.
 */
export class JobrightSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Jobright', client });
  }

  async fetch() {
    const kept = new Map();
    const rejected = {};
    let parsed = 0;

    const read = async (url) => {
      const page = await this.get(url, {}, ranked(kept.values()));
      if (page == null) return true;

      const jobs = parseJobright(page);
      this.addListed(jobs.length);

      for (const job of jobs) {
        parsed += 1;
        const reason = job.rejected ?? (isOlderThan(job.item.postedAt, this.settings.maxAgeDays) ? 'too-old' : null);
        if (reason) rejected[reason] = (rejected[reason] ?? 0) + 1;
        else if (!kept.has(job.item.externalId)) kept.set(job.item.externalId, job);
      }
      return true;
    };

    try {
      for (const page of PAGES) await read(page);
      await this.walk(CITY_PAGES, `${KEY}.cityPage`, read);
    } finally {
      logger.info('jobright', { listed: parsed, kept: kept.size, dropped: rejected });
    }

    return ranked(kept.values());
  }
}

/*
 * Category pages that exist (from sitemap-taxonomy): a guessed slug such as
 * "php-developer" silently falls back to an unrelated category, so only these.
 */
export const CATEGORIES = ['backend-engineer', 'full-stack-engineer', 'golang-engineer'];

/* Every place jobright has a page for: its 11 cities, each within 50 miles plus remote. */
export const CITIES = [
  'new-york-ny', 'san-francisco-ca', 'los-angeles-ca', 'seattle-wa', 'austin-tx', 'boston-ma',
  'chicago-il', 'dallas-tx', 'houston-tx', 'atlanta-ga', 'phoenix-az',
];

export const PAGES = [...CATEGORIES.map((c) => `${BASE}/jobs/${c}`), `${BASE}/remote-jobs/software-engineering`];
export const CITY_PAGES = CITIES.flatMap((city) => CATEGORIES.map((c) => `${BASE}/jobs/${c}-in-${city}`));

export const LANE = {
  REMOTE: '🌍 Remote, open outside the US',
  VISA: '✈️ Visa sponsorship (H1B)',
};

const SPONSOR_TAGS = new Set(['h1b sponsor likely', 'h1b sponsored']);
const NO_SPONSOR_TAG = 'no h1b';
const BLOCKING_TAGS = new Set(['u.s. citizen only', 'us citizen only', 'security clearance required']);
const ACCEPTED_LEVELS = /entry|junior|mid|new grad|associate|intermediate/i;
const SENIOR_LEVELS = /senior|lead|staff|principal|director|executive|manager/i;
const INTERN = /\bintern(ship)?s?\b/i;

/** Remote open outside the US first: it needs no visa at all. */
export function laneOf(tags, text, remote) {
  if (remote && containsAny(text, WORLDWIDE) && !containsAny(text, US_ONLY)) return LANE.REMOTE;
  if (tags.includes(NO_SPONSOR_TAG)) return null;
  if (tags.some((tag) => SPONSOR_TAGS.has(tag)) || containsAny(text, VISA_TERMS)) return LANE.VISA;
  return null;
}

/** jobright prints UTC as "2026-11-01 14:01:05". */
export function jobrightPosted(value) {
  const text = String(value ?? '').trim();
  return text.length >= 19 ? toIso(`${text.slice(0, 19).replace(' ', 'T')}Z`) : null;
}

/** Every job on a page, kept or not, in page order: {item, rejected, lane, score}. */
export function parseJobright(page) {
  const props = nextData(page);
  // A category page calls it jobList, the remote page defaultData.
  const list = arr(props?.jobList ?? props?.defaultData);

  return list
    .map((entry) => {
      const job = entry?.jobResult;
      const id = str(job?.jobId);
      const title = str(job?.jobTitle);
      if (!id || !title) return null;

      const tags = arr(job.recommendationTags).map(str).filter(Boolean).map((t) => t.toLowerCase());
      const summary = str(job.jobSummary);
      const duties = arr(job.coreResponsibilities).map(str).filter(Boolean);
      const needs = arr(job.requirements).map(str).filter(Boolean);
      const body = [summary, ...duties, ...needs].filter(Boolean).join('\n');
      const text = fold(`${title}\n${body}`);

      const remote = job.isRemote === true;
      const seniority = str(job.jobSeniority);
      const type = str(job.employmentType);
      const years = Number.isFinite(job.minYearsOfExperience) ? job.minYearsOfExperience : null;

      const lane = laneOf(tags, text, remote);
      const stack = stackOf(`${title}\n${body}`);
      const score = boostScore(`${title}\n${body}`);

      let rejected = null;
      if (tags.some((tag) => BLOCKING_TAGS.has(tag))) rejected = 'citizen-or-clearance';
      else if (!lane) rejected = 'no-lane';
      else if (stack.length === 0) rejected = 'stack';
      else if (INTERN.test(title) || /intern/i.test(type ?? '')) rejected = 'internship';
      else if (/part/i.test(type ?? '')) rejected = 'part-time';
      else if (years != null && years > MAX_YEARS) rejected = 'years';
      else if (seniority && !ACCEPTED_LEVELS.test(seniority) && SENIOR_LEVELS.test(seniority)) rejected = 'level';
      // Another language, or only for people already there.
      else rejected = blocked(`${title}\n${body}`);

      const locations = arr(job.jobLocations).map(str).filter(Boolean);
      const salary = str(job.salaryDesc);

      const excerpt = [
        lane,
        salary ? `💰 ${salary}` : null,
        years != null ? `Needs ${years}+ years` : null,
        summary,
        needs.length ? `Requirements:\n${needs.map((n) => `• ${n}`).join('\n')}` : null,
      ]
        .filter(Boolean)
        .join('\n')
        .slice(0, 4000);

      return {
        rejected,
        lane,
        score,
        item: {
          source: KEY,
          itemType: 'job',
          externalId: id,
          title,
          url: `${BASE}/jobs/info/${id}`,
          company: str(entry.companyResult?.companyName),
          location: locations.join(' / ') || str(job.jobLocation),
          market: lane,
          postedAt: jobrightPosted(job.publishTime),
          keyword: stack.join(', ') || null,
          excerpt: excerpt || null,
          seniority,
          employmentType: type,
          workplace: str(job.workModel),
          remote,
        },
      };
    })
    .filter(Boolean);
}

/** Most of the CV first, so a capped delivery sends the best fits. */
export const ranked = (jobs) => [...jobs].sort((a, b) => b.score - a.score).map((job) => job.item);

export default JobrightSource;
