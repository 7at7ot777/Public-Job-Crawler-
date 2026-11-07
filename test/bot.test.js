import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  CommandBot,
  parseCommand,
  parseHours,
  resolveSources,
  takeResults,
} from '../src/bot.js';

const tempDir = () => fs.mkdtempSync(path.join(os.tmpdir(), 'bot-test-'));

const botFor = (controlDir, allowedIds = ['42']) =>
  new CommandBot({ settings: {}, allowedIds, controlDir, log: { log() {}, error() {} } });

const message = (text, userId = 42) => ({ text, from: { id: userId }, chat: { id: 1001 } });

test('commands parse with or without a bot mention', () => {
  assert.deepEqual(parseCommand('/run linkedin_posts  ats'), {
    command: 'run',
    args: ['linkedin_posts', 'ats'],
  });
  assert.deepEqual(parseCommand('/Posts@JobsBot'), { command: 'posts', args: [] });
  assert.equal(parseCommand('hello'), null);
  assert.equal(parseCommand(''), null);
});

test('sources resolve from aliases and reject unknown keys', () => {
  const known = ['linkedin_posts', 'linkedin_jobs', 'ats'];

  assert.deepEqual(resolveSources({ command: 'posts', args: [] }, known), {
    sources: ['linkedin_posts'],
  });
  assert.deepEqual(resolveSources({ command: 'run', args: ['jobs', 'ATS', 'ats'] }, known), {
    sources: ['linkedin_jobs', 'ats'],
  });
  assert.match(resolveSources({ command: 'run', args: ['nope'] }, known).error, /Unknown source: nope/);
  assert.match(resolveSources({ command: 'run', args: [] }, known).error, /Which source/);
});

test('a stranger is told their id and queues nothing', () => {
  const dir = tempDir();
  const reply = botFor(dir).handle(message('/posts', 7));

  assert.match(reply, /Your user id is 7/);
  assert.equal(fs.existsSync(path.join(dir, 'queue')), false);
});

test('an allowed user queues the source once per chat request', () => {
  const dir = tempDir();
  const bot = botFor(dir);

  assert.match(bot.handle(message('/posts')), /linkedin_posts: queued/);
  assert.match(bot.handle(message('/run linkedin_posts')), /already queued/);
  assert.equal(fs.readFileSync(path.join(dir, 'queue', 'linkedin_posts'), 'utf8'), '1001\n1001\n');
  assert.match(bot.handle(message('/status')), /Queued: linkedin_posts/);
});

test('finished requests are read once, skipping half-written ones', () => {
  const dir = tempDir();
  fs.mkdirSync(path.join(dir, 'done'));
  fs.writeFileSync(path.join(dir, 'done', 'linkedin_posts-1'), '0 93\nlinkedin_posts\n1001\n1001\n55\n');
  fs.writeFileSync(path.join(dir, 'done', '.ats'), '0 1\nats\n1001\n');

  assert.deepEqual(takeResults(dir), [
    { source: 'linkedin_posts', status: 0, seconds: 93, chatIds: ['1001', '55'] },
  ]);
  assert.deepEqual(takeResults(dir), []);
});

const hoursFile = (dir) => path.join(dir, 'hours', 'linkedin_jobs');
const queued = (dir, source = 'linkedin_jobs') =>
  fs.readFileSync(path.join(dir, 'queue', source), 'utf8');
const tap = (data, userId = 42) => ({
  id: 'q1',
  data,
  from: { id: userId },
  message: { message_id: 5, chat: { id: 1001 } },
});

test('hours are whole numbers from 1 to 168', () => {
  assert.deepEqual(parseHours('10'), { hours: 10 });
  assert.deepEqual(parseHours(' 24h '), { hours: 24 });
  assert.deepEqual(parseHours('168'), { hours: 168 });
  for (const bad of ['0', '169', 'abc', '2.5', '-3', '']) {
    assert.ok(parseHours(bad).error, `${bad} should be rejected`);
  }
});

test('hours in a command apply to LinkedIn Jobs only', () => {
  const known = ['linkedin_posts', 'linkedin_jobs', 'wuzzuf'];

  assert.deepEqual(resolveSources({ command: 'jobs', args: ['10'] }, known), {
    sources: ['linkedin_jobs'],
    hours: 10,
  });
  assert.deepEqual(resolveSources({ command: 'run', args: ['linkedin_jobs', '24'] }, known), {
    sources: ['linkedin_jobs'],
    hours: 24,
  });
  assert.match(resolveSources({ command: 'run', args: ['wuzzuf', '10'] }, known).error, /LinkedIn Jobs/);
  assert.match(resolveSources({ command: 'posts', args: ['10'] }, known).error, /LinkedIn Jobs/);
  assert.match(resolveSources({ command: 'jobs', args: ['500'] }, known).error, /1 to 168/);
});

