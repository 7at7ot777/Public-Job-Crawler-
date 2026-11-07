import { test } from 'node:test';
import assert from 'node:assert/strict';
import config from '../src/config.js';
import { LinkedInJobsSource, rotationWindow } from '../src/sources/linkedinJobs.js';
import { BlockedError } from '../src/errors.js';

/*
 * The enrich path reads the same filter config as a real run. Pinned empty so
 * the fixtures below are judged only by the block logic under test — not by
 * whatever .env happens to hold.
 */
Object.assign(config.filters, {
  enabled: true,
  titleTerms: [],
  blockedLocations: [],
  locations: [],
  seniorityExclude: [],
});

// Off unless a test turns it on: .env may enable it for the real crawl, and
// the tests below that search every market expect the whole plan.
config.sources.linkedin_jobs.rotateLocations = false;

const card = (id) => `
  <li>
    <div class="base-card" data-entity-urn="urn:li:jobPosting:${id}">
      <a class="base-card__full-link" href="https://www.linkedin.com/jobs/view/${id}"></a>
      <h3 class="base-search-card__title">Backend Engineer</h3>
      <h4 class="base-search-card__subtitle">Acme</h4>
      <span class="job-search-card__location">Cairo, Egypt</span>
    </div>
  </li>`;

const DETAIL = `
  <div class="description__text">Node.js and PostgreSQL.</div>
  <div class="description__job-criteria-item">
    <h3 class="description__job-criteria-subheader">Seniority level</h3>
    <span class="description__job-criteria-text">Mid-Senior level</span>
  </div>`;

const isDetail = (url) => url.includes('/jobPosting/');

/** A client that answers from a script and records every call. */
function fakeClient(handler) {
  return {
    calls: [],
    async getHtml(url, query) {
      this.calls.push(url);
      return handler(url, query);
    },
  };
}

test('a block during detail fetches keeps the pass instead of discarding it', async () => {
  const client = fakeClient((url) => {
    if (!isDetail(url)) return card(1) + card(2) + card(3);
    if (url.endsWith('/1')) return DETAIL;

    throw new BlockedError('detail page throttled');
  });

  const source = new LinkedInJobsSource(client);
  const items = await source.fetch({ keywords: ['backend'], locations: ['Egypt'], pages: 1 });

  assert.equal(items.length, 3);
  assert.equal(items[0].excerpt, 'Node.js and PostgreSQL.');
  assert.equal(items[0].seniority, 'Mid-Senior level');
  assert.equal(items[1].excerpt, null);
  assert.equal(items[2].excerpt, null);

  // The block on the second page ended the loop: the third was never asked.
  assert.equal(client.calls.filter(isDetail).length, 2);
});

test('a search block hands back the cards even when details are also blocked', async () => {
  const client = fakeClient((url, query) => {
    if (isDetail(url)) throw new BlockedError('detail page throttled');
    if (query.keywords === 'backend') return card(1) + card(2);

    throw new BlockedError('search throttled');
  });

  const source = new LinkedInJobsSource(client);

  await assert.rejects(
    () => source.fetch({ keywords: ['backend', 'laravel'], locations: ['Egypt'], pages: 1 }),
    (error) => {
      assert.ok(error instanceof BlockedError);
      // The search block's own error — not the detail block replacing it.
      assert.match(error.message, /search throttled/);
      assert.deepEqual(
        error.partialItems.map((item) => item.externalId),
        ['1', '2'],
      );
      return true;
    },
  );

  // One detail attempt was enough to learn the remote is still angry.
  assert.equal(client.calls.filter(isDetail).length, 1);
});

