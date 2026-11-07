import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { decodeEntities, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import {
  arr, isOlderThan, judgeFeedJob, newCounts, nextData, parseJson, str, summarize, tally, toIso,
} from './abroad.js';

export const KEY = 'remote_feeds';

/**
 * Free job feeds, no key or account, ported from the Android app's
 * RemoteFeeds.kt: Himalayas, Jobicy, Remotive, We Work Remotely, RemoteOK,
 * Working Nomads and Remote Rocketship (remote jobs, each saying which
 * countries may apply), Arbeitnow (mostly on-site in Germany) and
 * Landing.jobs. A job is judged once: open to Egypt by the feed's own
 * restriction, then remote or visa, English, the CV's stack and level. One
 * feed failing never stops the others; REMOTE_FEEDS_OFF switches feeds off.
 */
export class RemoteFeedsSource extends BaseSource {
  constructor(client = null) {
    super(KEY, { label: 'Remote feeds', headers: { Accept: 'application/json, application/rss+xml' }, client });
  }

  async fetch() {
    const off = new Set(this.settings.off ?? []);
    const checked = this.checked();
    const counts = newCounts();
    const perFeed = [];

    try {
      for (const feed of FEEDS) {
        if (feed.id && off.has(feed.id)) {
          perFeed.push(`${feed.name} off`);
          continue;
        }

        let jobs;
        try {
          const body = await this.get(feed.url, {}, counts.kept);
          if (body == null) {
            perFeed.push(`${feed.name} ✗`);
            continue;
          }
          jobs = feed.parse(body);
        } catch (error) {
          if (error instanceof BlockedError) throw error;
          logger.warn(`Remote feeds: ${feed.name} did not parse`, { error: error.message });
          perFeed.push(`${feed.name} ✗`);
          continue;
        }

        perFeed.push(`${feed.name} ${jobs.length}`);
        counts.listed += jobs.length;

        for (const job of jobs) {
          const id = `${job.feed}:${job.id}`;
          if (checked.has(id)) {
            counts.seen += 1;
            continue;
          }
          if (isOlderThan(job.postedAt, this.settings.maxAgeDays)) {
            counts.old += 1;
            continue;
          }
          this.markChecked(id);
          checked.add(id);
          counts.judged += 1;
          tally(judgeFeedJob(job, KEY), counts);
        }
      }
    } finally {
      this.addListed(counts.listed);
      logger.info('Remote feeds', { feeds: perFeed.join(' · '), ...summarize(counts) });
    }

    return counts.kept;
  }
}

/* The trailing slash matters: without it the site answers a redirect. */
const ROCKETSHIP = 'https://www.remoterocketship.com/country/egypt/jobs/';

const COUNTRY_CODES = {
  PT: 'Portugal', ES: 'Spain', DE: 'Germany', NL: 'Netherlands', GB: 'United Kingdom', UK: 'United Kingdom',
  IE: 'Ireland', FR: 'France', BE: 'Belgium', IT: 'Italy', PL: 'Poland', SE: 'Sweden', DK: 'Denmark',
  AT: 'Austria', CH: 'Switzerland', LU: 'Luxembourg', CZ: 'Czech Republic', RO: 'Romania', GR: 'Greece',
  CA: 'Canada', AU: 'Australia',
};

const epoch = (seconds) => (Number(seconds) > 0 ? new Date(Number(seconds) * 1000).toISOString() : null);

export function himalayas(body) {
  return arr(parseJson(body)?.jobs)
    .map((job) => {
      const places = arr(job.locationRestrictions).map(str).filter(Boolean);
      const zones = arr(job.timezoneRestrictions).map(Number).filter(Number.isFinite);
      // Countries, else time zones: a list without UTC+1 to +3 does not take Cairo.
      let restriction = null;
      if (places.length) restriction = places.join(', ');
      else if (zones.length && !zones.some((z) => z >= 1 && z <= 3)) {
        restriction = `time zones ${zones.map((z) => Math.trunc(z)).join(', ')} only`;
      } else if (zones.length) restriction = 'UTC+2';

      const guid = str(job.guid);
      const title = str(job.title);
      if (!guid || !title) return null;

      return {
        feed: 'himalayas', id: guid, title, company: str(job.companyName), url: guid, location: null,
        restriction, description: stripHtml(job.description), postedAt: epoch(job.pubDate),
      };
    })
    .filter(Boolean);
}

export function jobicy(body) {
  return arr(parseJson(body)?.jobs)
    .map((job) => {
      const id = str(job.id);
      const title = str(decodeEntities(job.jobTitle));
      const url = str(job.url);
      if (!id || !title || !url) return null;
      return {
        feed: 'jobicy', id, title, company: str(decodeEntities(job.companyName)), url, location: null,
        restriction: str(job.jobGeo), description: stripHtml(job.jobDescription), postedAt: toIso(job.pubDate),
      };
    })
    .filter(Boolean);
}

export function remotive(body) {
  return arr(parseJson(body)?.jobs)
    .map((job) => {
      const id = str(job.id);
      const title = str(job.title);
      const url = str(job.url);
      if (!id || !title || !url) return null;
      // "2026-10-29T18:22:41": UTC without the zone.
      const date = str(job.publication_date);
      return {
        feed: 'remotive', id, title, company: str(job.company_name), url, location: null,
        restriction: str(job.candidate_required_location), description: stripHtml(job.description),
        postedAt: toIso(date && date.length === 19 ? `${date}Z` : date),
      };
    })
    .filter(Boolean);
}

const tag = (xml, name) =>
  xml.match(new RegExp(`<${name}>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:]]>)?</${name}>`))?.[1] ?? null;

export function weWorkRemotely(body) {
  return [...String(body).matchAll(/<item>([\s\S]*?)<\/item>/g)]
    .map(([, item]) => {
      const heading = str(decodeEntities(tag(item, 'title')));
      const link = str(tag(item, 'link'));
      if (!heading || !link) return null;
      // "Dropbox: Backend Product Software Engineer".
      const colon = heading.indexOf(':');
      const company = colon > 0 ? heading.slice(0, colon).trim() : null;
      return {
        feed: 'wwr',
        id: link.slice(link.lastIndexOf('/') + 1),
        title: company ? heading.slice(colon + 1).trim() : heading,
        company,
        url: link,
        location: null,
        restriction: str(tag(item, 'region')),
        description: stripHtml(decodeEntities(tag(item, 'description'))),
        postedAt: toIso(tag(item, 'pubDate')),
      };
    })
    .filter(Boolean);
}

export function remoteOk(body) {
  return arr(parseJson(body))
    .map((job) => {
      // The first entry is the terms of use, not a job.
      if (!job || !('position' in job)) return null;
      const id = str(job.id);
      const title = str(decodeEntities(job.position));
      const url = str(job.url);
      if (!id || !title || !url) return null;
      return {
        feed: 'remoteok', id, title, company: str(job.company), url, location: null,
        restriction: str(job.location), description: stripHtml(job.description), postedAt: toIso(job.date),
      };
    })
    .filter(Boolean);
}

export function workingNomads(body) {
  return arr(parseJson(body))
    .map((job) => {
      const url = str(job?.url);
      const title = str(job?.title);
      if (!url || !title) return null;
      return {
        feed: 'workingnomads', id: url.match(/\/(\d+)\/?$/)?.[1] ?? url, title, company: str(job.company_name),
        url, location: null, restriction: str(job.location), description: stripHtml(job.description),
        postedAt: toIso(job.pub_date),
      };
    })
    .filter(Boolean);
}

export function arbeitnow(body) {
  return arr(parseJson(body)?.data)
    .map((job) => {
      const id = str(job.slug);
      const title = str(job.title);
      const url = str(job.url);
      if (!id || !title || !url) return null;
      const place = str(job.location);
      return {
        feed: 'arbeitnow', id, title, company: str(job.company_name), url,
        location: place ? `${place}, Germany` : null, restriction: null,
        description: stripHtml(job.description), postedAt: epoch(job.created_at), remote: Boolean(job.remote),
      };
    })
    .filter(Boolean);
}

/**
 * Landing.jobs, a European tech board: each job says whether the relocation
 * is paid and whether it is remote. A paid relocation is written into the
 * text, so the visa lane sees it.
 */
export function landingJobs(body) {
  return arr(parseJson(body))
    .map((job) => {
      const url = str(job?.url);
      const id = str(job?.id);
      const title = str(job?.title);
      if (!url || !id || !title) return null;

      const places = arr(job.locations)
        .map((place) => {
          const code = str(place?.country_code)?.toUpperCase();
          const country = code ? (COUNTRY_CODES[code] ?? code) : null;
          return [str(place?.city), country].filter(Boolean).join(', ') || null;
        })
        .filter(Boolean);

      const text = ['role_description', 'main_requirements', 'nice_to_have', 'perks']
        .map((field) => stripHtml(job[field]))
        .filter(Boolean);
      const tags = arr(job.tags).map(str).filter(Boolean);
      if (tags.length) text.push(`Stack: ${tags.join(', ')}`);
      if (job.relocation_paid) text.push('Relocation package provided: the relocation is paid.');

      // "https://landing.jobs/at/inscale/senior-java-…": the company is the path's second part.
      const slug = url.match(/\/at\/([^/]+)\//)?.[1];
      const company = slug ? slug.replace(/-/g, ' ').replace(/^./, (c) => c.toUpperCase()) : null;

      return {
        feed: 'landingjobs', id, title, company, url, location: places.join(' / ') || null, restriction: null,
        description: text.join('\n'), postedAt: toIso(job.published_at), remote: Boolean(job.remote),
      };
    })
    .filter(Boolean);
}

/**
 * Remote Rocketship's Egypt pages: remote jobs it says Egypt may take, read
 * from the page's own Next.js data. A page also shows remote jobs for nearby
 * countries (the UAE); their country list leaves Egypt out, so they fail the
 * region check.
 */
export function remoteRocketship(body) {
  return arr(nextData(body)?.initialJobOpenings)
    .map((job) => {
      const id = str(job?.id);
      const title = str(job?.roleTitle);
      const url = str(job?.url);
      if (!id || !title || !url) return null;

      const countries = arr(job.locationCountries).map(str).filter(Boolean);
      const stack = arr(job.techStack).map(str).filter(Boolean);
      const summary = str(job.twoLineJobDescriptionSummary) ?? str(job.jobDescriptionSummary);
      const type = str(job.employmentType);

      return {
        feed: 'rocketship', id, title, company: str(job.company?.name), url, location: null,
        restriction: countries.length ? countries.join(', ') : str(job.location),
        description: [summary, stack.length ? `Stack: ${stack.join(', ')}` : null, type ? `Employment: ${type}` : null]
          .filter(Boolean)
          .join('\n'),
        postedAt: toIso(job.created_at),
        remote: String(job.locationType ?? '').toLowerCase() === 'remote',
      };
    })
    .filter(Boolean);
}

/** id is set on the feeds that REMOTE_FEEDS_OFF can switch off. */
export const FEEDS = [
  { name: 'Himalayas', url: 'https://himalayas.app/jobs/api?limit=100', parse: himalayas, id: 'himalayas' },
  { name: 'Jobicy', url: 'https://jobicy.com/api/v2/remote-jobs?count=50&industry=dev', parse: jobicy, id: 'jobicy' },
  { name: 'Remotive', url: 'https://remotive.com/api/remote-jobs?category=software-dev&limit=100', parse: remotive, id: 'remotive' },
  { name: 'We Work Remotely', url: 'https://weworkremotely.com/categories/remote-back-end-programming-jobs.rss', parse: weWorkRemotely, id: 'wwr' },
  { name: 'RemoteOK', url: 'https://remoteok.com/api', parse: remoteOk, id: 'remoteok' },
  { name: 'Working Nomads', url: 'https://www.workingnomads.com/api/exposed_jobs/', parse: workingNomads },
  { name: 'Arbeitnow', url: 'https://www.arbeitnow.com/api/job-board-api', parse: arbeitnow },
  { name: 'Landing.jobs', url: 'https://landing.jobs/api/v1/jobs?limit=50', parse: landingJobs },
  { name: 'Landing.jobs', url: 'https://landing.jobs/api/v1/jobs?limit=50&offset=50', parse: landingJobs },
  // Remote jobs open to Egypt, one title per page; only a page's first 20 are public, so three titles.
  { name: 'Remote Rocketship', url: `${ROCKETSHIP}software-engineer/`, parse: remoteRocketship, id: 'rocketship' },
  { name: 'Remote Rocketship', url: `${ROCKETSHIP}backend-engineer/`, parse: remoteRocketship, id: 'rocketship' },
  { name: 'Remote Rocketship', url: `${ROCKETSHIP}fullstack-engineer/`, parse: remoteRocketship, id: 'rocketship' },
];

export default RemoteFeedsSource;
