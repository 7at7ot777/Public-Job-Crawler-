import { createHash } from 'node:crypto';
import logger from '../logger.js';
import { BlockedError } from '../errors.js';
import { detag, stripHtml } from '../text.js';
import BaseSource from './baseSource.js';
import LinkedInGuestClient from './linkedinClient.js';
import { arr, isOlderThan, judgeFeedJob, newCounts, parseJson, str, summarize, tally, toIso } from './abroad.js';

export const KEY = 'job_apis';

/**
 * Three job search APIs with a free key, ported from the Android app's
 * JobApis.kt, for what the remote feeds cannot give: on-site jobs with a visa
 * in the UK, Germany, the Netherlands, Ireland, Australia and Canada. Each
 * runs only once its key is in .env (ADZUNA_APP_ID + ADZUNA_APP_KEY,
 * JOOBLE_API_KEY, CAREERJET_API_KEY). They return a title and a snippet, not
 * the whole ad, so a job is judged on those.
 */
export class JobApisSource extends BaseSource {
  constructor(client = null, careerjetClient = null) {
    super(KEY, { label: 'Job APIs', headers: { Accept: 'application/json' }, client });

    const careerjet = this.settings.careerjetKey ?? '';
    // Careerjet's v4 API takes the key as basic auth (user = key, empty password).
    this.careerjetClient =
      careerjetClient ??
      new LinkedInGuestClient({
        label: 'Careerjet',
        delayMs: this.settings.delayMs ?? 1500,
        maxRetries: 1,
        headers: {
          Accept: 'application/json',
          Authorization: `Basic ${Buffer.from(`${careerjet}:`).toString('base64')}`,
        },
      });
  }

  get keys() {
    const s = this.settings;
    return {
      adzuna: Boolean(s.adzunaId && s.adzunaKey),
      jooble: Boolean(s.joobleKey),
      careerjet: Boolean(s.careerjetKey),
    };
  }

  /** On, and at least one API has its key: without one there is nothing to ask. */
  isEnabled() {
    const keys = this.keys;
    return Boolean(this.settings.enabled) && (keys.adzuna || keys.jooble || keys.careerjet);
  }

  async fetch() {
    const checked = this.checked();
    const counts = newCounts();
    const keys = this.keys;
    const s = this.settings;
    // An API that refuses (a bad key, an IP it does not allow) is left alone for the rest of the run.
    const refused = new Set();

    const take = (jobs) => {
      counts.listed += jobs.length;
      for (const job of jobs) {
        const id = `${job.feed}:${job.id}`;
        if (checked.has(id)) {
          counts.seen += 1;
          continue;
        }
        if (isOlderThan(job.postedAt, s.maxAgeDays)) {
          counts.old += 1;
          continue;
        }
        this.markChecked(id);
        checked.add(id);
        counts.judged += 1;
        tally(judgeFeedJob(job, KEY), counts);
      }
    };

    const guarded = async (api, name, call) => {
      if (refused.has(api)) return;
      try {
        take(await call());
      } catch (error) {
        if (error instanceof BlockedError && error.transport) {
          logger.warn(`Job APIs: ${name} unreachable`, { error: error.message });
        } else if (error instanceof BlockedError) {
          refused.add(api);
          logger.warn(`Job APIs: ${api} refused the request, skipped this run`, {
            error: error.message,
            hint: api === 'Careerjet' ? 'Careerjet only answers IPs allowed in the publisher account' : undefined,
          });
        } else {
          logger.warn(`Job APIs: ${name} did not parse`, { error: error.message });
        }
      }
    };

    try {
      for (const query of QUERIES) {
        if (keys.adzuna) {
          for (const [code, country] of Object.entries(ADZUNA_COUNTRIES)) {
            await guarded('Adzuna', `Adzuna ${code}`, async () =>
              adzuna(await this.client.getHtml(adzunaUrl(code), adzunaParams(s, query, s.maxAgeDays)), country),
            );
          }
        }

        if (keys.jooble) {
          for (const country of COUNTRIES) {
            await guarded('Jooble', `Jooble ${country}`, async () =>
              jooble(await this.client.postJson(`https://jooble.org/api/${s.joobleKey}`, { keywords: query, location: country })),
            );
          }
        }

        if (keys.careerjet) {
          for (const locale of Object.keys(CAREERJET_LOCALES)) {
            await guarded('Careerjet', `Careerjet ${locale}`, async () =>
              careerjet(await this.careerjetClient.getHtml(CAREERJET_URL, careerjetParams(query, locale))),
            );
          }
        }
      }
    } finally {
      this.addListed(counts.listed);
      logger.info('Job APIs', summarize(counts));
    }

    return counts.kept;
  }
}

