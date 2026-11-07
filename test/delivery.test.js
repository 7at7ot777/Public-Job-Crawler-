import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import config from '../src/config.js';
import Pipeline from '../src/pipeline.js';
import { Store, STATUS } from '../src/store.js';
import { parseTopic, resolveTopic } from '../src/topics.js';
import { digestDue, renderDigest, splitForDigest } from '../src/digest.js';
import { nextStreak, silent } from '../src/breakage.js';
import { slot, weeklyDue, renderWeekly } from '../src/weekly.js';
import { formatMessage } from '../src/formatter.js';
import { REGION } from '../src/region.js';

// Ported from the Android app's TopicsTest, MonitoringTest and the digest/abroad delivery rules.

test('topics: every way .env can name one', () => {
  const main = '7000000000';
  assert.deepEqual(parseTopic('https://t.me/c/1234567890/3', main), { chatId: '-1001234567890', threadId: 3 });
  assert.deepEqual(parseTopic('t.me/c/1234567890/3/42', main), { chatId: '-1001234567890', threadId: 3 });
  assert.deepEqual(parseTopic('https://t.me/mygroup/7', main), { chatId: '@mygroup', threadId: 7 });
  assert.deepEqual(parseTopic('-1001234567890:5', main), { chatId: '-1001234567890', threadId: 5 });
  assert.deepEqual(parseTopic('-1001234567890', main), { chatId: '-1001234567890', threadId: null });
  assert.deepEqual(parseTopic(' 9 ', main), { chatId: main, threadId: 9 });
  assert.deepEqual(parseTopic('-100123:1', main), { chatId: '-100123', threadId: null });
  assert.equal(parseTopic('not a topic', main), null);
  assert.equal(parseTopic('https://t.me/c/abc/3', main), null);
  assert.equal(parseTopic('', main), null);
  assert.deepEqual(resolveTopic('???', main), { chatId: main, threadId: null });
});

test('digest: split, due, render', () => {
  const rows = [
    { id: 1, score: 90, title: 'Laravel', url: 'https://a.test/1', source: 'wuzzuf', region: 'EGYPT' },
    { id: 2, score: 40, title: 'PHP', url: 'https://a.test/2', source: 'remote_feeds', region: 'WORLDWIDE' },
    { id: 3, score: 60, title: 'Node', url: 'https://a.test/3', source: 'wuzzuf', region: 'EGYPT' },
  ];
  const [now, later] = splitForDigest(rows, true, 70);
  assert.deepEqual(now.map((r) => r.id), [1]);
  assert.deepEqual(later.map((r) => r.id), [2, 3]);
  assert.deepEqual(splitForDigest(rows, false, 70)[1], []);

  assert.ok(digestDue(10, null, 1, true, 5));
  assert.ok(!digestDue(10, 8, 1, true, 5));
  assert.ok(digestDue(10, 8, 1, false, 5));
  assert.ok(!digestDue(10, null, 0, true, 5));

  const { text, ids } = renderDigest(later);
  assert.deepEqual(ids, [3, 2]);
  assert.ok(text.indexOf('Egypt') < text.indexOf('Worldwide'));
});

test('breakage: a streak grows only on clean silent runs of a source that used to yield', () => {
  assert.ok(silent(0, null, false, null));
  assert.ok(!silent(0, 12, false, null));
  assert.equal(nextStreak(2, 0, false, null, true), 3);
  assert.equal(nextStreak(2, 0, false, null, false), 0);
  assert.equal(nextStreak(2, 5, false, null, true), 0);
  assert.equal(nextStreak(2, 0, true, 'blocked', true), null);
  assert.equal(nextStreak(2, 0, false, null, true, 30), 0);
});

test('weekly: the Friday slot, and when it is due', () => {
  const zone = 'Africa/Cairo';
  // Saturday 7 Nov 2026, noon Cairo: the slot is Friday the 6th.
  assert.equal(slot(new Date('2026-11-07T10:00:00Z'), zone), '2026-11-06');
  // Friday before 10:00: still last week's.
  assert.equal(slot(new Date('2026-11-06T06:00:00Z'), zone), '2026-10-30');
  assert.ok(weeklyDue(new Date('2026-11-07T10:00:00Z'), '2026-10-30', zone));
  assert.ok(!weeklyDue(new Date('2026-11-07T10:00:00Z'), '2026-11-06', zone));
  // Three days late: skipped.
  assert.ok(!weeklyDue(new Date('2026-11-09T12:00:00Z'), '2026-10-30', zone));

  const text = renderWeekly({
    from: '2026-10-30', to: '2026-11-05',
    facts: [{ source: 'wuzzuf', region: REGION.EGYPT, status: STATUS.SENT, score: 80 }],
    top: [{ score: 80, title: 'Laravel Developer', url: 'https://a.test/1', company: 'Acme' }],
    runs: [{ rejected: { title: 4, tech: 1 } }],
    serperWeek: 0, quiet: { tanqeeb: 3 },
  });
  assert.match(text, /1 new · 1 sent/);
  assert.match(text, /Dropped most for:<\/b> title 4/);
  assert.match(text, /Gone quiet/);
});

