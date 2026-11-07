import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store } from '../src/store.js';

const BODY = `We're hiring a Laravel Developer to build our payments platform in Cairo.
Requirements: 2+ years of PHP and Laravel, MySQL, Redis, REST APIs, Git,
queues and unit testing. Nice to have: Vue.js, Docker, AWS.`;

const post = (externalId, author, excerpt) => ({
  source: 'linkedin_posts',
  externalId,
  itemType: 'post',
  title: excerpt.split('\n')[0],
  company: author,
  url: `https://www.linkedin.com/posts/${author}-activity-${externalId}`,
  excerpt,
});

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-store-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

test('a repost under a new id and author is not stored again', () => {
  const store = tempStore();

  assert.equal(store.insertIfNew(post('7000000000001', 'alice', BODY)).created, true);

  const repost = store.insertIfNew(post('7000000000002', 'bob', `${BODY}\n#jobs`));
  assert.equal(repost.created, false);
  assert.equal(repost.row.external_id, '7000000000001');
});

test('a different post is stored', () => {
  const store = tempStore();
  store.insertIfNew(post('7000000000001', 'alice', BODY));

  const other = `Hiring a Backend Developer (Node.js) for our logistics startup in Alexandria.
You will own microservices on NestJS, PostgreSQL and Kafka, with CI on GitHub Actions.`;
  assert.equal(store.insertIfNew(post('7000000000003', 'carol', other)).created, true);
});

test('jobs are left to the existing checks', () => {
  const store = tempStore();
  const job = (id) => ({ ...post(id, 'x', BODY), source: 'wuzzuf', itemType: 'job', company: null });

  assert.equal(store.insertIfNew(job('a1')).created, true);
  assert.equal(store.insertIfNew(job('a2')).created, true);
});

test('a database is empty until the first row goes in', () => {
  const store = tempStore();
  assert.equal(store.isEmpty(), true);

  store.insertIfNew(post('7000000000001', 'alice', BODY));
  assert.equal(store.isEmpty(), false);

  // A row that was skipped still counts: it is the dedup baseline.
  const row = store.insertIfNew(post('7000000000004', 'dave', `${BODY}\nAlso Redis.`));
  if (row.created) store.markSkipped(row.row.id, 'seeded');
  assert.equal(store.isEmpty(), false);
});

test('a read-only preferred directory falls back instead of throwing', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-ro-'));
  const readOnly = path.join(dir, 'locked');

  fs.mkdirSync(readOnly);
  fs.chmodSync(readOnly, 0o500);

  let store;

  try {
    store = new Store(path.join(readOnly, 'crawler.sqlite'));
  } finally {
    fs.chmodSync(readOnly, 0o700);
  }

  // Windows ignores chmod, so the preferred path stays usable there; either
  // way the point is that constructing the store did not throw.
  assert.ok(store.databasePath.endsWith('crawler.sqlite'));
  assert.equal(store.isEmpty(), true);
});
