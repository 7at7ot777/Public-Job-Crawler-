import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import config from '../src/config.js';
import Pipeline from '../src/pipeline.js';
import { Store, STATUS } from '../src/store.js';

/*
 * The filters are pinned open: these tests are about what happens to an item
 * after it is kept, not about whether .env would have kept it.
 */
Object.assign(config.filters, {
  enabled: false,
  titleTerms: [],
  tech: [],
  locations: [],
  blockedLocations: [],
  seniorityExclude: [],
  seniorityInclude: [],
});

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-pipeline-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

const job = (id) => ({
  source: 'linkedin_jobs',
  externalId: id,
  itemType: 'job',
  title: 'Backend Developer',
  company: `Acme ${id}`,
  location: 'Cairo, Egypt',
  url: `https://www.linkedin.com/jobs/view/${id}`,
});

/** A source that hands back a fixed list. */
const fakeSource = (items) => ({
  key: 'linkedin_jobs',
  isEnabled: () => true,
  fetch: async () => items,
});

/** A Telegram client that records instead of sending. */
function fakeTelegram() {
  return {
    sent: [],
    isEnabled: () => true,
    async sendMessage(text) {
      this.sent.push(text);
      return { ok: true };
    },
  };
}

test('an empty database is seeded, not sent', async () => {
  const store = tempStore();
  const telegram = fakeTelegram();

  const pipeline = new Pipeline({
    store,
    telegram,
    sources: [fakeSource([job('1'), job('2')])],
  });

  const [stat] = await pipeline.run();

  assert.equal(stat.stored, 2);
  assert.equal(stat.seeded, 2);
  assert.equal(stat.delivered, 0);
  assert.equal(telegram.sent.length, 0);

  // Stored as the dedup baseline, and marked so it is clear why nothing went out.
  // node:sqlite hands back null-prototype rows, so compare the fields.
  const statuses = store.stats().map(({ status, count }) => [status, count]);
  assert.deepEqual(statuses, [[STATUS.SKIPPED, 2]]);
});

test('the pass after a seeding pass delivers normally', async () => {
  const store = tempStore();
  const telegram = fakeTelegram();

  const first = new Pipeline({ store, telegram, sources: [fakeSource([job('1')])] });
  await first.run();

  const second = new Pipeline({ store, telegram, sources: [fakeSource([job('1'), job('2')])] });
  const [stat] = await second.run();

  // Job 1 was already known, so only the new one is stored and sent.
  assert.equal(stat.stored, 1);
  assert.equal(stat.seeded, 0);
  assert.equal(stat.delivered, 1);
  assert.equal(telegram.sent.length, 1);
  assert.match(telegram.sent[0], /Acme 2/);
});

test('seeding is skipped when the operator asked to send on an empty database', async () => {
  const store = tempStore();
  const telegram = fakeTelegram();
  const pinned = config.limits.seedFreshDatabase;

  config.limits.seedFreshDatabase = false;

  try {
    const pipeline = new Pipeline({ store, telegram, sources: [fakeSource([job('1')])] });
    const [stat] = await pipeline.run();

    assert.equal(stat.seeded, 0);
    assert.equal(stat.delivered, 1);
  } finally {
    config.limits.seedFreshDatabase = pinned;
  }
});

test('a dry run neither seeds nor stores', async () => {
  const store = tempStore();
  const telegram = fakeTelegram();

  const pipeline = new Pipeline({ store, telegram, sources: [fakeSource([job('1')])] });
  const [stat] = await pipeline.run({ dryRun: true });

  assert.equal(stat.matched, 1);
  assert.equal(stat.stored, 0);
  assert.equal(stat.seeded, 0);
  assert.equal(telegram.sent.length, 0);
  assert.equal(store.isEmpty(), true);
});
