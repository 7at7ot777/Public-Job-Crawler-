/**
 * Naukrigulf's search response.
 *
 * The only parser here that reads JSON rather than markup, because the site's
 * pages are an empty shell — 43 KB of script that fetches this same endpoint
 * and draws the results client-side. There is nothing in the HTML to parse.
 *
 * That makes it the cheapest source in the project: one response carries the
 * employer, the description and a posted date, so nothing has to be opened
 * afterwards the way a Tanqeeb card or a Wuzzuf page does.
 */

/**
 * @param {string} body      raw response body
 * @param {string} keyword   the search that found these, kept on each item
 * @returns {{items: object[], total: number|null}}
 */
export function parseSearchResponse(body, keyword) {
  const payload = decode(body);

  if (payload === null) return { items: [], total: null };

  const entries = Array.isArray(payload.Jobs) ? payload.Jobs : [];
  const items = [];

  for (const entry of entries) {
    const item = parseJob(entry?.Job, keyword);
    if (item) items.push(item);
  }

  return { items, total: toNumber(payload.TotalJobsCount) };
}

/**
 * A 4xx arrives as an empty string from the client, and a challenge or an
 * error page arrives as HTML. Neither is worth an exception: both mean this
 * page yielded nothing, which the caller already knows how to handle.
 */
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
  if (!job) return null;

  const externalId = clean(job.JobId);
  const title = clean(job.Designation);
  const url = clean(job.JdURL);

  // All three are what the item is addressed and dedupped by; an entry missing
  // any of them is a placeholder row, not a posting.
  if (!externalId || !title || !url) return null;

  return {
    externalId,
    title,
    url,
    company: clean(job.Company?.Name),
    // "Dubai - United Arab Emirates (UAE)", "Egypt - Egypt". Left as the site
    // writes it: the country is spelled out, which is what the location filter
    // reads.
    location: clean(job.Location),
    // A summary paragraph, not the full description — but enough for the tech
    // filter to judge on something besides a job title.
    excerpt: clean(job.jobInfo),
    postedAt: parseEpoch(job.LatestPostedDate),
    experienceMin: toNumber(job.Experience?.Min),
    experienceMax: toNumber(job.Experience?.Max),
    keyword,
  };
}

/** Seconds since the epoch, as the site sends it, to an ISO timestamp. */
function parseEpoch(value) {
  const seconds = toNumber(value);
  if (seconds === null || seconds <= 0) return null;

  const date = new Date(seconds * 1000);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** Every number in this response is sent as a string. */
function toNumber(value) {
  if (value === null || value === undefined || value === '') return null;

  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function clean(value) {
  const text = String(value ?? '')
    .replace(/\s+/g, ' ')
    .trim();

  return text === '' ? null : text;
}

export default parseSearchResponse;
