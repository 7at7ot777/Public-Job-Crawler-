import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store } from '../src/store.js';
import { RecruiteraSource, isAgeOver, OUTAGE_AFTER } from '../src/sources/recruitera.js';
import { BlockedError } from '../src/errors.js';
import {
  parseSitemapSites,
  pickCategories,
  parseOpportunities,
  parseJobDetail,
  jobUrl,
  stripHtml,
  isJunkTitle,
} from '../src/sources/recruiteraParser.js';
import { evaluate } from '../src/filters.js';

const API = 'https://app.recruitera.ai/api/public/v1';
const IT = { id: 'cat-it', name: 'IT/Software Development' };

const daysAgo = (days) => new Date(Date.now() - days * 86_400_000).toISOString();

const detail = (id, overrides = {}) =>
  JSON.stringify({
    data: {
      available: true,
      id,
      title: 'Backend Developer',
      slug: `${id}-slug`,
      description: '<p><strong>About Acme</strong><br />We build payments.</p>',
      requirements: '<ul><li>2+ years of PHP &amp; Laravel</li></ul>',
      responsibilities: '<p>Build APIs.</p>',
      employment_type: 'Full-time',
      work_model: 'hybrid',
      location: 'Acme Head Office',
      location_details: { city: 'Cairo', country: 'EG' },
      experience: 'Entry Level',
      category: 'IT/Software Development',
      published_at: daysAgo(2),
      ...overrides,
    },
  });

/** Answers each URL from a table and records what was asked. */
function fakeClient(routes) {
  return {
    requests: [],
    lastResponse: null,
    async getHtml(url, query = {}) {
      const target = new URL(url);
      for (const [key, value] of Object.entries(query)) {
        if (value !== null && value !== undefined) target.searchParams.set(key, value);
      }

      const key = target.toString();
      this.requests.push(key);
      return routes[key] ?? '';
    },
  };
}

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-recruitera-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

function routes() {
  return {
    'https://app.recruitera.ai/sitemap.xml': `<urlset>
      <url><loc>https://app.recruitera.ai/careers/acme</loc></url>
      <url><loc>https://app.recruitera.ai/careers/acme/backend-developer</loc></url>
      <url><loc>https://app.recruitera.ai/careers/salesco</loc></url>
    </urlset>`,
    [`${API}/acme/opportunities/filters`]: JSON.stringify({
      data: { categories: [IT, { id: 'cat-sales', name: 'Sales/Retail' }] },
    }),
    [`${API}/salesco/opportunities/filters`]: JSON.stringify({
      data: { categories: [{ id: 'cat-sales', name: 'Sales/Retail' }] },
    }),
    [`${API}/acme/opportunities?category=cat-it`]: JSON.stringify({
      data: [
        { id: 'j1', slug: 'j1-slug', title: 'Backend Developer', days_since_posting: 2 },
        { id: 'j2', slug: 'j2-slug', title: 'Backend Developer London', days_since_posting: 1 },
        { id: 'j4', slug: 'j4-slug', title: 'Backend Developer Dubai', days_since_posting: 1 },
      ],
      meta: { next_cursor: 'MTI' },
    }),
    [`${API}/acme/opportunities?category=cat-it&cursor=MTI`]: JSON.stringify({
      data: [{ id: 'j3', slug: 'j3-slug', title: 'Old Backend Developer', days_since_posting: 200 }],
      meta: { next_cursor: null },
    }),
    [`${API}/acme/jobs/j1-slug`]: detail('j1'),
    [`${API}/acme/jobs/j2-slug`]: detail('j2', {
      title: 'Backend Developer London',
      location_details: { city: 'London', country: 'GB' },
    }),
    [`${API}/acme/jobs/j4-slug`]: detail('j4', {
      title: 'Backend Developer Dubai',
      location_details: { city: 'Dubai', country: 'AE' },
    }),
    [`${API}/acme`]: JSON.stringify({ data: { company: { name: 'Acme Payments' } } }),
  };
}

test('only company pages in the sitemap are sites', () => {
  const xml = `<loc>https://app.recruitera.ai/careers/paymob</loc>
    <loc>https://app.recruitera.ai/careers/paymob/data-engineer-7600</loc>
    <loc>https://app.recruitera.ai/careers/banquemisr-1/</loc>`;

  assert.deepEqual(parseSitemapSites(xml), ['paymob', 'banquemisr-1']);
});

