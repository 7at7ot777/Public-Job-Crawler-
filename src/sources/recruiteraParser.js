/**
 * Recruitera's public careers API, turned into items.
 *
 * Every response is JSON from the same endpoints the careers pages call, so
 * there is no markup to read — only a shape to trust, and a fallback to null
 * wherever that shape is not what it was when this was written.
 */

const SITE = 'https://app.recruitera.ai';

const WORK_MODELS = {
  remote: 'Remote',
  hybrid: 'Hybrid',
  on_site: 'On-site',
};

/** ISO 3166 alpha-2 -> the name a person reads, for the countries in play. */
const COUNTRY_NAMES = {
  EG: 'Egypt',
  SA: 'Saudi Arabia',
  AE: 'United Arab Emirates',
  QA: 'Qatar',
  KW: 'Kuwait',
  BH: 'Bahrain',
  OM: 'Oman',
  JO: 'Jordan',
  PK: 'Pakistan',
};

/** A body that should be JSON; null when it is not, never a throw. */
export function parseJson(body) {
  if (!body) return null;

  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

/**
 * Company career sites listed in the sitemap.
 *
 * The sitemap holds company pages and job pages alike; a company page is the
 * one with a single path segment after /careers/.
 */
export function parseSitemapSites(xml) {
  const sites = new Set();

  for (const match of (xml ?? '').matchAll(/<loc>\s*([^<\s]+)\s*<\/loc>/g)) {
    const path = match[1].replace(/^https?:\/\/[^/]+/, '').replace(/\/+$/, '');
    const site = path.match(/^\/careers\/([^/?#]+)$/)?.[1];

    if (site) sites.add(safeDecode(site));
  }

  return [...sites];
}

/** Category ids whose name is one of `wanted`, compared case-insensitively. */
export function pickCategories(body, wanted) {
  const names = new Set(wanted.map((name) => name.trim().toLowerCase()));
  const categories = parseJson(body)?.data?.categories;

  if (!Array.isArray(categories)) return [];

  return categories
    .filter((category) => category?.id && names.has(String(category.name).trim().toLowerCase()))
    .map((category) => ({ id: category.id, name: category.name }));
}

/** One page of a site's opportunities: the jobs and the cursor to the next. */
export function parseOpportunities(body) {
  const json = parseJson(body);
  const jobs = Array.isArray(json?.data) ? json.data.filter((job) => job?.id && job?.slug) : [];

  return {
    jobs,
    nextCursor: json?.meta?.next_cursor ?? null,
  };
}

/** The company's display name off its site endpoint. */
export function parseCompanyName(body) {
  const name = parseJson(body)?.data?.company?.name;
  return typeof name === 'string' && name.trim() !== '' ? name.trim() : null;
}

/**
 * A job detail response, reduced to what an item needs.
 *
 * @returns {object|null} null when the response is not a job.
 */
export function parseJobDetail(body) {
  const job = parseJson(body)?.data;
  if (!job?.id || !job?.title) return null;

  const country = String(job.location_details?.country ?? '').toUpperCase() || null;
  const city = job.location_details?.city ?? null;

  return {
    id: job.id,
    title: String(job.title).trim(),
    slug: job.slug ?? null,
    available: job.available !== false,
    country,
    city,
    // The office's own label ("Paymob Head Office") is no use to someone who
    // does not know where that is; the city and country are.
    location: [city, COUNTRY_NAMES[country] ?? country].filter(Boolean).join(', ') || job.location || null,
    workModel: job.work_model ?? null,
    workplace: WORK_MODELS[job.work_model] ?? null,
    employmentType: job.employment_type ?? null,
    experience: job.experience ?? null,
    category: job.category ?? null,
    publishedAt: toIsoOrNull(job.published_at),
    excerpt: excerptOf(job),
  };
}

/**
 * Titles no real vacancy carries.
 *
 * Some sites are QA accounts that post generated jobs around the clock —
 * "Legacy Data Agent mun6kji05zs", "Global Operations Officer 1790713328542" —
 * filed under IT like any other. The tell is the last word: a run of letters
 * and digits no language has, or a millisecond timestamp. Drafts left as
 * "Untitled", "Test …" and "… (Copy)" go with them.
 */
export function isJunkTitle(title) {
  const text = String(title ?? '').trim();
  if (text === '' || /^(?:untitled|test)\b/i.test(text) || /\(copy\)\s*$/i.test(text)) return true;

  const last = text.split(/\s+/).at(-1);

  return /^\d{10,}$/.test(last) || (/^[a-z0-9]{8,}$/i.test(last) && /\d/.test(last) && /[a-z]/i.test(last));
}

/** The public page for a job, which is where an application is made. */
export function jobUrl(site, jobSlug) {
  return `${SITE}/careers/${encodeURIComponent(site)}/${encodeURIComponent(jobSlug)}`;
}

/**
 * What the filters read: the role's own words, without the company blurb.
 *
 * The description opens with "About <company>", which says nothing about the
 * job and is where an unrelated "senior leadership team" would trip the
 * seniority rules. Requirements and responsibilities come first so the part
 * that does describe the role is never the part cut off.
 */
function excerptOf(job) {
  return [job.requirements, job.responsibilities, job.description]
    .map(stripHtml)
    .filter(Boolean)
    .join('\n')
    .slice(0, 4000);
}

export function stripHtml(value) {
  if (!value) return '';

  return String(value)
    .replace(/<\s*(br|\/p|\/li|\/h\d)\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim();
}

function toIsoOrNull(value) {
  if (!value) return null;

  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function safeDecode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
