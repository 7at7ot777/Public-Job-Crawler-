import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseStatusUrl, statusUrl, snowflakeDate, olderThanDays } from '../src/sources/xShared.js';
import { splitTitle, toItem } from '../src/sources/xPosts.js';

test('status permalinks on every X host resolve to handle and id', () => {
  const expected = { handle: 'acme_jobs', id: '1838912345678901234' };

  for (const url of [
    'https://x.com/acme_jobs/status/1838912345678901234',
    'https://twitter.com/acme_jobs/status/1838912345678901234',
    'https://mobile.twitter.com/acme_jobs/status/1838912345678901234',
    'https://www.x.com/acme_jobs/status/1838912345678901234/photo/1',
    'https://x.com/acme_jobs/status/1838912345678901234?s=20',
  ]) {
    assert.deepEqual(parseStatusUrl(url), expected, url);
  }
});

test('profiles, searches and hashtags are not posts', () => {
  for (const url of [
    'https://x.com/acme_jobs',
    'https://x.com/search?q=laravel',
    'https://x.com/hashtag/hiring',
    'https://x.com/i/status/1838912345678901234',
    'https://www.linkedin.com/posts/foo-activity-123',
    null,
  ]) {
    assert.equal(parseStatusUrl(url), null, String(url));
  }
});

test('a status id carries the time it was written', () => {
  // 1585000000000000000 is 2022-10-25T20:07:02.532Z by Twitter's snowflake epoch.
  assert.equal(snowflakeDate('1585000000000000000'), '2022-10-25T20:07:02.532Z');
  assert.equal(snowflakeDate('12345'), null);
  assert.equal(snowflakeDate(undefined), null);
});

test('canonical url is on x.com', () => {
  assert.equal(statusUrl('acme', '1'), 'https://x.com/acme/status/1');
});

test('a post is too old only when its own date says so', () => {
  const hoursAgo = (h) => new Date(Date.now() - h * 3_600_000).toISOString();

  assert.equal(olderThanDays(hoursAgo(2), 1), false);
  assert.equal(olderThanDays(hoursAgo(30), 1), true);
  // No date, or no cap: never too old — absence of a date is not evidence.
  assert.equal(olderThanDays(null, 1), false);
  assert.equal(olderThanDays(hoursAgo(1000), 0), false);
  assert.equal(olderThanDays('not a date', 1), false);
});

test('Google titles split into author and post text', () => {
  assert.deepEqual(splitTitle('Acme Careers on X: "We are hiring a Laravel dev in Cairo" / X'), {
    name: 'Acme Careers',
    text: 'We are hiring a Laravel dev in Cairo',
  });
  assert.deepEqual(splitTitle('Mona (@mona_dev) on X: "Hiring PHP devs…" / X'), {
    name: 'Mona',
    text: 'Hiring PHP devs…',
  });
  assert.deepEqual(splitTitle('Povilas Korop | Laravel Educator on X'), {
    name: 'Povilas Korop | Laravel Educator',
    text: null,
  });
  assert.deepEqual(splitTitle('Just some page title'), { name: null, text: 'Just some page title' });
});

test('a search result becomes a post item keyed by status id', () => {
  const item = toItem(
    {
      url: 'https://twitter.com/acme_jobs/status/1585000000000000000?s=20',
      title: 'Acme on X: "We are hiring a Laravel developer" / X',
      snippet: 'We are hiring a Laravel developer in Cairo. Send your CV to jobs@acme.io',
      postedAt: null,
    },
    'Laravel',
    'Egypt',
  );

  assert.equal(item.source, 'x_posts');
  assert.equal(item.itemType, 'post');
  assert.equal(item.externalId, '1585000000000000000');
  assert.equal(item.url, 'https://x.com/acme_jobs/status/1585000000000000000');
  assert.equal(item.company, 'Acme (@acme_jobs)');
  assert.equal(item.title, 'We are hiring a Laravel developer');
  assert.equal(item.postedAt, '2022-10-25T20:07:02.532Z');
  assert.equal(item.market, undefined);

  assert.equal(toItem({ url: 'https://x.com/acme_jobs', title: 'Acme' }, 'Laravel', 'Egypt'), null);
});
