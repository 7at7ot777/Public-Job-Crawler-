import { test } from 'node:test';
import assert from 'node:assert/strict';
import { REMOTE_LANE, VISA_LANE } from '../src/sources/abroadFit.js';
import { judgeFeedJob } from '../src/sources/abroad.js';
import { FEEDS, himalayas, jobicy, remotive, remoteOk, weWorkRemotely } from '../src/sources/remoteFeeds.js';
import { ads, latestThread } from '../src/sources/hnHiring.js';
import { asJob as jobgetherJob, earlyReject, parseList as jobgetherList, parseOffer } from '../src/sources/jobgether.js';
import { asJob as sponsorJob, KNOWN_SPONSOR } from '../src/sources/sponsorBoards.js';
import { adzuna, careerjetDate, joobleDate } from '../src/sources/jobApis.js';
import { parseJobright, laneOf as jobrightLane, LANE } from '../src/sources/jobright.js';
import { parseRelocateDetail, parseRelocateList } from '../src/sources/relocateMe.js';
import { age, parseDetail as drjobDetail, parseList as drjobList, slug } from '../src/sources/drjob.js';
import { khamsatPosted, mostaqlPosted, parseKhamsatList, parseMostaqlList } from '../src/sources/freelanceBoards.js';
import { evaluate } from '../src/filters.js';

// Ported from the Android app's FeedsTest, JobrightTest, RelocateMeTest, DrJobWalkTest and FreelanceTest.

const kept = (job, source = 'remote_feeds') => judgeFeedJob(job, source);

test('remote feeds: each switchable feed emits jobs under its own id', () => {
  const samples = {
    himalayas: '{"jobs":[{"guid":"https://himalayas.app/j/1","title":"Backend Developer"}]}',
    jobicy: '{"jobs":[{"id":"1","jobTitle":"Backend Developer","url":"https://jobicy.com/j/1"}]}',
    remotive: '{"jobs":[{"id":"1","title":"Backend Developer","url":"https://remotive.com/j/1"}]}',
    wwr: '<rss><item><title>Acme: Backend Developer</title><link>https://weworkremotely.com/j/1</link></item></rss>',
    remoteok: '[{"legal":"terms"},{"id":"1","position":"Backend Developer","url":"https://remoteok.com/j/1"}]',
    rocketship:
      '<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"initialJobOpenings":[{"id":"1","roleTitle":"Backend Developer","url":"https://ats.example/j/1"}]}}}</script>',
  };

  for (const feed of FEEDS.filter((f) => f.id)) {
    const jobs = feed.parse(samples[feed.id]);
    assert.equal(jobs.length, 1, feed.name);
    assert.equal(jobs[0].feed, feed.id, feed.name);
  }
});

