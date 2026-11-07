import { test } from 'node:test';
import assert from 'node:assert/strict';
import { WorkableSource, placedIn } from '../src/sources/workable.js';
import { parseSearchResponse } from '../src/sources/workableParser.js';

const SEARCH = 'https://jobs.workable.com/api/v1/jobs';

const daysAgo = (days) => new Date(Date.now() - days * 86_400_000).toISOString();

const job = (id, overrides = {}) => ({
  id,
  title: 'Backend Developer (Laravel)',
  state: 'published',
  description: '<p>We build <strong>payments</strong> &amp; more.</p>',
  requirementsSection: '<ul><li>2+ years of PHP</li></ul>',
  employmentType: 'Full-time',
  url: `https://jobs.workable.com/view/${id}/backend-developer`,
  locations: ['Cairo, Cairo Governorate, Egypt'],
  location: { city: 'Cairo', countryName: 'Egypt' },
  created: daysAgo(1),
  company: { title: 'Acme' },
  workplace: 'hybrid',
  ...overrides,
});

const page = (jobs, nextPageToken = null) =>
  JSON.stringify({ title: 'Workable', totalSize: jobs.length, nextPageToken, jobs });

/** Answers each search from a function and records what was asked. */
function fakeClient(answer) {
  return {
    requests: [],
    lastResponse: null,
    async getHtml(url, query = {}) {
      const target = new URL(url);
      for (const [key, value] of Object.entries(query)) {
        if (value !== null && value !== undefined) target.searchParams.set(key, value);
      }

      this.requests.push(target);
      return answer(target) ?? '';
    },
  };
}

test('parses a search response into items', () => {
  const { items, nextPageToken } = parseSearchResponse(page([job('a')], 'tok'), 'PHP Developer');

  assert.equal(nextPageToken, 'tok');
  assert.equal(items.length, 1);

  const [item] = items;
  assert.equal(item.externalId, 'a');
  assert.equal(item.company, 'Acme');
  assert.equal(item.location, 'Cairo, Cairo Governorate, Egypt');
  assert.equal(item.workplace, 'Hybrid');
  assert.equal(item.remote, false);
  assert.equal(item.keyword, 'PHP Developer');
  assert.match(item.excerpt, /2\+ years of PHP/);
  assert.match(item.excerpt, /payments & more/);
});

test('drops the TELECOMMUTE marker and marks remote jobs', () => {
  const { items } = parseSearchResponse(
    page([job('r', { locations: ['TELECOMMUTE', 'Egypt'], workplace: 'remote' })]),
    'k',
  );

  assert.equal(items[0].location, 'Egypt');
  assert.equal(items[0].remote, true);
  assert.equal(items[0].workplace, 'Remote');
});

test('skips unpublished jobs and anything that is not JSON', () => {
  assert.equal(parseSearchResponse(page([job('x', { state: 'closed' })]), 'k').items.length, 0);
  assert.deepEqual(parseSearchResponse('<html>challenge</html>', 'k').items, []);
  assert.deepEqual(parseSearchResponse('', 'k').items, []);
});

test('placedIn matches the country or the last part of a place', () => {
  assert.equal(placedIn('Egypt', 'Egypt', []), true);
  assert.equal(placedIn('Egypt', null, ['Cairo, Cairo Governorate, Egypt']), true);
  assert.equal(placedIn('Egypt', 'Poland', ['Warsaw, Poland']), false);
});

test('searches every keyword x location with day_range and dedups across them', async () => {
  const client = fakeClient(() => page([job('same')]));
  const source = new WorkableSource(client);

  const items = await source.fetch({
    keywords: ['PHP Developer', 'Laravel Developer'],
    locations: ['Egypt', 'Saudi Arabia'],
    maxAgeDays: 7,
  });

  assert.equal(client.requests.length, 4);
  assert.ok(client.requests.every((url) => url.searchParams.get('day_range') === '7'));
  assert.equal(items.length, 1);
  assert.equal(items[0].source, 'workable');
  assert.equal(items[0].itemType, 'job');
  assert.equal(items[0].market, 'Egypt');
});

test('leaves market unset for a job that is not in the searched country', async () => {
  const client = fakeClient(() =>
    page([job('pl', { locations: ['Warsaw, Poland'], location: { countryName: 'Poland' } })]),
  );

  const [item] = await new WorkableSource(client).fetch({ keywords: ['k'], locations: ['Egypt'] });

  assert.equal(item.market, null);
});

test('follows pageToken up to maxPages and drops old jobs', async () => {
  const client = fakeClient((url) =>
    url.searchParams.get('pageToken')
      ? page([job('old', { created: daysAgo(30) })])
      : page([job('new')], 'next'),
  );

  const items = await new WorkableSource(client).fetch({
    keywords: ['k'],
    locations: ['Egypt'],
    pages: 2,
    maxAgeDays: 7,
  });

  assert.equal(client.requests.length, 2);
  assert.equal(client.requests[1].searchParams.get('pageToken'), 'next');
  assert.deepEqual(
    items.map((item) => item.externalId),
    ['new'],
  );
  assert.ok(client.requests[0].toString().startsWith(SEARCH));
});