test('categories are picked by name, ignoring case', () => {
  const body = JSON.stringify({ data: { categories: [IT, { id: 'x', name: 'Legal' }] } });

  assert.deepEqual(pickCategories(body, ['it/software development']), [IT]);
  assert.deepEqual(pickCategories('not json', ['IT/Software Development']), []);
});

test('an opportunities page yields its jobs and the next cursor', () => {
  const page = parseOpportunities(
    JSON.stringify({ data: [{ id: 'a', slug: 'a' }, { title: 'no id' }], meta: { next_cursor: 'MjQ' } }),
  );

  assert.equal(page.jobs.length, 1);
  assert.equal(page.nextCursor, 'MjQ');
});

test('a job detail names its city and country, and its text without markup', () => {
  const job = parseJobDetail(detail('j1'));

  assert.equal(job.country, 'EG');
  assert.equal(job.location, 'Cairo, Egypt');
  assert.equal(job.workplace, 'Hybrid');
  assert.match(job.excerpt, /^2\+ years of PHP & Laravel/);
  assert.doesNotMatch(job.excerpt, /</);
  assert.equal(parseJobDetail('{"errors":[]}'), null);
});

test('job urls are the public careers page', () => {
  assert.equal(
    jobUrl('paymob', 'account-manager-–-x'),
    'https://app.recruitera.ai/careers/paymob/account-manager-%E2%80%93-x',
  );
  assert.equal(stripHtml('<p>a&nbsp;b</p><p>c</p>'), 'a b\nc');
});

test('generated QA titles and untitled drafts are junk, real titles are not', () => {
  for (const title of [
    'Legacy Data Agent mun6kji05zs',
    'Global Operations Officer 1790713328542',
    'Untitled',
    'Test Knockout',
    'Bubble (No Code & Low Code) Developer (Copy)',
    '',
  ]) {
    assert.equal(isJunkTitle(title), true, title);
  }

  for (const title of [
    'Senior Backend Engineer - Python',
    'SOC Analyst L2',
    'Data Engineer',
    'Software Engineer (Node.js)',
    'IT Specialist 2026',
  ]) {
    assert.equal(isJunkTitle(title), false, title);
  }
});

test('a listing age decides only when it is known', () => {
  assert.equal(isAgeOver(31, 30), true);
  assert.equal(isAgeOver(30, 30), false);
  assert.equal(isAgeOver(null, 30), false);
  assert.equal(isAgeOver(100, 0), false);
});

test('keeps Egypt tech jobs, drops other countries and old listings', async () => {
  const client = fakeClient(routes());
  const source = new RecruiteraSource(client, tempStore());

  const items = await source.fetch();

  // j4 is in Dubai: kept, as the UAE, because BOARDS_GULF is on by default.
  assert.deepEqual(
    items.map((item) => item.externalId),
    ['j1', 'j4'],
  );
  assert.equal(items[1].market, 'United Arab Emirates');
  assert.equal(items[0].company, 'Acme Payments');
  assert.equal(items[0].market, 'Egypt');
  assert.equal(items[0].url, 'https://app.recruitera.ai/careers/acme/j1-slug');

  // The sales-only site cost one request, and the old job was never opened.
  assert.equal(client.requests.filter((url) => url.includes('salesco')).length, 1);
  assert.equal(client.requests.some((url) => url.endsWith('/jobs/j3-slug')), false);
});

test('a second run opens nothing it has already seen', async () => {
  const store = tempStore();

  const first = await new RecruiteraSource(fakeClient(routes()), store).fetch();
  for (const item of first) store.insertIfNew(item);

  const client = fakeClient(routes());
  const second = await new RecruiteraSource(client, store).fetch();

  assert.deepEqual(second, []);
  assert.equal(client.requests.some((url) => url.includes('/jobs/')), false);
});

test('the same role posted again under a new id is not stored twice', async () => {
  const store = tempStore();
  const [item] = await new RecruiteraSource(fakeClient(routes()), store).fetch();

  assert.equal(store.insertIfNew(item).created, true);
  assert.equal(store.insertIfNew({ ...item, externalId: 'j1-reposted' }).created, false);
});