test('remote feeds: a worldwide Laravel job is kept in the remote lane, a US-only one is locked', () => {
  const body = JSON.stringify({
    jobs: [{
      id: 1, url: 'https://remotive.com/remote-jobs/1', title: 'Laravel Developer', company_name: 'Acme',
      candidate_required_location: 'Worldwide', publication_date: '2026-10-30T10:00:00',
      description: '<p>We build APIs with PHP and Laravel, MySQL and Docker. 2+ years of experience. Fully remote.</p>',
    }],
  });
  const job = remotive(body)[0];
  assert.equal(job.postedAt, '2026-10-30T10:00:00.000Z');
  const judged = kept(job);
  assert.equal(judged.rejected, null);
  assert.equal(judged.item.market, REMOTE_LANE);
  assert.equal(judged.item.externalId, 'remotive:1');
  assert.equal(judged.item.board, 'Remotive');

  const [us, cairo] = himalayas(JSON.stringify({
    jobs: [
      { title: 'Backend Developer (Node.js)', companyName: 'Acme', guid: 'https://himalayas.app/a', locationRestrictions: ['United States'], timezoneRestrictions: [], description: 'Node.js and NestJS.', pubDate: 1793500000 },
      { title: 'Backend Developer (Node.js)', companyName: 'Beta', guid: 'https://himalayas.app/b', locationRestrictions: [], timezoneRestrictions: [2, 3], description: 'Remote. Node.js, NestJS, TypeScript. 3+ years of experience.', pubDate: 1793500000 },
    ],
  }));
  assert.equal(kept(us).rejected, 'region-locked');
  assert.equal(cairo.restriction, 'UTC+2');
  assert.equal(kept(cairo).rejected, null);

  const [emea, usa] = jobicy(JSON.stringify({
    jobs: [
      { id: 7, url: 'https://jobicy.com/jobs/7', jobTitle: 'PHP Developer', companyName: 'Acme', jobGeo: 'EMEA', jobDescription: '<p>Laravel and PHP, remote across EMEA. 2 years of experience.</p>', pubDate: '2026-11-01T10:00:00+00:00' },
      { id: 8, url: 'https://jobicy.com/jobs/8', jobTitle: 'PHP Developer', companyName: 'Acme', jobGeo: 'USA', jobDescription: '<p>Laravel.</p>', pubDate: '2026-11-01T10:00:00+00:00' },
    ],
  }));
  assert.equal(kept(emea).rejected, null);
  assert.equal(kept(usa).rejected, 'region-locked');

  const wwr = weWorkRemotely(
    `<rss><channel><item><title>Acme: Laravel Developer</title><region>Anywhere in the World</region>
     <link>https://weworkremotely.com/remote-jobs/acme-laravel-developer</link><pubDate>Sun, 01 Nov 2026 10:00:00 +0000</pubDate>
     <description>&lt;p&gt;PHP and Laravel APIs, remote. 2 years of experience.&lt;/p&gt;</description></item></channel></rss>`,
  )[0];
  assert.equal(wwr.company, 'Acme');
  assert.equal(wwr.title, 'Laravel Developer');
  assert.equal(wwr.postedAt, '2026-11-01T10:00:00.000Z');
  assert.equal(kept(wwr).rejected, null);

  const go = remoteOk(
    '[{"legal":"terms"},{"id":"5","position":"Golang Developer","company":"Acme","location":"Worldwide","description":"Backend in Golang, remote worldwide. 3 years of experience.","url":"https://remoteok.com/remote-jobs/5","date":"2026-11-01T00:00:00+00:00"}]',
  );
  assert.equal(go.length, 1);
  assert.equal(kept(go[0]).rejected, null);
  assert.ok(evaluate(kept(go[0]).item).pass, 'Go passes the shared filters too');
});

test('Hacker News: top-level ads only, VISA in the header is sponsorship', () => {
  assert.equal(latestThread('{"hits":[{"title":"Ask HN: Who wants to be hired?","objectID":"1"},{"title":"Ask HN: Who is hiring? (November 2026)","objectID":"2"}]}'), '2');

  const list = ads(JSON.stringify({
    hits: [
      { objectID: '10', parent_id: 2, comment_text: 'Acme | Backend Engineer (PHP/Laravel) | Berlin | ONSITE | VISA<p>We build payments in PHP and Laravel. 2+ years of experience.', created_at: '2026-11-02T10:00:00Z' },
      { objectID: '11', parent_id: 10, comment_text: 'a reply' },
    ],
  }), '2');
  assert.equal(list.length, 1);
  assert.equal(list[0].company, 'Acme');
  assert.equal(list[0].title, 'Backend Engineer (PHP/Laravel)');
  assert.match(list[0].description, /Visa sponsorship available/);
  const judged = kept(list[0], 'hn_hiring');
  assert.equal(judged.rejected, null);
  assert.equal(judged.item.market, VISA_LANE);
});

test('Jobgether: the list, the early verdict, the offer page', () => {
  const [card] = jobgetherList('{"jobs":[{"id":"abc","title":"Laravel Developer","company":"Acme","url":"https://jobgether.com/offer/abc","location":"Anywhere","remote":"Full Remote","contractType":"Full-time","postedAt":"2026-11-01"}]}');
  assert.equal(card.id, 'abc');
  assert.equal(earlyReject(card), null);
  assert.equal(earlyReject({ ...card, title: 'Sales Manager' }), 'title');
  assert.equal(earlyReject({ ...card, contractType: 'Internship' }), 'internship');

  const offer = parseOffer(`<script type="application/ld+json">{"@type":"JobPosting","description":"<p>PHP and Laravel APIs. Remote. 2 years of experience.</p>","applicantLocationRequirements":[{"name":"EG"},{"name":"DE"}],"hiringOrganization":{"name":"Acme GmbH"}}</script>`);
  assert.ok(offer.countries.has('EG'));
  const job = jobgetherJob(card, offer);
  assert.equal(job.company, 'Acme GmbH');
  assert.match(job.restriction, /Egypt/);
  assert.equal(kept(job, 'jobgether').rejected, null);
});

