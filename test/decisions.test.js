import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import config from '../src/config.js';
import { evaluate, firstMatch } from '../src/filters.js';
import { Store } from '../src/store.js';
import Pipeline from '../src/pipeline.js';
import { decisionFilters, formatReport } from '../src/decisions.js';
import { CommandBot, decisionArgs } from '../src/bot.js';

/** Run `body` (sync or async) with config.filters set to exactly `filters`, then put them back. */
function withFilters(filters, body) {
  const saved = { ...config.filters };

  for (const key of Object.keys(config.filters)) delete config.filters[key];
  Object.assign(config.filters, {
    enabled: true,
    tech: [],
    locations: [],
    seniorityExclude: [],
    seniorityInclude: [],
    ...filters,
  });

  const restore = () => {
    for (const key of Object.keys(config.filters)) delete config.filters[key];
    Object.assign(config.filters, saved);
  };

  let result;

  try {
    result = body();
  } catch (error) {
    restore();
    throw error;
  }

  if (result instanceof Promise) return result.finally(restore);

  restore();
  return result;
}

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-decisions-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

// A source key with no per-source filter overrides, so config.filters applies as set.
const SOURCE = 'decision_test';

const job = (id, title, extra = {}) => ({
  source: SOURCE,
  externalId: id,
  itemType: 'job',
  title,
  company: `Acme ${id}`,
  location: 'Cairo, Egypt',
  url: `https://example.com/jobs/${id}`,
  excerpt: 'Laravel and PHP. Email jobs@acme.example',
  ...extra,
});

test('firstMatch names the term that fired', () => {
  assert.equal(firstMatch('senior laravel developer', ['lead', 'senior']), 'senior');
  assert.equal(firstMatch('laravel developer', ['senior']), null);
});

test('a rejection says what triggered it', () => {
  withFilters({ titleExclude: ['wordpress'] }, () => {
    const result = evaluate(job('1', 'WordPress Developer'));
    assert.equal(result.pass, false);
    assert.equal(result.reason, 'title-excluded');
    assert.equal(result.detail, 'matched: wordpress');
  });

  withFilters({ tech: ['golang', 'rust'] }, () => {
    const result = evaluate(job('2', 'Backend Developer'));
    assert.equal(result.reason, 'tech');
    assert.equal(result.detail, 'none of: golang, rust');
  });

  withFilters({ blockedLocations: ['india'] }, () => {
    const result = evaluate(job('3', 'Backend Developer', { location: 'Pune, India' }));
    assert.equal(result.reason, 'location-blocked');
    assert.equal(result.detail, 'matched: india');
  });

  withFilters({}, () => {
    const result = evaluate(job('4', 'Backend Developer'));
    assert.equal(result.pass, true);
    assert.equal(result.detail, null);
  });
});

test('insertIfNew says what an item duplicates', () => {
  const store = tempStore();

  assert.equal(store.insertIfNew(job('10', 'Laravel Developer')).created, true);

  const again = store.insertIfNew(job('10', 'Laravel Developer'));
  assert.equal(again.created, false);
  assert.equal(again.duplicate.kind, 'same-id');

  // Same company and title from another board: caught by the fingerprint.
  const elsewhere = store.insertIfNew({
    ...job('10', 'Laravel Developer'),
    source: 'wuzzuf',
    externalId: 'w1',
  });
  assert.equal(elsewhere.created, false);
  assert.equal(elsewhere.duplicate.kind, 'fingerprint');
  assert.match(elsewhere.duplicate.duplicateOf, /^decision_test: /);
});