test('remoteKeywords are searched only as remote work, in the borderless markets', async () => {
  const searches = [];

  const client = fakeClient((url, query) => {
    if (isDetail(url)) return DETAIL;

    searches.push(query);
    return '';
  });

  const pinned = {
    keywords: config.keywords,
    locations: config.locations,
    remoteLocations: config.remoteLocations,
    remoteKeywords: config.remoteKeywords,
    remoteKeywordLocations: config.remoteKeywordLocations,
  };

  Object.assign(config, {
    keywords: ['Laravel Developer'],
    locations: ['Egypt'],
    remoteLocations: [],
    remoteKeywords: ['Data Entry'],
    remoteKeywordLocations: ['Worldwide'],
  });

  try {
    const source = new LinkedInJobsSource(client);
    await source.fetch({ pages: 1 });
  } finally {
    Object.assign(config, pinned);
  }

  const stack = searches.filter((query) => query.keywords === 'Laravel Developer');
  const remote = searches.filter((query) => query.keywords === 'Data Entry');

  // The stack keyword stays in the local market, with no work-type filter.
  assert.deepEqual(
    stack.map((query) => query.location),
    ['Egypt'],
  );
  assert.equal(stack[0].f_WT, '');

  // The remote keyword never reaches Egypt, and asks for remote work only.
  assert.deepEqual(
    remote.map((query) => query.location),
    ['Worldwide'],
  );
  assert.equal(remote[0].f_WT, '2');

  // ...at the no-experience levels, not the stack's.
  assert.equal(remote[0].f_E, config.sources.linkedin_jobs.remoteKeywordExperience);
  assert.notEqual(remote[0].f_E, stack[0].f_E);
});

test('a manual --keyword run is not joined by the remoteKeywords group', async () => {
  const searches = [];

  const client = fakeClient((url, query) => {
    if (isDetail(url)) return DETAIL;

    searches.push(query);
    return '';
  });

  const source = new LinkedInJobsSource(client);
  await source.fetch({ keywords: ['backend'], locations: ['Egypt'], pages: 1 });

  assert.deepEqual(
    searches.map((query) => query.keywords),
    ['backend'],
  );
});

/** A store with only what the source asks of one. */
function fakeStore(storedIds = []) {
  const state = new Map();
  const stored = new Set(storedIds.map(String));

  return {
    state,
    getState: (key) => state.get(key) ?? null,
    setState: (key, value) => state.set(key, String(value)),
    findByExternalId: (source, id) => (stored.has(String(id)) ? { id } : null),
  };
}

/** Run `body` with rotation on and the search lists pinned, then restore both. */
async function withRotation(lists, body) {
  const settings = config.sources.linkedin_jobs;
  const pinned = {
    keywords: config.keywords,
    locations: config.locations,
    remoteLocations: config.remoteLocations,
    remoteKeywords: config.remoteKeywords,
    remoteKeywordLocations: config.remoteKeywordLocations,
  };
  const pinnedSettings = {
    rotateLocations: settings.rotateLocations,
    rotateIntervalMinutes: settings.rotateIntervalMinutes,
    timeWindow: settings.timeWindow,
    fetchDetails: settings.fetchDetails,
  };

  Object.assign(config, {
    remoteLocations: [],
    remoteKeywords: [],
    remoteKeywordLocations: [],
    ...lists,
  });
  Object.assign(settings, {
    rotateLocations: true,
    rotateIntervalMinutes: 10,
    timeWindow: 'r3600',
    fetchDetails: true,
  });

  try {
    return await body();
  } finally {
    Object.assign(config, pinned);
    Object.assign(settings, pinnedSettings);
  }
}

test('rotation searches one location per run, every keyword, and cycles', async () => {
  const searches = [];
  const client = fakeClient((url, query) => {
    if (!isDetail(url)) searches.push(query);
    return '';
  });

  const store = fakeStore();
  const lists = { keywords: ['Laravel', 'Node.js'], locations: ['Egypt', 'Cairo', 'Giza'] };

  await withRotation(lists, async () => {
    const source = new LinkedInJobsSource(client);
    source.useStore(store);

    for (let run = 0; run < 4; run += 1) await source.fetch({ pages: 1 });
  });

  const byRun = [0, 2, 4, 6].map((start) => searches.slice(start, start + 2));

  assert.equal(searches.length, 8);
  assert.deepEqual(
    byRun.map((run) => run.map((query) => query.location)),
    [
      ['Egypt', 'Egypt'],
      ['Cairo', 'Cairo'],
      ['Giza', 'Giza'],
      // A full cycle, then back to the start.
      ['Egypt', 'Egypt'],
    ],
  );
  assert.deepEqual(
    byRun[0].map((query) => query.keywords),
    ['Laravel', 'Node.js'],
  );
  assert.equal(store.state.get('linkedin_jobs:rotation_next'), '1');
});

