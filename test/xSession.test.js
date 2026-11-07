import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BlockedError } from '../src/errors.js';
import { parseSearchTimeline } from '../src/sources/xTimelineParser.js';
import { XSessionClient } from '../src/sources/xSessionClient.js';
import { XSessionSource, buildQuery } from '../src/sources/xSession.js';

const tweetEntry = (id, result) => ({
  entryId: `tweet-${id}`,
  content: { itemContent: { tweet_results: { result } } },
});

const tweet = (id, text, user = { screen_name: 'acme_jobs', name: 'Acme' }, { createdAt, ...extra } = {}) => ({
  __typename: 'Tweet',
  rest_id: id,
  core: { user_results: { result: { legacy: user } } },
  legacy: { full_text: text, created_at: createdAt ?? 'Wed Sep 23 10:00:00 +0000 2026' },
  ...extra,
});

// X's own date format, an hour ago — fresh whenever the suite runs, so the 24h
// age cap keeps these rather than dropping them like the fixed-date fixtures.
const recent = new Date(Date.now() - 3_600_000).toUTCString();

const page = (entries, extraInstructions = []) => ({
  data: {
    search_by_raw_query: {
      search_timeline: {
        timeline: {
          instructions: [{ type: 'TimelineAddEntries', entries }, ...extraInstructions],
        },
      },
    },
  },
});

const FIXTURE = page(
  [
    tweetEntry('1', tweet('1', 'We are hiring a Laravel developer &amp; a PHP dev in Cairo')),
    tweetEntry('2', {
      __typename: 'TweetWithVisibilityResults',
      tweet: tweet('2', 'short', { screen_name: 'mona', name: 'Mona' }, {
        note_tweet: { note_tweet_results: { result: { text: 'The full long hiring post' } } },
      }),
    }),
    // 2025 shape: handle and name moved to user.core.
    tweetEntry('3', {
      ...tweet('3', 'Hiring backend devs'),
      core: { user_results: { result: { core: { screen_name: 'newshape', name: 'New' } } } },
    }),
    { entryId: 'cursor-top-1', content: { value: 'TOP' } },
    { entryId: 'cursor-bottom-1', content: { value: 'NEXT' } },
  ],
  [{ type: 'TimelineReplaceEntry', entry: { entryId: 'cursor-bottom-2', content: { value: 'LATER' } } }],
);

test('timeline parses posts, unwraps visibility results, prefers note text', () => {
  const { tweets, cursor } = parseSearchTimeline(FIXTURE);

  assert.equal(tweets.length, 3);
  assert.equal(tweets[0].text, 'We are hiring a Laravel developer & a PHP dev in Cairo');
  assert.equal(tweets[0].handle, 'acme_jobs');
  assert.equal(tweets[0].createdAt, '2026-09-23T10:00:00.000Z');
  assert.equal(tweets[1].text, 'The full long hiring post');
  assert.equal(tweets[1].handle, 'mona');
  assert.equal(tweets[2].handle, 'newshape');
  assert.equal(cursor, 'LATER');
});

test('an empty or malformed timeline is no posts, not a crash', () => {
  assert.deepEqual(parseSearchTimeline({}), { tweets: [], cursor: null });
  assert.deepEqual(parseSearchTimeline(page([])), { tweets: [], cursor: null });
});

test('query gets a since: date from the window', () => {
  const now = new Date('2026-09-24T12:00:00Z');
  assert.equal(buildQuery('"{keyword}" {location}', 'Laravel', 'Egypt', 2, now), '"Laravel" Egypt since:2026-09-22');
  assert.equal(buildQuery('"{keyword}"', 'PHP', 'Egypt', 0, now), '"PHP"');
});

const SETTINGS = {
  authToken: 'token',
  ct0: 'csrf',
  bearer: 'bearer',
  apiBase: 'https://x.com/i/api/graphql',
  searchQueryId: 'QID',
  pageSize: 20,
  maxRequests: 10,
  delayMs: 0,
  features: '',
};

function clientReturning(...responses) {
  const calls = [];
  const client = new XSessionClient(SETTINGS, {
    transactionFactory: async () => async () => 'tid',
    fetch: async (url, init) => {
      calls.push({ url: String(url), init });
      const next = responses.shift();
      return new Response(next.body ?? '', { status: next.status, headers: next.headers ?? {} });
    },
  });

  return { client, calls };
}

