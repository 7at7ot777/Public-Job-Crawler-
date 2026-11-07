import { test } from 'node:test';
import assert from 'node:assert/strict';
import config from '../src/config.js';
import { TanqeebSource } from '../src/sources/tanqeeb.js';

const EGYPT = 'https://egypt.tanqeeb.com/jobs/search';
const UAE = 'https://uae.tanqeeb.com/jobs/search';

const card = (id, title, place) => `
  <div class="search-job-card-top">
    <h2 class="search-job-title" title="${title}">
      <a class="search-job-title-link" href="/jobs-in-x/all/jobs/${id}.html">${title}</a>
    </h2>
    <div class="search-job-workplace-location">On-site - ${place}</div>
  </div>`;

/** Answers each search URL from a table and records what was asked. */
function fakeClient(pages) {
  return {
    requests: [],
    lastResponse: null,
    async getHtml(url, query = {}) {
      this.requests.push({ url, ...query });
      return pages[url] ?? '';
    },
  };
}

/** Runs `fn` with the Tanqeeb settings patched, restoring them afterwards. */
async function withSettings(patch, fn) {
  const settings = config.sources.tanqeeb;
  const saved = { ...settings };
  Object.assign(settings, patch);

  try {
    return await fn();
  } finally {
    Object.assign(settings, saved);
  }
}

test('Tanqeeb searches Egypt first, then Saudi Arabia and the UAE (BOARDS_GULF) by default', () => {
  assert.deepEqual(Object.values(config.sources.tanqeeb.sites), ['Egypt', 'Saudi Arabia', 'United Arab Emirates']);
  assert.equal(Object.keys(config.sources.tanqeeb.sites)[0], EGYPT);
});

test('every site is searched, and each job is placed by the site that listed it', async () => {
  const client = fakeClient({
    [EGYPT]: card('1', 'PHP Developer', 'Egypt - Cairo') + card('3', 'Laravel Developer', 'UAE - Dubai'),
    // The same Dubai job is listed on both sites; it is kept once.
    [UAE]: card('2', 'Backend Developer', 'UAE - Dubai') + card('3', 'Laravel Developer', 'UAE - Dubai'),
  });

  const items = await withSettings(
    { sites: { [EGYPT]: 'Egypt', [UAE]: 'United Arab Emirates' }, fetchDetails: false },
    () => new TanqeebSource(client).fetch({ keywords: ['php'] }),
  );

  assert.deepEqual(
    client.requests.map((request) => request.url),
    [EGYPT, UAE],
  );
  assert.deepEqual(
    items.map((item) => [item.externalId, item.market]),
    [
      ['1', 'Egypt'],
      ['3', 'Egypt'],
      ['2', 'United Arab Emirates'],
    ],
  );
});