test('formatter: score, years, salary, CV fit, Gulf flag and the region tag', () => {
  const egypt = formatMessage({
    source: 'wuzzuf', title: 'Laravel Developer', url: 'https://wuzzuf.net/jobs/p/1', company: 'Acme',
    market: 'Egypt', score: 82, yearsMin: 2, yearsMax: 4, salary: 'EGP 18k/mo',
  });
  assert.match(egypt, /⭐ 82\/100/);
  assert.match(egypt, /🧭 2–4 yrs/);
  assert.match(egypt, /💰 EGP 18k\/mo/);
  assert.match(egypt, /#Egypt$/m);
  assert.doesNotMatch(egypt, /CV fit/);

  const riyadh = formatMessage({ source: 'workable', title: 'PHP Developer', url: 'https://x.test', market: 'Saudi Arabia' });
  assert.match(riyadh, /🇸🇦 Saudi Arabia/);
  assert.match(riyadh, /#Gulf/);

  const berlin = formatMessage({
    source: 'relocateme', title: 'Laravel Developer', url: 'https://relocate.me/x', location: 'Berlin, Germany',
    market: '✈️ Visa sponsorship', excerpt: 'PHP Laravel. Visa sponsorship.',
  });
  assert.match(berlin, /🎯 CV fit \d+\/100 · 🇪🇺 Europe/);
  assert.match(berlin, /#Worldwide/);
});

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-delivery-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

function fakeTelegram() {
  return {
    sent: [],
    isEnabled: () => true,
    async sendMessage(text, to) {
      this.sent.push({ text, to });
      return true;
    },
  };
}

test('pipeline: abroad sends only the best fits, digest holds the weaker ones', async () => {
  const pinned = { filters: { ...config.filters }, ranking: { ...config.ranking } };
  Object.assign(config.filters, { enabled: false });
  Object.assign(config.ranking, { digest: true, digestThreshold: 50 });

  try {
    const store = tempStore();
    // Seeded already: this is the run after.
    store.setState('relocateme.seeded', '1');
    store.setState('wuzzuf.seeded', '1');
    store.setState('weekly.lastSent', slot(new Date(), config.schedule.timezone));

    const telegram = fakeTelegram();
    const good = {
      source: 'relocateme', externalId: 'g', itemType: 'job', title: 'Laravel Developer', url: 'https://relocate.me/g',
      company: 'Good', location: 'Berlin, Germany', market: '✈️ Visa sponsorship',
      excerpt: 'PHP, Laravel, MySQL, Redis, Docker. 2+ years of experience. Visa sponsorship and relocation package.',
      postedAt: new Date().toISOString(),
    };
    const weak = { ...good, externalId: 'w', company: 'Weak', title: 'Software Engineer', excerpt: 'Visa.' };
    const local = {
      source: 'wuzzuf', externalId: 'l', itemType: 'job', title: 'Cashier', url: 'https://wuzzuf.net/l',
      company: 'Shop', location: 'Cairo, Egypt', market: 'Egypt',
    };

    const pipeline = new Pipeline({
      store,
      telegram,
      sources: [
        { key: 'relocateme', isEnabled: () => true, fetch: async () => [good, weak] },
        { key: 'wuzzuf', isEnabled: () => true, fetch: async () => [local] },
      ],
    });
    const [abroad, egypt] = await pipeline.run();

    assert.equal(abroad.delivered, 1);
    assert.match(telegram.sent[0].text, /Good/);
    assert.equal(store.findByExternalId('relocateme', 'w').status, STATUS.SKIPPED);
    assert.match(store.findByExternalId('relocateme', 'w').last_error, /lower fit/);

    // The cashier scores low: held for the digest, which goes out at the end of the run.
    assert.equal(egypt.digested, 1);
    assert.match(telegram.sent.at(-1).text, /Digest/);
    assert.equal(store.findByExternalId('wuzzuf', 'l').status, STATUS.SENT);
    assert.equal(store.runsSince(new Date(0)).length, 2);
  } finally {
    Object.assign(config.filters, pinned.filters);
    Object.assign(config.ranking, pinned.ranking);
  }
});

test('pipeline: a new source seeds on its own, the others keep sending', async () => {
  const pinned = { ...config.filters };
  Object.assign(config.filters, { enabled: false });

  try {
    const store = tempStore();
    store.setState('weekly.lastSent', slot(new Date(), config.schedule.timezone));
    const telegram = fakeTelegram();
    const item = (source, id) => ({
      source, externalId: id, itemType: 'job', title: 'Backend Developer', url: `https://x.test/${source}/${id}`,
      company: `${source} ${id}`, location: 'Cairo, Egypt', market: 'Egypt',
    });

    await new Pipeline({ store, telegram, sources: [{ key: 'wuzzuf', isEnabled: () => true, fetch: async () => [item('wuzzuf', '1')] }] }).run();
    assert.equal(telegram.sent.length, 0);

    const [old, fresh] = await new Pipeline({
      store,
      telegram,
      sources: [
        { key: 'wuzzuf', isEnabled: () => true, fetch: async () => [item('wuzzuf', '2')] },
        { key: 'tanqeeb', isEnabled: () => true, fetch: async () => [item('tanqeeb', '1')] },
      ],
    }).run();

    assert.equal(old.delivered, 1);
    assert.equal(fresh.seeded, 1);
    assert.equal(fresh.delivered, 0);
  } finally {
    Object.assign(config.filters, pinned);
  }
});
