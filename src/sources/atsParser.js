import { stripHtml } from './recruiteraParser.js';

/**
 * Company career boards hosted on the common applicant-tracking systems, each
 * read through the public feed its own careers page is built from.
 *
 * One adapter per platform: where a company's feed lives, and how one of its
 * jobs becomes an item. Every feed returns the whole board in one response,
 * so nothing is paged and nothing is opened per job. Most feeds are JSON;
 * Teamtailor and SuccessFactors publish XML and Zoho Recruit embeds its JSON
 * in the careers page, so those three bring their own `decode`.
 *
 * Workable is not here on purpose. jobs.workable.com lists every Workable
 * customer's openings in one search, which the workable source already reads.
 */

const WORKPLACES = {
  remote: 'Remote',
  hybrid: 'Hybrid',
  onsite: 'On-site',
  on_site: 'On-site',
};

export const PLATFORMS = {
  lever: {
    url: (slug) => `https://api.lever.co/v0/postings/${encodeURIComponent(slug)}?mode=json`,
    jobs: (payload) => (Array.isArray(payload) ? payload : []),
    parse: (job) => ({
      externalId: job.id,
      title: job.text,
      url: job.hostedUrl,
      company: null,
      location: job.categories?.location,
      workplace: workplaceOf(job.workplaceType),
      employmentType: job.categories?.commitment,
      postedAt: fromEpochMs(job.createdAt),
      excerpt: [job.descriptionPlain, job.additionalPlain].filter(Boolean).join('\n'),
    }),
  },

  greenhouse: {
    url: (slug) =>
      `https://boards-api.greenhouse.io/v1/boards/${encodeURIComponent(slug)}/jobs?content=true`,
    jobs: (payload) => (Array.isArray(payload?.jobs) ? payload.jobs : []),
    parse: (job) => ({
      externalId: job.id,
      title: job.title,
      url: job.absolute_url,
      company: job.company_name,
      location: job.location?.name,
      workplace: null,
      employmentType: null,
      postedAt: job.first_published ?? job.updated_at,
      // Greenhouse sends the HTML entity-escaped, so it is unescaped once
      // before the tags can be stripped.
      excerpt: stripHtml(stripHtml(job.content)),
    }),
  },

  ashby: {
    url: (slug) => `https://api.ashbyhq.com/posting-api/job-board/${encodeURIComponent(slug)}`,
    jobs: (payload) =>
      (Array.isArray(payload?.jobs) ? payload.jobs : []).filter((job) => job.isListed !== false),
    parse: (job) => {
      const address = job.address?.postalAddress;
      const place = [address?.addressLocality, address?.addressCountry].filter(Boolean).join(', ');

      return {
        externalId: job.id,
        title: job.title,
        url: job.jobUrl,
        company: null,
        // "Cairo Office" names a building; the address names the country.
        location: place || job.location,
        workplace: workplaceOf(job.workplaceType) ?? (job.isRemote ? 'Remote' : null),
        employmentType: job.employmentType,
        postedAt: job.publishedAt,
        excerpt: job.descriptionPlain,
      };
    },
  },

  bamboohr: {
    url: (slug) => `https://${encodeURIComponent(slug)}.bamboohr.com/careers/list`,
    jobs: (payload) => (Array.isArray(payload?.result) ? payload.result : []),
    parse: (job, slug) => ({
      externalId: job.id,
      title: job.jobOpeningName,
      url: `https://${slug}.bamboohr.com/careers/${encodeURIComponent(job.id)}`,
      company: null,
      location: [job.location?.city, job.location?.state, job.atsLocation?.country]
        .filter(Boolean)
        .join(', '),
      // 0 on-site, 1 remote, 2 hybrid.
      workplace: { 0: 'On-site', 1: 'Remote', 2: 'Hybrid' }[job.locationType] ?? null,
      employmentType: job.employmentStatusLabel,
      // The list carries no date and the description sits behind a request
      // per job. Undated is kept, and dedup sends each job once.
      postedAt: null,
      excerpt: null,
    }),
  },

  recruitee: {
    url: (slug) => `https://${encodeURIComponent(slug)}.recruitee.com/api/offers/`,
    jobs: (payload) =>
      (Array.isArray(payload?.offers) ? payload.offers : []).filter(
        (job) => !job.status || job.status === 'published',
      ),
    parse: (job) => ({
      externalId: job.id,
      title: job.title,
      url: job.careers_url,
      company: job.company_name,
      location: job.location || [job.city, job.country].filter(Boolean).join(', '),
      workplace: job.remote ? 'Remote' : job.hybrid ? 'Hybrid' : job.on_site ? 'On-site' : null,
      employmentType: job.employment_type_code,
      postedAt: job.published_at ?? job.created_at,
      excerpt: [job.requirements, job.description].map(stripHtml).filter(Boolean).join('\n'),
    }),
  },

  /*
   * The RSS feed every Teamtailor careers site serves at /jobs.rss. The slug
   * is the site's own host when it has one (careers.axisapp.com), or its
   * Teamtailor subdomain (axis -> axis.teamtailor.com).
   */
  teamtailor: {
    url: (slug) => `https://${teamtailorHost(slug)}/jobs.rss`,
    decode: (body) => (/<rss[\s>]/.test(body ?? '') ? xmlBlocks(body, 'item') : null),
    jobs: (items) => items,
    parse: (item) => {
      const place = [xmlText(item, 'tt:city'), xmlText(item, 'tt:country')]
        .filter(Boolean)
        .join(', ');

      return {
        externalId: xmlText(item, 'guid') ?? xmlText(item, 'link'),
        title: xmlText(item, 'title'),
        url: xmlText(item, 'link'),
        company: null,
        location: place || xmlText(item, 'tt:name'),
        workplace: workplaceOf(xmlText(item, 'remoteStatus')),
        employmentType: null,
        // RFC 822, "Sun, 04 Oct 2026 11:57:49 +0300", which parseDate's
        // space-to-T rewrite would break.
        postedAt: fromRfc822(xmlText(item, 'pubDate')),
        excerpt: stripHtml(xmlText(item, 'description')),
      };
    },
  },

  /*
   * The JSON list every Breezy careers site serves at /json. It carries no
   * description; a job open in several places lists each in `locations`.
   */
  breezy: {
    url: (slug) => `https://${encodeURIComponent(slug)}.breezy.hr/json`,
    jobs: (payload) => (Array.isArray(payload) ? payload : []),
    parse: (job) => {
      const places = job.locations?.length ? job.locations : [job.location].filter(Boolean);
      const place = places
        .map((at) => [at.city, at.country?.name].filter(Boolean).join(', ') || at.name)
        .filter(Boolean)
        .join(' / ');

      return {
        externalId: job.id,
        title: job.name,
        url: job.url,
        company: job.company?.name,
        location: place,
        workplace:
          workplaceOf(job.location?.remote_details?.value) ??
          (job.location?.is_remote ? 'Remote' : null),
        employmentType: job.type?.name,
        postedAt: job.published_date,
        excerpt: stripHtml(job.description),
      };
    },
  },

  /*
   * A Zoho Recruit careers site (qutip.zohorecruit.com/jobs/Careers). It has
   * no feed, but the page carries every published job as entity-escaped JSON
   * in a hidden <input id="jobs">. The slug is the subdomain, or the whole
   * host off zohorecruit.com (zohomea.zohorecruit.in).
   *
   * Date_Opened is a field each company may hide, and most do. Undated is
   * kept, and dedup sends each job once.
   */
  zoho: {
    url: (slug) => `https://${zohoHost(slug)}/jobs/Careers`,
    decode: (body) => {
      // Quote-aware: the value may hold a raw ">" from a description's HTML.
      const input = /<input\b(?:[^>"]|"[^"]*")*\bid="jobs"(?:[^>"]|"[^"]*")*>/.exec(
        body ?? '',
      )?.[0];
      const value = input && /\bvalue="([^"]*)"/.exec(input)?.[1];
      return value ? decode(unescapeEntities(value)) : null;
    },
    jobs: (payload) =>
      (Array.isArray(payload) ? payload : []).filter((job) => job.Publish !== false),
    parse: (job, slug) => ({
      externalId: job.id,
      title: job.Posting_Title ?? job.Job_Opening_Name,
      url: `https://${zohoHost(slug)}/jobs/Careers/${encodeURIComponent(job.id ?? '')}`,
      company: null,
      location: [job.City, job.State, job.Country].filter(Boolean).join(', '),
      workplace: job.Remote_Job ? 'Remote' : null,
      employmentType: job.Job_Type,
      postedAt: job.Date_Opened,
      excerpt: stripHtml(job.Job_Description),
    }),
  },

  /*
   * The XML job listing behind a SAP SuccessFactors career portal
   * (career5.successfactors.eu/careers?company=capgemitecP3). Slug:
   * host/companyId[/unitId/Place].
   *
   * The listing is the company's whole world — Capgemini's runs to ~1800 jobs
   * and 15 MB — and names no place. Each job does carry two unlabelled ids,
   * the first being the hiring unit, so a board can keep one unit's jobs and
   * say where that unit is: capgemitecP3/15638549/Egypt is Capgemini Egypt.
   */
  successfactors: {
    url: (slug) => {
      const { host, company } = successFactorsBoard(slug);
      return `https://${host}/career?company=${encodeURIComponent(company)}&career_ns=job_listing_summary&resultType=XML`;
    },
    decode: (body) => (/<Job-Listing[\s>]/.test(body ?? '') ? xmlBlocks(body, 'Job') : null),
    jobs: (jobs, slug) => {
      const { unit } = successFactorsBoard(slug);
      return unit ? jobs.filter((job) => unlabelled(job)[0] === unit) : jobs;
    },
    parse: (job, slug) => {
      const { host, company, place } = successFactorsBoard(slug);
      const id = xmlText(job, 'ReqId');

      return {
        externalId: id,
        // CDATA, yet still entity-escaped: "infotainment &amp; connectivity".
        title: stripHtml(xmlText(job, 'JobTitle')),
        url: `https://${host}/career?company=${encodeURIComponent(company)}&career_ns=job_listing&career_job_req_id=${encodeURIComponent(id ?? '')}`,
        company: null,
        location: place,
        workplace: null,
        employmentType: null,
        postedAt: usDate(xmlText(job, 'Posted-Date')),
        excerpt: stripHtml(xmlText(job, 'Job-Description')?.replace(/&#13;/g, '')),
      };
    },
  },

  /*
   * The three below came from the Android app, for the Saudi/UAE employers
   * (kept with BOARDS_GULF on, dropped by the Egypt location rule otherwise).
   */

  // SmartRecruiters' public postings API. The list carries no description.
  smartrecruiters: {
    url: (slug) => `https://api.smartrecruiters.com/v1/companies/${encodeURIComponent(slug)}/postings?limit=100`,
    jobs: (payload) => (Array.isArray(payload?.content) ? payload.content : []),
    parse: (job, slug) => {
      const location = job.location ?? {};
      // "Ruwais, , United Arab Emirates": a blank region left in.
      const full = String(location.fullLocation ?? '')
        .split(',')
        .map((part) => part.trim())
        .filter(Boolean)
        .join(', ');

      return {
        externalId: job.id,
        title: job.name,
        url: `https://jobs.smartrecruiters.com/${encodeURIComponent(slug)}/${encodeURIComponent(job.id ?? '')}`,
        company: job.company?.name ?? null,
        location: full || [location.city, location.country?.toUpperCase()].filter(Boolean).join(', '),
        workplace: location.remote ? 'Remote' : location.hybrid ? 'Hybrid' : null,
        employmentType: job.typeOfEmployment?.label ?? null,
        postedAt: job.releasedDate,
        excerpt: null,
      };
    },
  },

  /*
   * Workday's career-site search, a POST. Slug: host/tenant/site[?search]
   * (wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi). It answers
   * 20 at a time by relevance, not date, so up to WORKDAY_PAGES are read.
   * The list carries no description, and its date only as "Posted 6 Days Ago".
   */
  workday: {
    url: (slug) => {
      const at = workdaySlug(slug);
      return `https://${at.host}/wday/cxs/${at.tenant}/${at.site}/jobs`;
    },
    fetchBody: async (client, slug) => {
      const url = PLATFORMS.workday.url(slug);
      const all = [];

      for (let page = 0, offset = 0; page < WORKDAY_PAGES; page += 1, offset += WORKDAY_PAGE_SIZE) {
        const body = await client.postJson(url, {
          limit: WORKDAY_PAGE_SIZE,
          offset,
          searchText: workdaySlug(slug).search,
          appliedFacets: {},
        });
        const json = decode(body);
        if (!json) return offset === 0 ? body : JSON.stringify({ jobPostings: all });

        const jobs = Array.isArray(json.jobPostings) ? json.jobPostings : [];
        all.push(...jobs);
        if (jobs.length < WORKDAY_PAGE_SIZE || offset + WORKDAY_PAGE_SIZE >= (json.total ?? 0)) break;
      }

      return JSON.stringify({ jobPostings: all });
    },
    jobs: (payload) => (Array.isArray(payload?.jobPostings) ? payload.jobPostings : []),
    parse: (job, slug) => {
      const at = workdaySlug(slug);
      const base = at.host.endsWith('myworkdaysite.com')
        ? `https://${at.host}/recruiting/${at.tenant}/${at.site}`
        : `https://${at.host}/${at.site}`;
      const path = job.externalPath ?? null;

      return {
        externalId: String(job.bulletFields?.[0] ?? '').trim() || path,
        title: job.title,
        url: path ? base + path : null,
        company: null,
        location: job.locationsText,
        workplace: null,
        employmentType: null,
        postedAt: workdayPosted(job.postedOn),
        excerpt: null,
      };
    },
  },

  // Oracle HCM's candidate-experience API. Slug: host/siteNumber (eghj.fa.em2.oraclecloud.com/CX_1).
  oracle: {
    url: (slug) => {
      const { host, site } = oracleSlug(slug);
      return (
        `https://${host}/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true` +
        `&expand=requisitionList&finder=findReqs;siteNumber=${site},limit=50,sortBy=POSTING_DATES_DESC`
      );
    },
    jobs: (payload) => {
      const list = payload?.items?.[0]?.requisitionList;
      return Array.isArray(list) ? list : [];
    },
    parse: (job, slug) => {
      const { host, site } = oracleSlug(slug);
      return {
        externalId: job.Id,
        title: job.Title,
        url: `https://${host}/hcmUI/CandidateExperience/en/sites/${site}/job/${encodeURIComponent(job.Id ?? '')}`,
        company: null,
        location: job.PrimaryLocation,
        workplace: workplaceOf(String(job.WorkplaceTypeCode ?? '').replace(/^ORA_/, '').toLowerCase() || null),
        employmentType: null,
        postedAt: job.PostedDate,
        excerpt: stripHtml(job.ShortDescriptionStr),
      };
    },
  },
};

/** Workday's page size (its maximum) and how many pages one board may take. */
const WORKDAY_PAGE_SIZE = 20;
const WORKDAY_PAGES = 3;

/** wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi -> host, tenant, site, search. */
export function workdaySlug(slug) {
  const [path, search = ''] = String(slug).split('?');
  const [host, tenant, site] = path.split('/').filter(Boolean);
  return { host, tenant, site, search };
}

/** eghj.fa.em2.oraclecloud.com/CX_1 -> host, site number. */
function oracleSlug(slug) {
  const [host, ...rest] = String(slug).split('/');
  return { host, site: rest.join('/') };
}

/**
 * "Posted Today", "Posted Yesterday", "Posted 6 Days Ago" as ISO; "Posted 30+
 * Days Ago" as 31 days, so the age cut drops it rather than keeping it as undated.
 */
export function workdayPosted(text, now = Date.now()) {
  const value = String(text ?? '').toLowerCase();
  if (!value) return null;

  let days;
  if (value.includes('today')) days = 0;
  else if (value.includes('yesterday')) days = 1;
  else {
    const match = value.match(/(\d+)(\+?)\s+days?/);
    if (!match) return null;
    days = Number(match[1]) + (match[2] ? 1 : 0);
  }

  return new Date(now - days * 86_400_000).toISOString();
}

/**
 * "lever:klivvr" -> { platform, slug, name }. A third part names the company
 * for a feed that does not: "bamboohr:instabug:Instabug".
 *
 * @returns {{platform: string, slug: string, name: string|null}|null}
 */
export function parseBoard(entry) {
  const [platform, slug, ...rest] = String(entry ?? '')
    .split(':')
    .map((part) => part.trim());

  if (!platform || !slug || !PLATFORMS[platform.toLowerCase()]) return null;

  return { platform: platform.toLowerCase(), slug, name: rest.join(':') || null };
}

/**
 * @param {string} body
 * @param {{platform: string, slug: string, name: string|null}} board
 * @returns {object[]|null}  null when the body was not a feed at all
 */
export function parseBoardResponse(body, board) {
  const adapter = PLATFORMS[board.platform];
  const payload = adapter ? (adapter.decode ?? decode)(body) : null;

  if (!adapter || payload === null) return null;

  const items = [];

  for (const job of adapter.jobs(payload, board.slug)) {
    const raw = adapter.parse(job, board.slug);

    const externalId = clean(raw.externalId);
    const title = clean(raw.title);
    const url = clean(raw.url);

    if (!externalId || !title || !url) continue;

    const workplace = clean(raw.workplace);

    items.push({
      // Ids are only unique inside one board.
      externalId: `${board.platform}:${board.slug}:${externalId}`,
      title,
      url,
      company: clean(raw.company) ?? board.name ?? titleCase(board.slug),
      location: clean(raw.location),
      remote: workplace === 'Remote',
      workplace,
      employmentType: clean(raw.employmentType),
      postedAt: parseDate(raw.postedAt),
      excerpt: clean(raw.excerpt)?.slice(0, 4000) ?? null,
    });
  }

  return items;
}

function teamtailorHost(slug) {
  return slug.includes('.') ? slug : `${slug}.teamtailor.com`;
}

function zohoHost(slug) {
  return slug.includes('.') ? slug : `${slug}.zohorecruit.com`;
}

/** &#34; &quot; &#x27; and friends, decoded once. */
function unescapeEntities(value) {
  return String(value)
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

/** "career5.successfactors.eu/capgemitecP3/15638549/Egypt" */
function successFactorsBoard(slug) {
  const [host, company, unit, ...place] = String(slug).split('/');
  return { host, company, unit: unit || null, place: place.join('/') || null };
}

/** A SuccessFactors job's nameless <>15638549</> fields, in order. */
function unlabelled(job) {
  return [...job.matchAll(/<>([^<]*)<\/>/g)].map((match) => match[1].trim());
}

function fromRfc822(value) {
  const date = new Date(value ?? '');
  return value && !Number.isNaN(date.getTime()) ? date.toISOString() : null;
}

/** "10/01/2026": month first, read as UTC midnight. */
function usDate(value) {
  const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(String(value ?? '').trim());
  if (!match) return null;

  const [, month, day, year] = match;
  return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}T00:00:00Z`;
}

/** Every <tag>…</tag> in an XML body, as raw inner text. */
function xmlBlocks(body, tag) {
  return [...String(body).matchAll(new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`, 'g'))].map(
    (match) => match[1],
  );
}

/** The first <tag>'s text, CDATA unwrapped or entities decoded once. */
function xmlText(xml, tag) {
  const match = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml);
  if (!match) return null;

  const cdata = /^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/.exec(match[1]);
  if (cdata) return cdata[1];

  return match[1]
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;|&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function workplaceOf(value) {
  return WORKPLACES[String(value ?? '').toLowerCase()] ?? null;
}

function fromEpochMs(value) {
  const ms = Number(value);
  return Number.isFinite(ms) && ms > 0 ? new Date(ms).toISOString() : null;
}

function parseDate(value) {
  if (!value) return null;

  const date = new Date(String(value).replace(' UTC', 'Z').replace(' ', 'T'));
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** "mnt-halan" -> "Mnt Halan": a fallback name, better than none. */
function titleCase(slug) {
  return String(slug)
    .split(/[-_]/)
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(' ');
}

/** A 4xx arrives as an empty string and an error page as HTML: no feed. */
function decode(body) {
  const text = (body ?? '').trim();
  if (text === '' || !(text.startsWith('{') || text.startsWith('['))) return null;

  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function clean(value) {
  if (value === null || value === undefined) return null;

  const text = String(value)
    .replace(/[ \t]+/g, ' ')
    .trim();

  return text === '' ? null : text;
}

export default parseBoardResponse;