test('an on-site Cairo job passes the filters a remote-only crawl would drop', async () => {
  const [item] = await new RecruiteraSource(fakeClient(routes()), tempStore()).fetch();

  assert.equal(evaluate(item).pass, true);
  assert.equal(evaluate({ ...item, title: 'Senior Backend Developer' }).pass, false);
});

test('a job dated over a week ago is dropped, a job with no date is still sent', async () => {
  const pages = routes();

  pages[`${API}/acme/opportunities?category=cat-it`] = JSON.stringify({
    data: [
      { id: 'j5', slug: 'j5-slug', title: 'Backend Developer', days_since_posting: 10 },
      { id: 'j6', slug: 'j6-slug', title: 'Backend Developer' },
    ],
    meta: { next_cursor: null },
  });
  pages[`${API}/acme/jobs/j5-slug`] = detail('j5', { published_at: daysAgo(10) });
  pages[`${API}/acme/jobs/j6-slug`] = detail('j6', { published_at: null });

  const items = await new RecruiteraSource(fakeClient(pages), tempStore()).fetch();

  assert.deepEqual(
    items.map((item) => [item.externalId, item.postedAt]),
    [['j6', null]],
  );
});

/** A client whose requests to the matching urls never get an answer. */
function unreachable(pages, matches = () => true) {
  const client = fakeClient(pages);
  const answer = client.getHtml.bind(client);

  client.getHtml = async (url, query) => {
    if (matches(url)) {
      client.requests.push(url);
      throw new BlockedError('Recruitera request failed after 3 attempts: fetch failed', [], true);
    }
    return answer(url, query);
  };

  return client;
}

test('a run with the network down is reported as one to retry, not as an empty pass', async () => {
  const source = new RecruiteraSource(unreachable(routes()), tempStore());

  await assert.rejects(source.fetch(), (error) => {
    assert.ok(error instanceof BlockedError);
    assert.equal(error.retryable, true);
    assert.deepEqual(error.partialItems, []);
    return true;
  });
});

test('the network dropping mid-run stops it after a few unanswered requests in a row', async () => {
  const pages = routes();
  const sites = Array.from({ length: 20 }, (_, i) => `site${i}`);
  pages['https://app.recruitera.ai/sitemap.xml'] = `<urlset>${sites
    .map((site) => `<url><loc>https://app.recruitera.ai/careers/${site}</loc></url>`)
    .join('')}</urlset>`;

  const client = unreachable(pages, (url) => url.startsWith(API));

  await assert.rejects(new RecruiteraSource(client, tempStore()).fetch(), (error) => {
    assert.equal(error.retryable, true);
    assert.match(error.message, new RegExp(`${OUTAGE_AFTER} requests in a row`));
    return true;
  });

  // The sitemap, then OUTAGE_AFTER sites — not all twenty, each timing out.
  assert.equal(client.requests.length, 1 + OUTAGE_AFTER);
});

test('one unreachable site is skipped and the run still counts as done', async () => {
  const client = unreachable(routes(), (url) => url.includes('/salesco/'));
  const items = await new RecruiteraSource(client, tempStore()).fetch();

  assert.deepEqual(items.map((item) => item.externalId), ['j1', 'j4']);
});

test('jobs found before the network dropped are delivered, and not marked checked', async () => {
  const pages = routes();
  pages[`${API}/acme/opportunities?category=cat-it`] = JSON.stringify({
    data: Array.from({ length: 8 }, (_, i) => ({
      id: `n${i}`,
      slug: `n${i}-slug`,
      title: 'Backend Developer',
      days_since_posting: 1,
    })),
    meta: { next_cursor: null },
  });
  pages[`${API}/acme/jobs/n0-slug`] = detail('n0');

  const store = tempStore();
  const client = unreachable(pages, (url) => /\/jobs\/n[1-9]-slug$/.test(url));

  await assert.rejects(new RecruiteraSource(client, store).fetch(), (error) => {
    assert.equal(error.retryable, true);
    assert.deepEqual(error.partialItems.map((item) => item.externalId), ['n0']);
    return true;
  });

  // The unanswered jobs are opened again on the retry.
  const checked = store.checkedIds('recruitera');
  assert.equal(checked.has('n0'), true);
  assert.equal(checked.has('n1'), false);
});