/* The CV's stack, each with the two ways a job abroad can be taken from Cairo. */
export const QUERIES = ['Laravel', 'PHP', 'Node.js', 'Backend Developer'].flatMap((q) => [`${q} visa sponsorship`, `${q} remote`]);

export const ADZUNA_COUNTRIES = { gb: 'United Kingdom', de: 'Germany', nl: 'Netherlands', au: 'Australia', ca: 'Canada' };
export const COUNTRIES = ['United Kingdom', 'Germany', 'Netherlands', 'Ireland', 'Australia', 'Canada'];
export const CAREERJET_LOCALES = { en_GB: 'United Kingdom', en_IE: 'Ireland', en_AU: 'Australia', en_CA: 'Canada' };
const CAREERJET_URL = 'https://search.api.careerjet.net/v4/query';

export const adzunaUrl = (country) => `https://api.adzuna.com/v1/api/jobs/${country}/search/1`;

export const adzunaParams = (s, query, maxAgeDays = 7) => ({
  app_id: s.adzunaId,
  app_key: s.adzunaKey,
  what: query,
  max_days_old: maxAgeDays,
  results_per_page: 20,
  'content-type': 'application/json',
});

/** Careerjet v4: the key goes in the Authorization header, not here. */
export const careerjetParams = (query, locale) => ({
  keywords: query,
  locale_code: locale,
  page_size: 20,
  sort: 'date',
  fragment_size: 500,
  // Careerjet asks who is searching; a crawler has no visitor, so it says so.
  user_ip: '127.0.0.1',
  user_agent: 'JobCrawler',
});

/** A remote job's place says so, so the lane check sees it. */
const remoteIn = (...text) => text.some((part) => /remote/i.test(part ?? ''));

export function adzuna(body, country) {
  return arr(parseJson(body)?.results)
    .map((job) => {
      const title = str(detag(job?.title));
      const id = str(job?.id);
      const url = str(job?.redirect_url);
      if (!title || !id || !url) return null;
      const description = stripHtml(job.description);
      const place = str(job.location?.display_name);
      return {
        feed: 'adzuna', id, title, company: str(job.company?.display_name), url,
        location: [place, place?.toLowerCase().includes(country.toLowerCase()) ? null : country].filter(Boolean).join(', '),
        restriction: null, description, postedAt: toIso(job.created), remote: remoteIn(title, description),
      };
    })
    .filter(Boolean);
}

/** Jooble's "2026-09-22T00:00:00.0000000": seven fraction digits and no zone, read as UTC. */
export function joobleDate(value) {
  const text = String(value ?? '').trim();
  return text.length >= 19 ? toIso(`${text.slice(0, 19)}Z`) : null;
}

export function jooble(body) {
  return arr(parseJson(body)?.jobs)
    .map((job) => {
      const title = str(detag(job?.title));
      const id = str(job?.id);
      const url = str(job?.link);
      if (!title || !id || !url) return null;
      const description = stripHtml(job.snippet);
      return {
        feed: 'jooble', id, title, company: str(job.company), url, location: str(job.location),
        restriction: null, description, postedAt: joobleDate(job.updated),
        remote: remoteIn(title, description, job.location),
      };
    })
    .filter(Boolean);
}

/** Careerjet's "Wed,15 Nov 2025 19:13:43 GMT": the weekday dropped (it may not match the date). */
export function careerjetDate(value) {
  const text = String(value ?? '').trim().replace(/^[A-Za-z]{3},\s*/, '');
  return text ? toIso(text) : null;
}

export function careerjet(body) {
  return arr(parseJson(body)?.jobs)
    .map((job) => {
      const url = str(job?.url);
      const title = str(detag(job?.title));
      if (!url || !title) return null;
      const description = stripHtml(job.description);
      return {
        feed: 'careerjet',
        id: createHash('sha1').update(url).digest('hex').slice(0, 12),
        title, company: str(job.company), url, location: str(job.locations), restriction: null, description,
        postedAt: careerjetDate(job.date), remote: remoteIn(title, description, job.locations),
      };
    })
    .filter(Boolean);
}

export default JobApisSource;
