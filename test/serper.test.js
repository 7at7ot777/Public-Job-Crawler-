import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import config from '../src/config.js';
import { Store } from '../src/store.js';
import { Pipeline } from '../src/pipeline.js';
import { BlockedError } from '../src/errors.js';
import LinkedInGuestClient from '../src/sources/linkedinClient.js';
import LinkedInPostsSource from '../src/sources/linkedinPosts.js';
import SerperBudget, { dayOf } from '../src/sources/serperBudget.js';
import { planQueries, fillTemplate, placeFor, YieldLedger } from '../src/sources/searchPlan.js';

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'serper-test-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

const TEMPLATE = 'site:linkedin.com/posts "{keyword}" "{location}" hiring';

/** A permalink whose activity id says it was written an hour ago. */
function freshPostUrl(n) {
  const id = ((BigInt(Date.now() - 3_600_000) << 22n) + BigInt(n)).toString();
  return `https://www.linkedin.com/posts/acme_hiring-activity-${id}-HaYU`;
}

/** A search client that answers every query with `results(query)` and records the queries. */
function fakeSearch(results = () => []) {
  const queries = [];
  return {
    queries,
    postJson: async (_url, body) => {
      queries.push(body.q);
      return JSON.stringify({ organic: results(body.q) });
    },
  };
}

async function withSettings(overrides, fn) {
  const settings = config.sources.linkedin_posts;
  const saved = Object.fromEntries(Object.keys(overrides).map((key) => [key, settings[key]]));
  Object.assign(settings, overrides);

  try {
    return await fn(settings);
  } finally {
    Object.assign(settings, saved);
  }
}

test('pairs mode asks one query per keyword x place', () => {
  const queries = planQueries({
    template: TEMPLATE,
    keywords: ['Backend', 'Full Stack'],
    locations: ['Egypt', 'Cairo', 'Giza'],
    queryMode: 'pairs',
  });

  assert.equal(queries.length, 6);
  assert.equal(queries[0].query, 'site:linkedin.com/posts "Backend" "Egypt" hiring');
  assert.deepEqual(queries[0].places, ['Egypt']);
});

test('grouped mode puts the places in OR groups of groupSize', () => {
  const places = ['Egypt', 'Cairo', 'New Cairo', 'Giza', 'Maadi', 'Dokki', 'Nasr City'];
  const queries = planQueries({
    template: TEMPLATE,
    keywords: ['Backend', 'Full Stack', 'Software Engineer'],
    locations: places,
    queryMode: 'grouped',
    groupSize: 6,
  });

  // 3 keywords x ceil(7 / 6) groups.
  assert.equal(queries.length, 6);
  assert.equal(
    queries[0].query,
    'site:linkedin.com/posts "Backend" ("Egypt" OR "Cairo" OR "New Cairo" OR "Giza" OR "Maadi" OR "Dokki") hiring',
  );
  // A group of one is a plain quoted place, not "(…)".
  assert.equal(queries[1].query, 'site:linkedin.com/posts "Backend" "Nasr City" hiring');
});

test('a template without quotes around {location} still gets the group', () => {
  assert.equal(
    fillTemplate('site:x.com {keyword} {location}', 'Backend', ['Cairo', 'Giza']),
    'site:x.com Backend ("Cairo" OR "Giza")',
  );
});

test('a grouped result is labelled with the place it names, longest match first', () => {
  const planned = { places: ['Cairo', 'New Cairo', 'Giza'] };

  assert.equal(placeFor(planned, 'We are hiring in New Cairo', null), 'New Cairo');
  assert.equal(placeFor(planned, null, 'Office in giza'), 'Giza');
  assert.equal(placeFor(planned, 'Remote role'), null);
  assert.equal(placeFor({ places: ['Egypt'] }, 'anything'), 'Egypt');
});

test('a query is cold after coldAfter empty runs and is asked again every coldEveryDays', () => {
  const store = tempStore();
  let now = Date.UTC(2026, 9, 1);
  const ledger = new YieldLedger(store, 'linkedin_posts', { coldAfter: 2, coldEveryDays: 3 }, () => now);
  const query = { key: 'Backend|Maadi' };

  assert.equal(ledger.isDue(query), true);
  ledger.record(query, 0);
  assert.equal(ledger.isDue(query), true, 'one miss is not cold');
  ledger.record(query, 0);
  assert.equal(ledger.isDue(query), false, 'two misses in a row is cold');

  now += 2 * 86_400_000;
  assert.equal(ledger.isDue(query), false);
  now += 86_400_000;
  assert.equal(ledger.isDue(query), true, 'three days on, asked again');

  ledger.record(query, 4);
  assert.deepEqual(
    ledger.report().map(({ query: key, runs, hits, misses }) => [key, runs, hits, misses]),
    [['Backend|Maadi', 3, 4, 0]],
  );
  assert.equal(ledger.isDue(query), true, 'a hit warms it');
});

