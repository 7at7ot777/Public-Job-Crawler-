import test from 'node:test';
import assert from 'node:assert/strict';
import { LinkedInGuestClient } from '../src/sources/linkedinClient.js';
import { BlockedError } from '../src/errors.js';

const SEARCH = 'https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search';

/** Swaps the global fetch for one test, and always puts it back. */
async function withFetch(handler, run) {
  const original = globalThis.fetch;

  globalThis.fetch = handler;

  try {
    return await run();
  } finally {
    globalThis.fetch = original;
  }
}

const status = (code) => new Response('', { status: code });

test('a throttle slows everything that follows, and a clean stretch gives the pace back', async () => {
  let calls = 0;

  await withFetch(
    async () => {
      calls += 1;
      return calls === 1 ? status(429) : status(200);
    },
    async () => {
      const client = new LinkedInGuestClient({
        delayMs: 1,
        backoffBaseMs: 1,
        maxRetries: 3,
        maxDelayMs: 1_000,
        throttleBudget: 10,
        recoverySuccesses: 2,
      });

      await client.getHtml(SEARCH);

      assert.equal(calls, 2); // the retry happened
      assert.equal(client.throttleEvents, 1);
      assert.equal(client.intervalMs, 2); // doubled from delayMs, not just the retry

      await client.getHtml(SEARCH);

      assert.equal(calls, 3);
      assert.equal(client.intervalMs, 1); // two clean answers relaxed it again
      assert.equal(client.throttleEvents, 0);
    },
  );
});

test('a run that keeps being throttled stops instead of knocking on', async () => {
  let calls = 0;

  await withFetch(
    async () => {
      calls += 1;
      return status(429);
    },
    async () => {
      const client = new LinkedInGuestClient({
        delayMs: 0,
        backoffBaseMs: 1,
        maxRetries: 5,
        throttleBudget: 2,
      });

      await assert.rejects(
        () => client.getHtml(SEARCH),
        (error) => {
          assert.ok(error instanceof BlockedError);
          assert.match(error.message, /throttled 2 times/);
          return true;
        },
      );

      assert.equal(client.throttleEvents, 2);
      assert.equal(calls, 2); // budget spent: no third request is made
    },
  );
});

test('the refusal that ends a request is counted and named like the other throttles', async () => {
  let calls = 0;

  await withFetch(
    async () => {
      calls += 1;
      return status(429);
    },
    async () => {
      const client = new LinkedInGuestClient({
        delayMs: 0,
        backoffBaseMs: 1,
        maxRetries: 3,
        throttleBudget: 10,
      });

      await assert.rejects(
        () => client.getHtml(SEARCH),
        (error) => {
          assert.ok(error instanceof BlockedError);
          assert.match(error.message, /after 3 attempts/);
          return true;
        },
      );

      assert.equal(calls, 3);
      assert.equal(client.throttleEvents, 3); // attempts 1, 2, and the last one
    },
  );
});
