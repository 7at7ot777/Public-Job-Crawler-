import { test } from 'node:test';
import assert from 'node:assert/strict';
import config from '../src/config.js';
import LinkedInPostsSource, { activityDate, extractExternalId } from '../src/sources/linkedinPosts.js';

test('a post keeps its activity-id time over a bare-day engine or page date', async () => {
  // A bare day parses as midnight UTC, which every post used to show as 03:00 Cairo.
  const written = new Date(Date.now() - 2 * 3_600_000);
  written.setUTCMinutes(17, 0, 0);
  const id = (BigInt(written.getTime()) << 22n).toString();
  const url = `https://www.linkedin.com/posts/acme_hiring-activity-${id}-HaYU`;
  const day = written.toISOString().slice(0, 10);

  const search = {
    postJson: async () =>
      JSON.stringify({
        organic: [{ link: url, title: 'Acme on LinkedIn: We are hiring', snippet: 'Hiring', date: day }],
      }),
  };
  const posts = {
    getHtml: async () => `<html><head><meta property="article:published_time" content="${day}"></head></html>`,
  };

  const settings = config.sources.linkedin_posts;
  const saved = { engine: settings.engine, fetchDetails: settings.fetchDetails };
  Object.assign(settings, { engine: 'serper', fetchDetails: true });

  try {
    for (const fetchDetails of [false, true]) {
      settings.fetchDetails = fetchDetails;
      const [item] = await new LinkedInPostsSource(search, posts).fetch({
        keywords: ['Laravel'],
        locations: ['Egypt'],
        pages: 1,
      });

      assert.equal(item.postedAt, written.toISOString(), `fetchDetails: ${fetchDetails}`);
    }
  } finally {
    Object.assign(settings, saved);
  }
});

test('a post date is read off its activity id, in either URL shape', () => {
  // Verified against Serper's own relative dates for these real posts.
  const cases = [
    ['7508099304926986240', '2026-09-22T09:46:08.034Z'],
    ['7501240109950889985', '2026-09-03T11:30:08.514Z'],
    ['7497911764223672320', '2026-08-25T07:04:29.055Z'],
  ];

  for (const [id, iso] of cases) {
    assert.equal(activityDate(`https://www.linkedin.com/posts/acme_hiring-activity-${id}-HaYU`), iso, id);
    assert.equal(activityDate(`https://www.linkedin.com/feed/update/urn:li:activity:${id}`), iso, id);
  }
});

test('a URL with no activity id has no id-date', () => {
  assert.equal(activityDate('https://www.linkedin.com/posts/some-slug-only'), null);
  assert.equal(activityDate(null), null);
  // The same URLs still yield a dedup key from the slug — id-date is separate.
  assert.equal(extractExternalId('https://www.linkedin.com/posts/some-slug-only'), 'some-slug-only');
});
