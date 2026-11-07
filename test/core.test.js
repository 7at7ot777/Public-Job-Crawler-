import { test } from 'node:test';
import assert from 'node:assert/strict';
import { years, salary, applyExtract } from '../src/extract.js';
import { regionOf, REGION, home } from '../src/region.js';
import * as Gulf from '../src/gulf.js';
import { scoreOf, learn } from '../src/score.js';
import * as AbroadFit from '../src/sources/abroadFit.js';

// Ported from the Android app's ExtractTest, ScoreTest, AbroadFitTest and RegionTest.

test('extract: english and arabic years', () => {
  assert.deepEqual(years('We need 3-5 years of experience with Laravel'), { min: 3, max: 5 });
  assert.deepEqual(years('3+ yrs backend experience'), { min: 3, max: null });
  assert.deepEqual(years('Minimum 4 years in backend development'), { min: 4, max: null });
  assert.deepEqual(years('Experience: 2 years'), { min: 2, max: null });
  assert.deepEqual(years('مطلوب مطور خبرة 3 سنوات'), { min: 3, max: null });
  assert.deepEqual(years('خبرة من ٢ إلى ٤ سنوات'), { min: 2, max: 4 });
  assert.deepEqual(years('سنتين خبرة في Laravel'), { min: 2, max: 2 });
  assert.deepEqual(years('5 سنوات خبرة'), { min: 5, max: null });
  assert.equal(years('Founded 10 years ago, we build payments'), null);
  assert.equal(years(null), null);
  assert.equal(years('2+ years of experience with PHP, 5+ years of experience preferred').min, 2);
});

test('extract: salaries', () => {
  assert.equal(salary('Salary: 15,000 - 20,000 EGP per month'), 'EGP 15k–20k/mo');
  assert.equal(salary('Package SAR 12k plus housing'), 'SAR 12k');
  assert.equal(salary('الراتب 8000 درهم'), 'AED 8k');
  assert.equal(salary('Pay: $90k–110k/year'), 'USD 90k–110k/yr');
  assert.equal(salary('Competitive salary and benefits'), null);
  assert.equal(salary('Lunch for $5'), null);
  assert.equal(salary('For SALE 2000 units'), null);

  const item = { title: 'PHP Developer', excerpt: '2-4 years of experience. Salary 18,000 EGP monthly' };
  applyExtract(item);
  assert.equal(item.yearsMin, 2);
  assert.equal(item.yearsMax, 4);
  assert.equal(item.salary, 'EGP 18k/mo');
});

const it = (source, market = null, location = null) => ({ source, market, location, title: 'Backend Developer' });

test('region: one per job', () => {
  assert.equal(regionOf(it('wuzzuf', null, 'Cairo, Egypt')), REGION.EGYPT);
  assert.equal(regionOf(it('jobright', Gulf.SAUDI)), REGION.GULF);
  assert.equal(regionOf(it('linkedin_jobs', '🌍 Remote, open to Egypt')), REGION.WORLDWIDE);
  assert.equal(regionOf(it('remote_feeds')), REGION.WORLDWIDE);
  assert.equal(regionOf(it('ats', null, 'Dubai, United Arab Emirates')), REGION.GULF);
  assert.equal(regionOf(it('ats', null, 'Cairo')), REGION.EGYPT);
  assert.equal(regionOf(it('gulf_posts')), REGION.GULF);
  assert.equal(home('ai_data'), REGION.WORLDWIDE);
  assert.equal(regionOf(it('ai_data', null, 'Anywhere')), REGION.WORLDWIDE);
  assert.equal(regionOf(it('ai_data', null, 'Cairo, Egypt')), REGION.EGYPT);
  assert.equal(regionOf(it('mostaql', null, 'Riyadh')), REGION.FREELANCE);
});

const NOW = Date.parse('2026-11-02T10:00:00Z');
const job = (title, excerpt = '', extra = {}) => ({
  source: 'wuzzuf', externalId: title, title, url: `https://x.test/${title}`, company: 'Acme',
  location: 'Cairo, Egypt', excerpt, ...extra,
});
const score = (item, taught) => scoreOf(applyExtract(item), NOW, taught ?? learn());