test('sponsor boards: a board job is marked as from a known sponsor', () => {
  const job = sponsorJob({ externalId: 'greenhouse:monzo:1', title: 'Backend Engineer', url: 'https://x.test', location: 'London, UK', excerpt: 'Go and PHP.' }, 'Monzo');
  assert.equal(job.company, 'Monzo');
  assert.ok(job.description.includes(KNOWN_SPONSOR));
  assert.equal(job.remote, false);
});

test('job APIs: dates and Adzuna', () => {
  assert.equal(joobleDate('2026-09-22T00:00:00.0000000'), '2026-09-22T00:00:00.000Z');
  assert.equal(careerjetDate('Wed,15 Nov 2025 19:13:43 GMT'), '2025-11-15T19:13:43.000Z');
  const [job] = adzuna('{"results":[{"id":"9","title":"<b>PHP</b> Developer","redirect_url":"https://adzuna.test/9","description":"Laravel","location":{"display_name":"London"},"company":{"display_name":"Acme"},"created":"2026-11-01T00:00:00Z"}]}', 'United Kingdom');
  assert.equal(job.title, 'PHP Developer');
  assert.equal(job.location, 'London, United Kingdom');
});

test('jobright: lanes and the page data', () => {
  assert.equal(jobrightLane([], 'fully remote, worldwide', true), LANE.REMOTE);
  assert.equal(jobrightLane(['h1b sponsor likely'], 'on site', false), LANE.VISA);
  assert.equal(jobrightLane(['no h1b'], 'we sponsor', false), null);

  const page = `<script id="__NEXT_DATA__" type="application/json">${JSON.stringify({
    props: { pageProps: { jobList: [
      { jobResult: { jobId: 'j1', jobTitle: 'Backend Engineer', jobSummary: 'Node.js and TypeScript APIs, remote worldwide.', isRemote: true, jobSeniority: 'Mid Level', minYearsOfExperience: 2, publishTime: '2026-11-01 14:01:05', recommendationTags: [] }, companyResult: { companyName: 'Acme' } },
      { jobResult: { jobId: 'j2', jobTitle: 'Backend Engineer', jobSummary: 'Node.js.', isRemote: false, recommendationTags: ['US Citizen Only'] } },
    ] } },
  })}</script>`;
  const [good, citizen] = parseJobright(page);
  assert.equal(good.rejected, null);
  assert.equal(good.item.market, LANE.REMOTE);
  assert.equal(good.item.postedAt, '2026-11-01T14:01:05.000Z');
  assert.equal(citizen.rejected, 'citizen-or-clearance');
});

test('relocate.me: cards and the posting', () => {
  const [card, advert] = parseRelocateList(
    '<div class="jobs-list__job"><a href="/germany/berlin/acme/laravel-developer-123"><b>Laravel Developer</b></a><div class="job__company"><p>Acme</p></div></div>' +
      '<div class="jobs-list__job"><a href="/germany/berlin/acme/paid-option-dev-124"><b>Developer</b></a></div>',
  );
  assert.equal(card.id, '123');
  assert.equal(card.city, 'Berlin');
  assert.equal(card.country, 'Germany');
  assert.ok(advert.sponsored);

  const job = parseRelocateDetail(
    '<script type="application/ld+json">{"@type":"JobPosting","title":"Laravel Developer | Relocation Offered","description":"PHP and Laravel APIs.\n2+ years of experience.","datePosted":"2026-11-01","jobLocation":{"address":{"addressLocality":"Berlin","addressCountry":"Germany"}}}</script>',
    card,
  );
  assert.equal(job.rejected, null);
  assert.equal(job.item.title, 'Laravel Developer');
  assert.equal(job.item.market, '✈️ Relocation to Germany, visa paid');
});

