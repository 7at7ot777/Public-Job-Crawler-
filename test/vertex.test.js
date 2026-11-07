import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store } from '../src/store.js';
import { VertexSource } from '../src/sources/vertex.js';
import { parseSitemap, parseVacancyPage, extractExternalId } from '../src/sources/vertexParser.js';
import config from '../src/config.js';

const SITEMAP = 'https://vertextech-eg.com/sitemap.xml';
const BASE = 'https://vertextech-eg.com/vacancies';

/** The shape of a live vacancy page, trimmed to the parts the parser reads. */
const page = (title, { level = 'Middle', stack = 'Node.js', place = 'Cairo, Smart Village' } = {}) => `<!doctype html>
<html><head>
<title>${title} - VerteX Technologies</title>
<meta name="description" content="We're looking for ${title} job position in ${place}. Apply for an open vacancy at VerteX Technologies!">
</head><body>
<div class="framer-x" data-framer-name="Vacancy name" data-framer-component-type="RichTextContainer"><h1 class="framer-text">${title}</h1></div>
<div class="framer-y"><div data-framer-component-type="RichTextContainer"><p class="framer-text">${level}</p></div>
<div data-framer-name="Title" data-framer-component-type="RichTextContainer"><p class="framer-text">${stack}</p></div></div>
<div class="framer-z" data-framer-name="Vacancy description" data-framer-component-type="RichTextContainer"><p dir="auto" class="framer-text">We are an Egyptian technology company. <strong class="framer-text">Our office in Smart Village</strong>.</p><h3 class="framer-text"><strong>What we expect from you:</strong></h3><ul class="framer-text"><li class="framer-text"><p>3+ years with Node.js &amp; TypeScript.</p></li></ul></div><div class="framer-w" data-framer-name="Apply"><p>Apply now</p></div>
</body></html>`;

const sitemap = (...slugs) =>
  `<?xml version="1.0"?><urlset>
  <url><loc>https://vertextech-eg.com/</loc></url>
  <url><loc>https://vertextech-eg.com/vacancies</loc></url>
  ${slugs.map((slug) => `<url><loc>${BASE}/${slug}</loc></url>`).join('\n')}
  </urlset>`;

function fakeClient(routes) {
  return {
    requests: [],
    lastResponse: null,
    async getHtml(url) {
      this.requests.push(url);
      return routes[url] ?? '';
    },
  };
}

function tempStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ljt-vertex-'));
  return new Store(path.join(dir, 'test.sqlite'));
}

test('the sitemap yields vacancy pages only, once each', () => {
  const found = parseSitemap(
    sitemap('senior-java-engineer', 'senior-fullstack-(python)-engineer', 'senior-java-engineer'),
  );

  assert.deepEqual(
    found.map((entry) => entry.externalId),
    ['senior-java-engineer', 'senior-fullstack-(python)-engineer'],
  );
  assert.equal(extractExternalId(`${BASE}/`), null);
  assert.equal(extractExternalId('https://vertextech-eg.com/vacancies'), null);
});

test('a vacancy page gives the title, level, place and description', () => {
  const item = parseVacancyPage(page('Full Stack JS Developer'), `${BASE}/full-stack-js-developer`);

  assert.equal(item.externalId, 'full-stack-js-developer');
  assert.equal(item.title, 'Full Stack JS Developer');
  assert.equal(item.company, 'VerteX Technologies');
  assert.equal(item.seniority, 'Middle');
  assert.equal(item.location, 'Cairo, Smart Village, Egypt');
  assert.equal(item.postedAt, null);
  assert.match(item.excerpt, /^Middle · Node\.js\n/);
  assert.match(item.excerpt, /3\+ years with Node\.js & TypeScript\./);
  assert.doesNotMatch(item.excerpt, /Apply now|<|framer/);
});

test('a page with no heading falls back to its <title>, and an empty one is nothing', () => {
  const html = page('Senior Data Scientist').replace(/<h1[^>]*>[^<]*<\/h1>/, '');
  assert.equal(parseVacancyPage(html, `${BASE}/senior-data-scientist`).title, 'Senior Data Scientist');
  assert.equal(parseVacancyPage('', `${BASE}/x`), null);
});

test('the first run records what is open and sends none of it', async () => {
  const store = tempStore();
  const client = fakeClient({ [SITEMAP]: sitemap('a-role', 'b-role') });
  const source = new VertexSource(client, store);

  assert.deepEqual(await source.fetch(), []);
  assert.deepEqual(client.requests, [SITEMAP]);
  assert.deepEqual([...store.checkedIds('vertex')].sort(), ['a-role', 'b-role']);
});

test('later runs open only vacancies never seen before', async () => {
  const store = tempStore();
  store.markChecked('vertex', 'a-role');

  const client = fakeClient({
    [SITEMAP]: sitemap('a-role', 'backend-node-developer'),
    [`${BASE}/backend-node-developer`]: page('Backend Node Developer'),
  });
  const source = new VertexSource(client, store);

  const items = await source.fetch();

  assert.deepEqual(client.requests, [SITEMAP, `${BASE}/backend-node-developer`]);
  assert.equal(items.length, 1);
  assert.equal(items[0].source, 'vertex');
  assert.equal(items[0].market, 'Egypt');
  assert.equal(items[0].url, `${BASE}/backend-node-developer`);
  assert.ok(store.checkedIds('vertex').has('backend-node-developer'));

  // And the next run opens nothing.
  client.requests.length = 0;
  assert.deepEqual(await source.fetch(), []);
  assert.deepEqual(client.requests, [SITEMAP]);
});

test('with the baseline off, the first run sends what is open', async () => {
  const store = tempStore();
  const client = fakeClient({
    [SITEMAP]: sitemap('backend-node-developer'),
    [`${BASE}/backend-node-developer`]: page('Backend Node Developer'),
  });

  const saved = config.sources.vertex.baseline;
  config.sources.vertex.baseline = false;
  try {
    const items = await new VertexSource(client, store).fetch();
    assert.equal(items.length, 1);
  } finally {
    config.sources.vertex.baseline = saved;
  }
});