test('client signs the request as the web app does', async () => {
  const { client, calls } = clientReturning({ status: 200, body: JSON.stringify(FIXTURE) });
  await client.search('laravel hiring');

  const { url, init } = calls[0];
  assert.match(url, /^https:\/\/x\.com\/i\/api\/graphql\/QID\/SearchTimeline\?/);
  assert.equal(JSON.parse(new URL(url).searchParams.get('variables')).product, 'Latest');
  assert.equal(init.headers['x-csrf-token'], 'csrf');
  assert.equal(init.headers.cookie, 'auth_token=token; ct0=csrf');
  assert.equal(init.headers.authorization, 'Bearer bearer');
  assert.equal(init.headers['x-client-transaction-id'], 'tid');
});

for (const [status, pattern] of [
  [401, /cookie has expired/],
  [403, /cookie has expired/],
  [404, /X_SEARCH_QUERY_ID/],
  [400, /features cannot be null/],
]) {
  test(`HTTP ${status} is a named block, never an empty crawl`, async () => {
    const { client } = clientReturning({ status, body: '{"errors":[{"message":"nope"}]}' });
    await assert.rejects(client.search('q'), (error) => error instanceof BlockedError && pattern.test(error.message));
  });
}

test('429 with a far-off reset stops the run instead of sleeping', async () => {
  const reset = Math.floor(Date.now() / 1000) + 900;
  const { client } = clientReturning({ status: 429, headers: { 'x-rate-limit-reset': String(reset) } });
  await assert.rejects(client.search('q'), (error) => error instanceof BlockedError && /rate-limited/.test(error.message));
});

test('GraphQL errors inside a 200 are a block', async () => {
  const { client } = clientReturning({ status: 200, body: '{"errors":[{"message":"Bad guest token"}]}' });
  await assert.rejects(client.search('q'), (error) => error instanceof BlockedError && /Bad guest token/.test(error.message));
});

test('a login page served with 200 is a block', async () => {
  const { client } = clientReturning({ status: 200, body: '<html>Log in to X</html>' });
  await assert.rejects(client.search('q'), (error) => error instanceof BlockedError && /did not return JSON/.test(error.message));
});

test('request budget is enforced per client', async () => {
  const { client } = clientReturning(
    { status: 200, body: JSON.stringify(page([])) },
    { status: 200, body: JSON.stringify(page([])) },
  );
  client.settings = { ...SETTINGS, maxRequests: 1 };

  await client.search('q');
  await assert.rejects(client.search('q'), /budget spent/);
});

const FRESH = page([
  tweetEntry('1', tweet('1', 'We are hiring a Laravel developer in Cairo', undefined, { createdAt: recent })),
  tweetEntry('2', tweet('2', 'Hiring PHP devs', { screen_name: 'mona', name: 'Mona' }, { createdAt: recent })),
  tweetEntry('3', tweet('3', 'Hiring backend devs', { screen_name: 'z', name: 'Z' }, { createdAt: recent })),
]);

test('source turns a page into post items and hands back partials on a block', async () => {
  let callCount = 0;
  const fakeClient = {
    async search() {
      callCount += 1;
      if (callCount === 1) return FRESH;
      throw new BlockedError('X rejected the session');
    },
  };

  const source = new XSessionSource(fakeClient);

  await assert.rejects(
    source.fetch({ keywords: ['Laravel', 'PHP'], locations: ['Egypt'], pages: 1, timeWindow: 1 }),
    (error) => {
      assert.equal(error.partialItems.length, 3);
      const [first] = error.partialItems;
      assert.equal(first.source, 'x_session');
      assert.equal(first.itemType, 'post');
      assert.equal(first.url, 'https://x.com/acme_jobs/status/1');
      assert.equal(first.company, 'Acme (@acme_jobs)');
      assert.equal(first.keyword, 'Laravel');
      return true;
    },
  );
});

test('a post older than the window is dropped, a fresh one kept', async () => {
  // 1585000000000000000 is a 2022 status id; with no created_at, its age is
  // read from the id and falls outside a one-day window.
  const oldTweet = {
    __typename: 'Tweet',
    rest_id: '1585000000000000000',
    core: { user_results: { result: { legacy: { screen_name: 'stale', name: 'Stale' } } } },
    legacy: { full_text: 'Hiring, but this is an old post' },
  };

  const mixed = page([
    tweetEntry('1', tweet('1', 'Hiring a Laravel dev today', undefined, { createdAt: recent })),
    tweetEntry('1585000000000000000', oldTweet),
  ]);

  const source = new XSessionSource({ async search() { return mixed; } });
  const items = await source.fetch({ keywords: ['Laravel'], locations: ['Egypt'], pages: 1, timeWindow: 1 });

  assert.equal(items.length, 1);
  assert.equal(items[0].externalId, '1');
});