test('decisions are recorded, queried, summarised and pruned', () => {
  const store = tempStore();

  store.recordDecisions([
    {
      runId: 'r1',
      source: 'wuzzuf',
      decision: 'rejected',
      reason: 'title',
      detail: 'x',
      item: job('1', 'QA Engineer'),
    },
    {
      runId: 'r1',
      source: 'wuzzuf',
      decision: 'rejected',
      reason: 'title',
      item: job('2', 'QA Engineer'),
    },
    {
      runId: 'r1',
      source: 'wuzzuf',
      decision: 'rejected',
      reason: 'tech',
      item: job('3', 'Go Developer'),
    },
    { runId: 'r1', source: 'wuzzuf', decision: 'accepted', item: job('4', 'Laravel Developer') },
  ]);

  assert.equal(store.queryDecisions({ decision: 'rejected' }).length, 3);
  assert.equal(store.queryDecisions({ reason: 'tech' })[0].title, 'Go Developer');
  assert.equal(store.queryDecisions({ source: 'tanqeeb' }).length, 0);

  const summary = store.decisionSummary({});
  assert.deepEqual({ ...summary.byReason[0] }, { decision: 'rejected', reason: 'title', count: 2 });
  assert.deepEqual(summary.topRejectedTitles.title, [{ title: 'QA Engineer', count: 2 }]);

  const report = formatReport(store, decisionFilters({ decision: 'rejected', since: '24' }));
  assert.match(report, /2× rejected: title/);
  assert.match(report, /QA Engineer \(2\)/);

  // Nothing is old enough yet; backdate one row and prune.
  assert.equal(store.pruneDecisions(14), 0);
  store.db
    .prepare("UPDATE decisions SET created_at = '2000-01-01T00:00:00.000Z' WHERE id = 1")
    .run();
  assert.equal(store.pruneDecisions(14), 1);
});

test('a run records rejected, accepted and duplicate items with its run id', async () => {
  const store = tempStore();
  const items = [
    job('1', 'WordPress Developer'),
    job('2', 'Laravel Developer'),
    job('2', 'Laravel Developer'),
  ];
  const source = {
    key: SOURCE,
    isEnabled: () => true,
    fetch: async () => items.map((item) => ({ ...item })),
  };
  const pipeline = new Pipeline({ store, telegram: { isEnabled: () => false }, sources: [source] });

  await withFilters({ titleExclude: ['wordpress'] }, () =>
    pipeline.run({ noSend: true, runId: 'run-1' }),
  );

  const rows = store.queryDecisions({ runId: 'run-1', limit: 10 });
  const byDecision = Object.groupBy(rows, (row) => row.decision);

  assert.equal(byDecision.rejected.length, 1);
  assert.equal(byDecision.rejected[0].detail, 'matched: wordpress');
  assert.equal(byDecision.accepted.length, 1);
  assert.match(byDecision.accepted[0].detail, /jobs@acme\.example/);
  assert.equal(byDecision.duplicate.length, 1);
  assert.equal(byDecision.duplicate[0].reason, 'same-id');
});

test('a dry run records no decisions', async () => {
  const store = tempStore();
  const source = {
    key: SOURCE,
    isEnabled: () => true,
    fetch: async () => [job('1', 'WordPress Developer')],
  };
  const pipeline = new Pipeline({ store, telegram: { isEnabled: () => false }, sources: [source] });

  await withFilters({ titleExclude: ['wordpress'] }, () => pipeline.run({ dryRun: true }));

  assert.equal(store.queryDecisions({}).length, 0);
});

test('/rejected arguments become decision filters', () => {
  assert.deepEqual(decisionArgs(['title', '48h'], ['wuzzuf']), {
    decision: 'rejected',
    since: '48',
    reason: 'title',
  });
  assert.deepEqual(decisionArgs(['duplicate', 'wuzzuf'], ['wuzzuf']), {
    decision: 'duplicate',
    since: '24',
    source: 'wuzzuf',
  });
  assert.equal(decisionFilters({ decision: 'all' }).decision, null);
});

test('/rejected replies with the report, and only to allowed users', async () => {
  const logged = [];
  const bot = new CommandBot({
    settings: {},
    allowedIds: [42],
    controlDir: fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-bot-')),
    log: { log: (line) => logged.push(line), error() {} },
    decisions: async (args) => `report for ${args.join(' ')}`,
  });
  const message = (userId) => ({ text: '/rejected title', from: { id: userId }, chat: { id: 7 } });

  assert.equal(await bot.handle(message(42)), 'report for title');
  assert.match(await bot.handle(message(99)), /Not allowed/);
  assert.match(logged[0], /\/rejected title from user 42 in chat 7/);
  assert.match(logged[1], /not allowed/);
});