test('Dr.Job: slugs, cards, the posting', () => {
  assert.equal(slug('Node.js Developer'), 'node-js-developer');
  assert.ok(age('30+ days ago'));

  const [item] = drjobList(
    '<div class="card-item job-card"><a class="job-card-stretched-link" href="/egypt/job/php-backend-developer-cairo-pentavalue-MVZW6DME27M83AG"></a>' +
      '<h2 class="job-name">PHP Backend Developer</h2><div class="company-name">Pentavalue</div><div class="location"><span>Cairo - Egypt</span></div>' +
      '<div class="posted-date"><span>15 hours ago</span></div></div>',
    'PHP Developer',
  );
  assert.equal(item.externalId, 'MVZW6DME27M83AG');
  assert.equal(item.location, 'Cairo, Egypt');
  assert.equal(item.market, 'Egypt');
  assert.equal(item.url, 'https://www.drjobpro.com/egypt/job/php-backend-developer-cairo-pentavalue-MVZW6DME27M83AG');

  const detail = drjobDetail('<script type="application/ld+json">{"@type":"JobPosting","description":"<p>Laravel</p>","hiringOrganization":{"name":"Pentavalue"}}</script>');
  assert.equal(detail.description, 'Laravel');
  assert.equal(detail.company, 'Pentavalue');
});

test('Mostaql and Khamsat: listing rows, dates, and the freelance gate', () => {
  assert.equal(mostaqlPosted('2026-11-06 18:20:43'), '2026-11-06T18:20:43.000Z');
  assert.equal(khamsatPosted('05/11/2026 17:26:45 GMT'), '2026-11-05T17:26:45.000Z');

  const [gig] = parseMostaqlList(
    '<table><tr class="project-row"><td><h2><a href="https://mostaql.com/project/123-x">تطوير متجر لارافيل</a></h2>' +
      '<ul class="project__meta"><li><bdi>Ahmed</bdi></li><li>3 عرض</li></ul><time datetime="2026-11-06 18:20:43"></time>' +
      '<p class="project__brief">نحتاج مطور Laravel</p></td></tr></table>',
  );
  assert.equal(gig.externalId, '123');
  assert.equal(gig.company, 'Ahmed');
  assert.match(gig.excerpt, /3 offers/);
  assert.ok(evaluate(gig).pass, 'a gig naming the stack passes');
  assert.equal(evaluate({ ...gig, title: 'تصميم شعار', excerpt: 'شعار لشركة' }).reason, 'stack');

  const [request] = parseKhamsatList(
    '<table><tr id="forum_post-800306" class="forum_post"><td><h3 class="details-head"><a href="/community/requests/800306-x">مطلوب مبرمج Node.js</a></h3>' +
      '<a class="user">Sheikh A</a><span title="05/11/2026 17:26:45 GMT"></span></td></tr></table>',
  );
  assert.equal(request.url, 'https://khamsat.com/community/requests/800306-x');
  assert.equal(request.keyword, 'Node.js');
});

test('LinkedIn worldwide: open to anyone, or out', async () => {
  const { judgeWorldwide, windowSince, params, LANE } = await import('../src/sources/linkedinWorldwide.js');
  const card = { source: 'linkedin_worldwide', externalId: '1', title: 'Backend Developer', url: 'https://www.linkedin.com/jobs/view/1' };
  assert.equal(judgeWorldwide(card, 'Node.js APIs. Work from anywhere in the world.', {}).rejected, null);
  assert.equal(judgeWorldwide(card, 'Node.js APIs. Work from anywhere in the world.', {}).item.market, LANE);
  assert.equal(judgeWorldwide(card, 'Node.js APIs, remote. US-based candidates only.', {}).rejected, 'us-only');
  assert.equal(judgeWorldwide(card, 'Node.js APIs, remote.', {}).rejected, 'not-open-worldwide');
  assert.equal(judgeWorldwide(card, '', {}).rejected, 'no-description');
  assert.equal(windowSince(NaN), 'r3600');
  assert.equal(windowSince(Date.now() - 5 * 3_600_000), 'r18600');
  assert.equal(params('Laravel', 'r3600').f_WT, '2');
});