test('coldAfter 0 never skips', () => {
  const store = tempStore();
  const ledger = new YieldLedger(store, 'linkedin_posts', { coldAfter: 0, coldEveryDays: 3 });
  const query = { key: 'k' };

  for (let i = 0; i < 10; i += 1) ledger.record(query, 0);
  assert.equal(ledger.isDue(query), true);
});

test('the budget counts queries per day and stops at the cap with an alert', () => {
  const store = tempStore();
  const budget = new SerperBudget(store, { ...config.serper, dailyCap: 2 });

  budget.spend();
  budget.spend();

  assert.throws(
    () => budget.spend(),
    (error) => error instanceof BlockedError && error.alert === 'serper-cap',
  );

  const usage = budget.usage();
  assert.equal(usage.today, 2);
  assert.equal(usage.month, 2);
  assert.equal(usage.days[dayOf()], 2);
});

test('the balance is read at most once a day, and remembered', async () => {
  const store = tempStore();
  const budget = new SerperBudget(store, { ...config.serper, apiKey: 'k', lowBalance: 300 });
  let calls = 0;
  const answer = async () => {
    calls += 1;
    return { ok: true, json: async () => ({ balance: 50 }) };
  };

  assert.equal((await budget.dailyBalanceAlert(answer)).kind, 'serper-low-balance');
  assert.equal(await budget.dailyBalanceAlert(answer), null);
  assert.equal(calls, 1);
  assert.equal(budget.lastBalance(), 50);
  assert.equal(await new SerperBudget(null).dailyBalanceAlert(answer), null, 'no store, no check');
});

test('the budget with no cap never stops', () => {
  const budget = new SerperBudget(null, { ...config.serper, dailyCap: 0 });
  for (let i = 0; i < 500; i += 1) budget.spend();
  assert.equal(budget.used(), 500);
});

test('a balance under the threshold is an alert; an unreadable one is not', async () => {
  const settings = { ...config.serper, apiKey: 'k', lowBalance: 300 };
  const budget = new SerperBudget(null, settings);
  const answer = (body, ok = true) => async () => ({ ok, json: async () => body });

  assert.equal((await budget.lowBalanceAlert(answer({ balance: 120 }))).kind, 'serper-low-balance');
  assert.equal(await budget.lowBalanceAlert(answer({ balance: 2000 })), null);
  assert.equal(await budget.lowBalanceAlert(answer({ something: 'else' })), null);
  assert.equal(await budget.lowBalanceAlert(answer({}, false)), null);
  assert.equal(
    await budget.lowBalanceAlert(async () => {
      throw new Error('offline');
    }),
    null,
  );
  // Off without a key.
  assert.equal(
    await new SerperBudget(null, { ...settings, apiKey: '' }).balance(answer({ balance: 1 })),
    null,
  );
});

test('the posts source spends one budget query per request and stops at the cap', async () => {
  const store = tempStore();
  const search = fakeSearch((q) => (q.includes('Egypt') ? [{ link: freshPostUrl(1), title: 'Hiring' }] : []));

  await withSettings({ engine: 'serper', fetchDetails: false }, async () => {
    const savedCap = config.serper.dailyCap;
    const savedBalance = config.serper.lowBalance;
    config.serper.dailyCap = 2;
    config.serper.lowBalance = 0;

    try {
      const source = new LinkedInPostsSource(search);
      source.useStore(store);

      await assert.rejects(
        source.fetch({ keywords: ['Backend'], locations: ['Egypt', 'Cairo', 'Giza'], pages: 1 }),
        (error) => {
          assert.equal(error.alert, 'serper-cap');
          // The post found before the cap is handed back.
          assert.equal(error.partialItems.length, 1);
          return true;
        },
      );

      assert.equal(search.queries.length, 2);
      assert.equal(new SerperBudget(store).used(), 2);
    } finally {
      config.serper.dailyCap = savedCap;
      config.serper.lowBalance = savedBalance;
    }
  });
});