test('/jobs alone offers quick buttons and queues nothing yet', () => {
  const dir = tempDir();
  const reply = botFor(dir).handle(message('/jobs'));

  assert.match(reply.text, /how many hours back/i);
  const data = reply.markup.inline_keyboard.flat().map((button) => button.callback_data);
  assert.deepEqual(data, ['jobs:1', 'jobs:6', 'jobs:10', 'jobs:24', 'jobs:48', 'jobs:168', 'jobs:default']);
  assert.equal(fs.existsSync(path.join(dir, 'queue')), false);
});

test('/jobs 10 queues the run with its window', () => {
  const dir = tempDir();

  assert.match(botFor(dir).handle(message('/jobs 10')), /linkedin_jobs: queued \(last 10h\)/);
  assert.equal(queued(dir), '1001\n');
  assert.equal(fs.readFileSync(hoursFile(dir), 'utf8'), '10\n');
});

test('two waiting requests keep the wider window', () => {
  const dir = tempDir();
  const bot = botFor(dir);

  bot.handle(message('/jobs 24'));
  assert.match(bot.handle(message('/jobs 10')), /already queued \(last 24h\)/);
  bot.handle(message('/jobs 48'));
  assert.equal(fs.readFileSync(hoursFile(dir), 'utf8'), '48\n');
});

test('a typed number answers an open prompt, and only then', () => {
  const dir = tempDir();
  const bot = botFor(dir);

  assert.equal(bot.handle(message('24')), null);

  bot.handle(message('/jobs'));
  assert.match(bot.handle(message('500')), /1 to 168/);
  assert.match(bot.handle(message('24')), /queued \(last 24h\)/);
  assert.equal(fs.readFileSync(hoursFile(dir), 'utf8'), '24\n');

  // Answered: a second number is just a message.
  assert.equal(bot.handle(message('6')), null);
});

test('an expired prompt ignores a typed number', () => {
  const dir = tempDir();
  const bot = botFor(dir);
  let now = 0;
  bot.now = () => now;

  bot.handle(message('/jobs'));
  now = 6 * 60_000;
  assert.equal(bot.handle(message('10')), null);
  assert.equal(fs.existsSync(path.join(dir, 'queue')), false);
});

test('a stranger typing a number queues nothing', () => {
  const dir = tempDir();
  const bot = botFor(dir);

  bot.handle(message('/jobs'));
  assert.equal(bot.handle(message('10', 7)), null);
  assert.equal(fs.existsSync(path.join(dir, 'queue')), false);
});

test('a button tap queues the run with that window, or the default', () => {
  const dir = tempDir();
  const bot = botFor(dir);

  assert.match(bot.handleCallback(tap('jobs:48')), /queued \(last 48h\)/);
  assert.equal(fs.readFileSync(hoursFile(dir), 'utf8'), '48\n');

  const other = tempDir();
  assert.match(botFor(other).handleCallback(tap('jobs:default')), /linkedin_jobs: queued$/m);
  assert.equal(queued(other), '1001\n');
  assert.equal(fs.existsSync(hoursFile(other)), false);
});

test('a stranger\'s or a forged button tap queues nothing', () => {
  const dir = tempDir();
  const bot = botFor(dir);

  assert.match(bot.handleCallback(tap('jobs:10', 7)), /Your user id is 7/);
  assert.match(bot.handleCallback(tap('jobs:999')), /1 to 168/);
  assert.match(bot.handleCallback(tap('nope')), /expired/);
  assert.equal(fs.existsSync(path.join(dir, 'queue')), false);
});

test('a finished request reports its window, and old summaries still read', () => {
  const dir = tempDir();
  fs.mkdirSync(path.join(dir, 'done'));
  fs.writeFileSync(path.join(dir, 'done', 'linkedin_jobs-1'), '0 42 10\nlinkedin_jobs\n1001\n');
  fs.writeFileSync(path.join(dir, 'done', 'ats-2'), '1 3\nats\n1001\n');

  const results = takeResults(dir).sort((a, b) => a.source.localeCompare(b.source));
  assert.deepEqual(results, [
    { source: 'ats', status: 1, seconds: 3, chatIds: ['1001'] },
    { source: 'linkedin_jobs', status: 0, seconds: 42, hours: 10, chatIds: ['1001'] },
  ]);
});