test('Glassdoor: titles, ids, lanes and scopes', async () => {
  const { splitTitle, idOf, judgeGlassdoor, parseSerper, QUERIES, GULF_QUERIES, VISA_LANE } = await import('../src/sources/glassdoor.js');
  assert.deepEqual(splitTitle('Acme hiring Backend Developer Job in Cairo, Cairo | Glassdoor'), { role: 'Backend Developer', company: 'Acme', place: 'Cairo, Cairo' });
  assert.equal(idOf('https://www.glassdoor.com/job-listing/x?jl=1009123'), '1009123');
  assert.equal(parseSerper('{"organic":[{"link":"https://www.glassdoor.com/Job/egypt-php-jobs-SRCH_1.htm"},{"link":"https://www.glassdoor.com/job-listing/php-dev?jl=5","title":"Acme hiring PHP Developer Job in Cairo"}]}').length, 1);
  assert.throws(() => parseSerper('{"message":"Not enough credits"}'), /Not enough credits/);

  const egypt = judgeGlassdoor({ url: 'https://www.glassdoor.com/job-listing/a?jl=1', title: 'Acme hiring PHP Developer Job in Cairo' }, QUERIES[0]);
  assert.equal(egypt.rejected, null);
  assert.equal(egypt.item.market, null);
  const berlin = judgeGlassdoor({ url: 'https://www.glassdoor.de/job-listing/b?jl=2', title: 'Acme hiring Laravel Developer Job in Berlin', snippet: 'PHP Laravel, visa sponsorship.' }, QUERIES[3]);
  assert.equal(berlin.item.market, VISA_LANE);
  const dubai = judgeGlassdoor({ url: 'https://www.glassdoor.com/job-listing/c?jl=3', title: 'Acme hiring PHP Developer Job in Dubai' }, GULF_QUERIES[1]);
  assert.equal(dubai.item.market, 'United Arab Emirates');
});

test('career sites: entries, ids, JSON-LD and markup pages', async () => {
  const { parseSite, idOf, parseCareerPage, locs } = await import('../src/sources/careerSites.js');
  const site = parseSite('acme|Acme|https://acme.com/sitemap.xml|^https://acme\\.com/jobs/([^/]+)/?$');
  assert.equal(site.location, 'Cairo, Egypt');
  assert.equal(parseSite('broken'), null);
  assert.deepEqual(locs('<urlset><url><loc>https://acme.com/jobs/php-dev</loc></url><url><loc>https://acme.com/about</loc></url></urlset>'), ['https://acme.com/jobs/php-dev', 'https://acme.com/about']);
  assert.equal(idOf(site, 'https://acme.com/jobs/PHP-Dev/'), 'php-dev');
  assert.equal(idOf(site, 'https://acme.com/about'), null);

  const info = { url: 'https://acme.com/jobs/php-dev', externalId: 'acme:php-dev', site };
  const ld = parseCareerPage('<script type="application/ld+json">{"@graph":[{"@type":"JobPosting","title":"PHP Developer","description":"<p>Laravel</p>","jobLocation":{"address":{"addressLocality":"Giza","addressCountry":"EG"}}}]}</script>', info);
  assert.equal(ld.title, 'PHP Developer');
  assert.equal(ld.location, 'Giza, EG');
  const closed = parseCareerPage('<script type="application/ld+json">{"@type":"JobPosting","title":"PHP Developer","validThrough":"2020-01-01"}</script>', info);
  assert.equal(closed, null);
  const markup = parseCareerPage('<title>Backend Engineer | Acme Careers</title><main><p>Node.js</p><script>x</script></main>', info);
  assert.equal(markup.title, 'Backend Engineer');
  assert.equal(markup.excerpt, 'Node.js');
});

test('Wuzzuf: the index names the job sitemaps, the newest per market is read', async () => {
  const { jobSitemaps, chooseSitemaps } = await import('../src/sources/wuzzuf.js');
  const index = `<sitemapindex>
    <sitemap><loc>https://wuzzuf.net/sitemap-job-1.xml</loc><lastmod>2026-11-01</lastmod></sitemap>
    <sitemap><loc>https://wuzzuf.net/sitemap-job-2.xml</loc><lastmod>2026-11-07</lastmod></sitemap>
    <sitemap><loc>https://wuzzuf.net/sitemap-saudi-job-1.xml</loc></sitemap>
    <sitemap><loc>https://wuzzuf.net/sitemap-companies.xml</loc></sitemap>
  </sitemapindex>`;
  const listed = jobSitemaps(index);
  assert.equal(listed.length, 3);
  assert.deepEqual(chooseSitemaps(listed).map((s) => s.url), [
    'https://wuzzuf.net/sitemap-job-2.xml',
    'https://wuzzuf.net/sitemap-saudi-job-1.xml',
  ]);
});