test('grouped mode sends fewer queries and labels each post with its place', async () => {
  // The same post answers both keywords: one item, not two.
  const url = freshPostUrl(2);
  const search = fakeSearch(() => [
    { link: url, title: 'Acme on LinkedIn: Backend developer wanted', snippet: 'Office in Maadi' },
  ]);

  await withSettings(
    { engine: 'serper', fetchDetails: false, queryMode: 'grouped', groupSize: 6 },
    async () => {
      const items = await new LinkedInPostsSource(search).fetch({
        keywords: ['Backend', 'Full Stack'],
        locations: ['Egypt', 'Cairo', 'Maadi'],
        pages: 1,
      });

      assert.equal(search.queries.length, 2);
      assert.match(search.queries[0], /\("Egypt" OR "Cairo" OR "Maadi"\)/);
      assert.equal(items.length, 1);
      assert.equal(items[0].location, 'Maadi');
    },
  );
});

test('a scheduled run skips cold queries; an explicit one asks them all', async () => {
  const store = tempStore();
  const url = freshPostUrl(3);
  const search = fakeSearch((q) => (q.includes('"Egypt"') ? [{ link: url, title: 'Hiring' }] : []));

  await withSettings(
    {
      engine: 'serper',
      fetchDetails: false,
      queryMode: 'pairs',
      coldAfter: 1,
      coldEveryDays: 3,
      keywords: ['Backend'],
      locations: ['Egypt', 'Maadi'],
    },
    async () => {
      const source = new LinkedInPostsSource(search);
      source.useStore(store);
      // Offline: the balance is "already read today".
      store.setState('serper.balance.day', dayOf());

      await source.fetch({ pages: 1 });
      assert.equal(search.queries.length, 2);

      // Maadi found nothing once, so with coldAfter 1 it is skipped next run.
      await source.fetch({ pages: 1 });
      assert.equal(search.queries.length, 3);
      assert.match(search.queries[2], /"Egypt"/);

      await source.fetch({ pages: 1, locations: ['Egypt', 'Maadi'] });
      assert.equal(search.queries.length, 5, 'explicit lists ask everything');
    },
  );
});

test('a JSON API 4xx with a reason is a block flagged as an alert, not an empty answer', async () => {
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ message: 'Not enough credits', statusCode: 400 }), { status: 400 });

  try {
    const client = new LinkedInGuestClient({ label: 'Serper', delayMs: 0 });
    await assert.rejects(client.postJson('https://google.serper.dev/search', { q: 'x' }), (error) => {
      assert.ok(error instanceof BlockedError);
      assert.equal(error.alert, 'api-refused');
      assert.match(error.message, /Not enough credits/);
      return true;
    });

    // A page's 4xx is still "nothing here".
    globalThis.fetch = async () => new Response('gone', { status: 404 });
    assert.equal(await client.getHtml('https://example.com/job/1'), '');
  } finally {
    globalThis.fetch = realFetch;
  }
});

test('the pipeline sends an alert once a day, and only once it is through', async () => {
  const store = tempStore();
  const sent = [];
  let failNext = true;

  const telegram = {
    isEnabled: () => true,
    sendMessage: async (html, chatId) => {
      if (failNext) {
        failNext = false;
        throw new Error('network down');
      }
      sent.push({ html, chatId });
    },
  };

  const source = {
    key: 'linkedin_posts',
    isEnabled: () => true,
    fetch: async () => {
      const error = new BlockedError('Serper refused the request: Not enough credits');
      error.alert = 'serper-refused';
      throw error;
    },
  };

  const pipeline = new Pipeline({ store, telegram, sources: [source] });

  await pipeline.run();
  assert.equal(sent.length, 0, 'the failed send is not recorded');

  await pipeline.run();
  await pipeline.run();
  assert.equal(sent.length, 1, 'then once a day');
  assert.match(sent[0].html, /Not enough credits/);
  assert.equal(sent[0].chatId, config.alerts.chatId);

  // A dry run never alerts.
  store.setState('alert.serper-refused.day', 'yesterday');
  await pipeline.run({ dryRun: true });
  assert.equal(sent.length, 1);
});

test('the pipeline relays a source warning that did not stop it', async () => {
  const store = tempStore();
  const sent = [];
  const telegram = { isEnabled: () => true, sendMessage: async (html) => sent.push(html) };

  const source = {
    key: 'linkedin_posts',
    isEnabled: () => true,
    fetch: async () => [],
    takeAlerts: () => [{ kind: 'serper-low-balance', message: 'Serper has 12 credits left' }],
  };

  await new Pipeline({ store, telegram, sources: [source] }).run();
  assert.equal(sent.length, 1);
  assert.match(sent[0], /12 credits/);
});