test('score: ranks what passed', () => {
  assert.ok(score(job('Laravel Developer', 'PHP MySQL')) > score(job('Software Engineer', 'PHP MySQL')));
  const fresh = job('Laravel Developer', '', { postedAt: new Date(NOW - 3_600_000).toISOString() });
  const stale = job('Laravel Developer', '', { postedAt: new Date(NOW - 20 * 86_400_000).toISOString() });
  assert.ok(score(fresh) > score(stale));
  assert.ok(score(job('Laravel Developer', '2 years of experience')) > score(job('Laravel Developer', '6 years of experience')));

  const base = score(job('Laravel Developer'));
  const once = learn([job('PHP Developer')]);
  const twice = learn([job('PHP Developer'), job('Node Developer', '', { company: 'ACME' })]);
  assert.equal(score(job('Laravel Developer'), once), base);
  assert.equal(score(job('Laravel Developer'), twice), Math.max(0, base - 25));

  const abroad = job('Laravel Developer', 'Remote, open to Egypt. PHP, Laravel', { source: 'remote_feeds' });
  assert.ok(Math.abs(score(abroad) - AbroadFit.scoreItem(abroad)) <= 15);
  for (const title of ['Laravel PHP Node.js Backend Developer', 'Cashier', 'x']) {
    const value = score(job(title, 'Laravel PHP Node.js MySQL Redis Docker AWS remote junior'));
    assert.ok(value >= 0 && value <= 100);
  }
});

const berlinBody =
  'We build payment APIs with PHP and Laravel, MySQL, Redis and Docker. ' +
  '2+ years of experience. Visa sponsorship and relocation package provided. Our team works in English.';

test('abroad fit: hard rules', () => {
  assert.equal(AbroadFit.blocked('Laravel developer. German (C1) is required.'), 'language');
  assert.equal(AbroadFit.blocked('Laravel developer. Fluent English and Dutch.'), 'language');
  assert.equal(AbroadFit.blocked('PHP. Sehr gute Deutschkenntnisse.'), 'language');
  assert.equal(AbroadFit.blocked('Laravel developer. Fluent German is a plus.'), null);
  assert.equal(AbroadFit.blocked(berlinBody), null);
  const german =
    'Wir suchen einen Laravel Entwickler für unser Team in Berlin. Du arbeitest mit PHP und Laravel ' +
    'und baust APIs für unsere Kunden. Wir bieten dir ein Visum und Unterstützung bei der Relocation. Die Arbeit ist ' +
    'spannend und das Team ist nett und wir freuen uns auf dich und deine Bewerbung mit uns.';
  assert.equal(AbroadFit.blocked(german), 'not-english');
  assert.equal(AbroadFit.blocked('Remote Laravel developer, UK only.'), 'region-locked');
  assert.equal(AbroadFit.blocked('Sydney. You must be an Australian citizen or permanent resident.'), 'region-locked');
  assert.equal(AbroadFit.blocked('We are a UK-based fintech; our office is located in London. Visa sponsorship available.'), null);
  assert.equal(AbroadFit.blocked('Laravel developer. Globally distributed team. Remote, US and Canada only.'), 'region-locked');
  assert.equal(AbroadFit.blocked('Our customers are across North America. Remote role open to anyone, worldwide.'), null);
});

test('abroad fit: score, lanes, best of a run', () => {
  const berlin = { title: 'Laravel Developer', excerpt: berlinBody, market: AbroadFit.VISA_LANE, location: 'Berlin, Germany' };
  assert.ok(AbroadFit.scoreItem(berlin) >= 80);
  assert.equal(AbroadFit.abroadRegionOf(berlin.location), '🇪🇺 Europe');
  assert.equal(AbroadFit.laneOf('Fully remote, open to EMEA', false), AbroadFit.REMOTE_LANE);
  assert.equal(AbroadFit.laneOf('Visa sponsorship available', false), AbroadFit.VISA_LANE);
  assert.equal(AbroadFit.laneOf('On site in London', false), null);
  assert.ok(AbroadFit.openToEgypt(null));
  assert.ok(AbroadFit.openToEgypt('EMEA'));
  assert.ok(!AbroadFit.openToEgypt('USA'));

  const good = Array.from({ length: 12 }, (_, i) => ({ ...berlin, externalId: `g${i}`, excerpt: berlinBody + ' Docker '.repeat(i % 3) }));
  const weak = { title: 'Software Engineer', excerpt: 'Visa.', market: AbroadFit.VISA_LANE };
  const chosen = AbroadFit.best([...good, weak]);
  assert.equal(chosen.length, AbroadFit.MAX_PER_RUN);
  assert.ok(!chosen.includes(weak));
});
