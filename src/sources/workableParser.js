import { stripHtml } from './recruiteraParser.js';

/**
 * jobs.workable.com's search response.
 *
 * The cross-company board every Workable customer's openings are listed on,
 * read through the JSON endpoint its own search page calls. One response
 * carries the employer, the location, the workplace, the posted date and the
 * full description, so nothing is opened afterwards.
 */

const WORKPLACES = {
  remote: 'Remote',
  hybrid: 'Hybrid',
  on_site: 'On-site',
};

/** Workable's marker for "remote", listed alongside the real places. */
const TELECOMMUTE = 'TELECOMMUTE';

/**
 * @param {string} body      raw response body
 * @param {string} keyword   the search that found these, kept on each item
 * @returns {{items: object[], total: number|null, nextPageToken: string|null}}
 */
export function parseSearchResponse(body, keyword) {
  const payload = decode(body);

  if (payload === null) return { items: [], total: null, nextPageToken: null };

  const entries = Array.isArray(payload.jobs) ? payload.jobs : [];
  const items = [];

  for (const entry of entries) {
    const item = parseJob(entry, keyword);
    if (item) items.push(item);
  }

  return {
    items,
    total: Number.isFinite(payload.totalSize) ? payload.totalSize : null,
    nextPageToken: clean(payload.nextPageToken),
  };
}

/** A 4xx arrives as an empty string and a challenge page as HTML: no jobs. */
function decode(body) {
  const text = (body ?? '').trim();
  if (text === '' || !text.startsWith('{')) return null;

  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function parseJob(job, keyword) {
  if (!job || job.state !== 'published') return null;

  const externalId = clean(job.id);
  const title = clean(job.title);
  const url = clean(job.url);

  if (!externalId || !title || !url) return null;

  const places = (Array.isArray(job.locations) ? job.locations : [])
    .map(clean)
    .filter((place) => place && place !== TELECOMMUTE);

  return {
    externalId,
    title,
    url,
    company: clean(job.company?.title),
    // "Cairo, Cairo Governorate, Egypt". Every place a job is open in, which
    // for a remote job is the list of countries it accepts.
    location: places.length > 0 ? places.join(' / ') : clean(job.location?.countryName),
    country: clean(job.location?.countryName),
    countries: places,
    remote: job.workplace === 'remote',
    workplace: WORKPLACES[job.workplace] ?? null,
    employmentType: clean(job.employmentType),
    postedAt: parseDate(job.created),
    excerpt: excerptOf(job),
    keyword,
  };
}

/** Requirements first: the part that says what the role is about. */
function excerptOf(job) {
  const text = [job.requirementsSection, job.description]
    .map(stripHtml)
    .filter(Boolean)
    .join('\n')
    .slice(0, 4000);

  return text === '' ? null : text;
}

function parseDate(value) {
  if (!value) return null;

  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function clean(value) {
  const text = String(value ?? '')
    .replace(/\s+/g, ' ')
    .trim();

  return text === '' ? null : text;
}

export default parseSearchResponse;