test('rotation keeps LINKEDIN_TIME_WINDOW until the cycle outgrows it', async () => {
  const windowFor = async (locations) => {
    const searches = [];
    const client = fakeClient((url, query) => {
      if (!isDetail(url)) searches.push(query);
      return '';
    });

    await withRotation({ keywords: ['Laravel'], locations }, () =>
      new LinkedInJobsSource(client).fetch({ pages: 1 }),
    );

    return searches[0].f_TPR;
  };

  assert.equal(await windowFor(['A', 'B', 'C']), 'r3600');
  // Six locations x 10 min, plus one interval of margin.
  assert.equal(await windowFor(['A', 'B', 'C', 'D', 'E', 'F']), 'r4200');
});

test('rotationWindow only ever widens the configured window', () => {
  assert.equal(rotationWindow('r3600', 3, 10), 'r3600');
  assert.equal(rotationWindow('r3600', 5, 10), 'r3600');
  assert.equal(rotationWindow('r3600', 6, 10), 'r4200');
  assert.equal(rotationWindow('r3600', 10, 10), 'r6600');
  assert.equal(rotationWindow('r86400', 10, 10), 'r86400');
  assert.equal(rotationWindow('r3600', 3, 30), 'r7200');
});

test('rotation stands aside for a run that asked for something specific', async () => {
  const searches = [];
  const client = fakeClient((url, query) => {
    if (!isDetail(url)) searches.push(query);
    return '';
  });

  const store = fakeStore();

  await withRotation({ keywords: ['Laravel'], locations: ['Egypt', 'Cairo'] }, async () => {
    const source = new LinkedInJobsSource(client);
    source.useStore(store);

    // The startup pass and a bot /jobs request carry their own window.
    await source.fetch({ pages: 1, timeWindow: 'r86400' });
  });

  assert.deepEqual(
    searches.map((query) => [query.location, query.f_TPR]),
    [
      ['Egypt', 'r86400'],
      ['Cairo', 'r86400'],
    ],
  );
  // ...and leaves the rotation where it was.
  assert.equal(store.state.size, 0);
});

test('a blocked rotating run searches the same location again next time', async () => {
  const searches = [];
  let block = true;

  const client = fakeClient((url, query) => {
    if (isDetail(url)) return DETAIL;
    searches.push(query.location);
    if (block) throw new BlockedError('search throttled');
    return '';
  });

  const store = fakeStore();

  await withRotation({ keywords: ['Laravel'], locations: ['Egypt', 'Cairo'] }, async () => {
    const source = new LinkedInJobsSource(client);
    source.useStore(store);

    await assert.rejects(() => source.fetch({ pages: 1 }), BlockedError);
    block = false;
    await source.fetch({ pages: 1 });
  });

  assert.deepEqual(searches, ['Egypt', 'Egypt']);
});

test('a dry run does not move the rotation', async () => {
  const client = fakeClient(() => '');
  const store = fakeStore();

  await withRotation({ keywords: ['Laravel'], locations: ['Egypt', 'Cairo'] }, async () => {
    const source = new LinkedInJobsSource(client);
    source.useStore(store);
    await source.fetch({ pages: 1, dryRun: true });
  });

  assert.equal(store.state.size, 0);
});

test('postings already stored are dropped before their detail page is fetched', async () => {
  const client = fakeClient((url) => (isDetail(url) ? DETAIL : card(1) + card(2) + card(3)));

  const source = new LinkedInJobsSource(client);
  source.useStore(fakeStore([1, 3]));

  const items = await source.fetch({ keywords: ['backend'], locations: ['Egypt'], pages: 1 });

  assert.deepEqual(
    items.map((item) => item.externalId),
    ['2'],
  );
  assert.deepEqual(client.calls.filter(isDetail), [
    `${config.sources.linkedin_jobs.detailUrl}2`,
  ]);
});
