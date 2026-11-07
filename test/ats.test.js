import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AtsSource, boardMaxAgeDays, isOlderThan } from '../src/sources/ats.js';
import { parseBoard, parseBoardResponse } from '../src/sources/atsParser.js';
import { BlockedError } from '../src/errors.js';

const daysAgo = (days) => new Date(Date.now() - days * 86_400_000).toISOString();
// Recruitee's format is "YYYY-MM-DD HH:MM:SS UTC"; kept relative so the fetch
// test below (maxAgeDays: 14) does not start failing as the calendar moves.
const recruiteeDate = daysAgo(2).slice(0, 10);

const FEEDS = {
  'https://api.lever.co/v0/postings/klivvr?mode=json': JSON.stringify([
    {
      id: 'l1',
      text: 'Backend Engineer',
      hostedUrl: 'https://jobs.lever.co/klivvr/l1',
      categories: { location: 'One Kattameya, Cairo', commitment: 'Full-time' },
      workplaceType: 'onsite',
      createdAt: Date.now() - 86_400_000,
      descriptionPlain: 'Node.js and PostgreSQL.',
    },
  ]),
  'https://boards-api.greenhouse.io/v1/boards/tamara/jobs?content=true': JSON.stringify({
    jobs: [
      {
        id: 7,
        title: 'Backend Engineer',
        absolute_url: 'https://job-boards.eu.greenhouse.io/tamara/jobs/7',
        company_name: 'Tamara',
        location: { name: 'Riyadh, Saudi Arabia' },
        first_published: daysAgo(2),
        content: '&lt;p&gt;Laravel &amp;amp; PHP&lt;/p&gt;',
      },
    ],
  }),
  'https://api.ashbyhq.com/posting-api/job-board/thndr': JSON.stringify({
    jobs: [
      {
        id: 'a1',
        title: 'DevOps Engineer',
        jobUrl: 'https://jobs.ashbyhq.com/thndr/a1',
        location: 'Cairo Office',
        address: { postalAddress: { addressLocality: 'Cairo', addressCountry: 'Egypt' } },
        workplaceType: 'Hybrid',
        publishedAt: daysAgo(1),
        isListed: true,
        descriptionPlain: 'Kubernetes.',
      },
      { id: 'a2', title: 'Hidden', jobUrl: 'https://jobs.ashbyhq.com/thndr/a2', isListed: false },
    ],
  }),
  'https://instabug.bamboohr.com/careers/list': JSON.stringify({
    result: [{ id: '124', jobOpeningName: 'Product Engineer (Backend)', location: { city: 'CAIRO' }, locationType: '2' }],
  }),
  'https://elmenus.recruitee.com/api/offers/': JSON.stringify({
    offers: [
      {
        id: 9,
        title: 'PHP Developer',
        careers_url: 'https://elmenus.recruitee.com/o/php-developer',
        company_name: 'elmenus',
        location: 'Cairo, Egypt',
        published_at: `${recruiteeDate} 13:38:06 UTC`,
        remote: true,
        status: 'published',
        description: '<p>Laravel</p>',
      },
    ],
  }),
};

function fakeClient(routes) {
  return {
    requests: [],
    lastResponse: null,
    async getHtml(url) {
      this.requests.push(url);
      const answer = routes[url];
      if (answer instanceof Error) throw answer;
      return answer ?? '';
    },
  };
}

const board = (entry) => parseBoard(entry);

test('parseBoard reads platform, slug and an optional name', () => {
  assert.deepEqual(parseBoard('lever:klivvr'), { platform: 'lever', slug: 'klivvr', name: null });
  assert.deepEqual(parseBoard('BambooHR:instabug:Instabug'), {
    platform: 'bamboohr',
    slug: 'instabug',
    name: 'Instabug',
  });
  assert.equal(parseBoard('icims:acme'), null);
  assert.equal(parseBoard('lever'), null);
});

test('lever jobs become items with a board-scoped id', () => {
  const [item] = parseBoardResponse(
    FEEDS['https://api.lever.co/v0/postings/klivvr?mode=json'],
    board('lever:klivvr:Klivvr'),
  );

  assert.equal(item.externalId, 'lever:klivvr:l1');
  assert.equal(item.company, 'Klivvr');
  assert.equal(item.location, 'One Kattameya, Cairo');
  assert.equal(item.workplace, 'On-site');
  assert.equal(item.remote, false);
  assert.match(item.excerpt, /Node\.js/);
  assert.ok(item.postedAt);
});

test('greenhouse content is unescaped before tags are stripped', () => {
  const [item] = parseBoardResponse(
    FEEDS['https://boards-api.greenhouse.io/v1/boards/tamara/jobs?content=true'],
    board('greenhouse:tamara'),
  );

  assert.equal(item.company, 'Tamara');
  assert.equal(item.excerpt, 'Laravel & PHP');
});

test('ashby uses the address over the office name and skips unlisted jobs', () => {
  const items = parseBoardResponse(
    FEEDS['https://api.ashbyhq.com/posting-api/job-board/thndr'],
    board('ashby:thndr'),
  );

  assert.equal(items.length, 1);
  assert.equal(items[0].location, 'Cairo, Egypt');
  assert.equal(items[0].workplace, 'Hybrid');
  assert.equal(items[0].company, 'Thndr');
});

test('bamboohr builds the job url and leaves the date unset', () => {
  const [item] = parseBoardResponse(
    FEEDS['https://instabug.bamboohr.com/careers/list'],
    board('bamboohr:instabug:Instabug'),
  );

  assert.equal(item.url, 'https://instabug.bamboohr.com/careers/124');
  assert.equal(item.workplace, 'Hybrid');
  assert.equal(item.postedAt, null);
});

test('recruitee reads its "UTC" timestamps and remote flag', () => {
  const [item] = parseBoardResponse(
    FEEDS['https://elmenus.recruitee.com/api/offers/'],
    board('recruitee:elmenus'),
  );

  assert.equal(item.postedAt, `${recruiteeDate}T13:38:06.000Z`);
  assert.equal(item.remote, true);
});

test('teamtailor reads its RSS feed, place and workplace', () => {
  const rss = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:tt="https://teamtailor.com/locations"><channel>
  <title>Axis</title><link>https://careers.axisapp.com/jobs</link>
  <item>
    <title>Backend Software Engineer</title>
    <description>&lt;p&gt;PHP &amp;amp; Laravel&lt;/p&gt;</description>
    <pubDate>Wed, 09 Sep 2026 11:58:18 +0300</pubDate>
    <link>https://careers.axisapp.com/jobs/8351217-backend-software-engineer</link>
    <remoteStatus>hybrid</remoteStatus>
    <guid>1e29dbc6</guid>
    <tt:locations><tt:location>
      <tt:name>Axis HQ Office</tt:name><tt:city>Cairo</tt:city><tt:country>Egypt</tt:country>
    </tt:location></tt:locations>
  </item>
</channel></rss>`;

  const [item] = parseBoardResponse(rss, board('teamtailor:careers.axisapp.com:Axis'));

  assert.equal(item.externalId, 'teamtailor:careers.axisapp.com:1e29dbc6');
  assert.equal(item.url, 'https://careers.axisapp.com/jobs/8351217-backend-software-engineer');
  assert.equal(item.company, 'Axis');
  assert.equal(item.location, 'Cairo, Egypt');
  assert.equal(item.workplace, 'Hybrid');
  assert.equal(item.postedAt, '2026-09-09T08:58:18.000Z');
  assert.equal(item.excerpt, 'PHP & Laravel');
  assert.equal(parseBoardResponse('<html>404</html>', board('teamtailor:axis')), null);
});

test('successfactors keeps one hiring unit and names its place', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<Job-Listing>
<Job>
<JobTitle><![CDATA[Infotainment &amp; Connectivity Engineer]]></JobTitle>
<Job-Description><![CDATA[<p>Node.js&#13;</p>]]></Job-Description>
<ReqId>512863</ReqId>
<Posted-Date>09/09/2026</Posted-Date>
<>15638549</>
<>793</>
</Job>
<Job>
<JobTitle><![CDATA[Business Analyst (m/w/d)]]></JobTitle>
<ReqId>295249</ReqId>
<Posted-Date>08/12/2025</Posted-Date>
<>1144098</>
<>519</>
</Job>
</Job-Listing>`;

  const items = parseBoardResponse(
    xml,
    board('successfactors:career5.successfactors.eu/capgemitecP3/15638549/Egypt:Capgemini'),
  );

  assert.equal(items.length, 1);
  assert.equal(items[0].title, 'Infotainment & Connectivity Engineer');
  assert.equal(
    items[0].url,
    'https://career5.successfactors.eu/career?company=capgemitecP3&career_ns=job_listing&career_job_req_id=512863',
  );
  assert.equal(items[0].company, 'Capgemini');
  assert.equal(items[0].location, 'Egypt');
  assert.equal(items[0].postedAt, '2026-09-09T00:00:00.000Z');
  assert.equal(items[0].excerpt, 'Node.js');

  // Without a unit, the whole listing.
  const all = parseBoardResponse(xml, board('successfactors:career5.successfactors.eu/capgemitecP3'));
  assert.equal(all.length, 2);
  assert.equal(all[1].location, null);
});

test('breezy names every place a job is open in', () => {
  const json = JSON.stringify([
    {
      id: '617fd04c8a6b',
      name: 'Senior Product Designer',
      url: 'https://almentor.breezy.hr/p/617fd04c8a6b-senior-product-designer',
      published_date: '2026-10-06T12:48:27.004Z',
      type: { id: 'fullTime', name: 'Full-Time' },
      location: {
        country: { name: 'Egypt', id: 'EG' },
        city: 'Cairo',
        is_remote: true,
        remote_details: { value: 'hybrid' },
        name: 'Cairo, EG',
      },
      locations: [
        { country: { name: 'Egypt', id: 'EG' }, city: 'Cairo', name: 'Cairo, EG' },
        { country: { name: 'Saudi Arabia', id: 'SA' }, city: 'Riyadh', name: 'Riyadh, SA' },
      ],
      company: { name: 'almentor' },
      description: '',
    },
  ]);

  const [item] = parseBoardResponse(json, board('breezy:almentor'));

  assert.equal(item.externalId, 'breezy:almentor:617fd04c8a6b');
  assert.equal(item.company, 'almentor');
  assert.equal(item.location, 'Cairo, Egypt / Riyadh, Saudi Arabia');
  assert.equal(item.workplace, 'Hybrid');
  assert.equal(item.employmentType, 'Full-Time');
  assert.equal(item.postedAt, '2026-10-06T12:48:27.004Z');
  assert.equal(item.excerpt, null);
});

test('zoho reads the jobs embedded in its careers page', () => {
  const jobs = [
    {
      Remote_Job: false,
      Posting_Title: 'Fullstack Developer',
      City: 'Cairo',
      State: null,
      Country: 'Egypt',
      Job_Type: 'Full time',
      Job_Description: '<p>Node.js &amp; "React"</p>',
      id: '270332000006552156',
      Publish: true,
      Date_Opened: '2026-08-06',
    },
    { Remote_Job: true, Posting_Title: 'Product Owner', Country: 'Egypt', id: '2', Publish: true },
    { Posting_Title: 'Closed', Country: 'Egypt', id: '3', Publish: false },
  ];
  const escaped = JSON.stringify(jobs).replace(/&/g, '&amp;').replace(/"/g, '&#34;');
  const html = `<html><input type="hidden" value="[]" id="moduleMeta">
    <input type="hidden" value="${escaped}" id="jobs"></html>`;

  const items = parseBoardResponse(html, board('zoho:qutip:QuTip'));

  assert.equal(items.length, 2);
  assert.equal(items[0].externalId, 'zoho:qutip:270332000006552156');
  assert.equal(items[0].url, 'https://qutip.zohorecruit.com/jobs/Careers/270332000006552156');
  assert.equal(items[0].company, 'QuTip');
  assert.equal(items[0].location, 'Cairo, Egypt');
  assert.equal(items[0].employmentType, 'Full time');
  assert.equal(items[0].postedAt, '2026-08-06T00:00:00.000Z');
  assert.equal(items[0].excerpt, 'Node.js & "React"');
  assert.equal(items[1].workplace, 'Remote');
  assert.equal(items[1].postedAt, null);

  const [other] = parseBoardResponse(html, board('zoho:zohomea.zohorecruit.in'));
  assert.equal(other.url, 'https://zohomea.zohorecruit.in/jobs/Careers/270332000006552156');
  assert.equal(parseBoardResponse('<html>not found</html>', board('zoho:qutip')), null);
});

test('a non-feed body is null, not an empty board', () => {
  assert.equal(parseBoardResponse('', board('lever:gone')), null);
  assert.equal(parseBoardResponse('<html>404</html>', board('lever:gone')), null);
});

test('fetch reads every board, skips a broken one and drops old jobs', async () => {
  const routes = {
    ...FEEDS,
    'https://api.lever.co/v0/postings/klivvr?mode=json': JSON.stringify([
      {
        id: 'old',
        text: 'Backend Engineer',
        hostedUrl: 'https://jobs.lever.co/klivvr/old',
        createdAt: Date.now() - 60 * 86_400_000,
      },
    ]),
    'https://api.lever.co/v0/postings/down?mode=json': new BlockedError('timed out', [], true),
  };

  const source = new AtsSource(fakeClient(routes));
  Object.defineProperty(source, 'settings', {
    value: {
      boards: ['lever:klivvr', 'lever:down', 'ashby:thndr', 'nope:x', 'recruitee:elmenus'],
      maxAgeDays: 14,
    },
  });

  const items = await source.fetch();

  assert.deepEqual(
    items.map((item) => item.externalId),
    ['ashby:thndr:a1', 'recruitee:elmenus:9'],
  );
  assert.ok(items.every((item) => item.source === 'ats' && item.market === null));
});

test('a block that is not a transport failure stops the run with what it has', async () => {
  const routes = {
    ...FEEDS,
    'https://api.lever.co/v0/postings/klivvr?mode=json': new BlockedError('HTTP 429'),
  };

  const source = new AtsSource(fakeClient(routes));
  Object.defineProperty(source, 'settings', {
    value: { boards: ['ashby:thndr', 'lever:klivvr', 'recruitee:elmenus'], maxAgeDays: 14 },
  });

  await assert.rejects(source.fetch(), (error) => {
    assert.ok(error instanceof BlockedError);
    assert.deepEqual(
      error.partialItems.map((item) => item.externalId),
      ['ashby:thndr:a1'],
    );
    return true;
  });
});

test('boardMaxAgeDays reads platform:slug:days and skips malformed entries', () => {
  const ages = boardMaxAgeDays(['zoho:Careers.MigrationIT.com:3', 'lever:klivvr', 'zoho:x:0', ':5']);

  assert.deepEqual([...ages], [['zoho:careers.migrationit.com', 3]]);
  assert.equal(boardMaxAgeDays(undefined).size, 0);
});

test('a date with no time is aged by calendar day', () => {
  const now = Date.parse('2026-11-03T21:00:00Z');

  assert.equal(isOlderThan('2026-10-31T00:00:00.000Z', 3, now), false);
  assert.equal(isOlderThan('2026-10-30T00:00:00.000Z', 3, now), true);
  // With a time of day the cut stays exact.
  assert.equal(isOlderThan('2026-10-31T20:00:00.000Z', 3, now), true);
  assert.equal(isOlderThan('2026-10-31T22:00:00.000Z', 3, now), false);
  assert.equal(isOlderThan(null, 3, now), false);
});

test("a board's own cutoff overrides the default age", async () => {
  const day = (days) => daysAgo(days).slice(0, 10);
  const jobs = [
    { Posting_Title: 'Android Developer', Country: 'Egypt', id: 'new', Publish: true, Date_Opened: day(1) },
    { Posting_Title: 'iOS Developer', Country: 'Egypt', id: 'edge', Publish: true, Date_Opened: day(3) },
    { Posting_Title: 'PHP Developer', Country: 'Egypt', id: 'old', Publish: true, Date_Opened: day(5) },
    { Posting_Title: 'Filled', Country: 'Egypt', id: 'filled', Publish: false, Date_Opened: day(1) },
  ];
  const escaped = JSON.stringify(jobs).replace(/"/g, '&#34;');
  const html = `<input type="hidden" value="${escaped}" id="jobs">`;

  const routes = {
    ...FEEDS,
    'https://careers.migrationit.com/jobs/Careers': html,
    'https://qutip.zohorecruit.com/jobs/Careers': html,
  };

  const source = new AtsSource(fakeClient(routes));
  Object.defineProperty(source, 'settings', {
    value: {
      boards: ['zoho:careers.migrationit.com:MigrationIT', 'zoho:qutip:QuTip'],
      boardMaxAgeDays: ['zoho:careers.migrationit.com:3'],
      maxAgeDays: 14,
    },
  });

  const items = await source.fetch();

  assert.deepEqual(
    items.map((item) => item.externalId),
    [
      'zoho:careers.migrationit.com:new',
      'zoho:careers.migrationit.com:edge',
      'zoho:qutip:new',
      'zoho:qutip:edge',
      'zoho:qutip:old',
    ],
  );
  assert.equal(items[0].url, 'https://careers.migrationit.com/jobs/Careers/new');
  assert.equal(items[0].company, 'MigrationIT');
});

// Ported from the Android app: the Saudi/UAE employers' platforms.
test('smartrecruiters, workday and oracle boards parse', async () => {
  const { workdayPosted, workdaySlug } = await import('../src/sources/atsParser.js');

  const [sr] = parseBoardResponse(
    JSON.stringify({ content: [{ id: '7', name: 'Backend Developer', location: { fullLocation: 'Ruwais, , United Arab Emirates' }, releasedDate: '2026-11-01T00:00:00Z' }] }),
    board('smartrecruiters:ArchirodonGroup:Archirodon'),
  );
  assert.equal(sr.location, 'Ruwais, United Arab Emirates');
  assert.equal(sr.url, 'https://jobs.smartrecruiters.com/ArchirodonGroup/7');
  assert.equal(sr.company, 'Archirodon');

  assert.deepEqual(workdaySlug('wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi'), {
    host: 'wd3.myworkdaysite.com', tenant: 'yokogawa', site: 'yokogawa-career-site', search: 'Saudi',
  });
  const now = Date.parse('2026-11-07T00:00:00Z');
  assert.equal(workdayPosted('Posted Today', now), '2026-11-07T00:00:00.000Z');
  assert.equal(workdayPosted('Posted 30+ Days Ago', now), '2026-10-07T00:00:00.000Z');
  const [wd] = parseBoardResponse(
    JSON.stringify({ jobPostings: [{ title: 'Software Engineer', externalPath: '/job/Riyadh/SE_R1', locationsText: 'Riyadh, Saudi Arabia', postedOn: 'Posted Yesterday', bulletFields: ['R1'] }] }),
    board('workday:wd3.myworkdaysite.com/yokogawa/yokogawa-career-site?Saudi:Yokogawa'),
  );
  assert.equal(wd.url, 'https://wd3.myworkdaysite.com/recruiting/yokogawa/yokogawa-career-site/job/Riyadh/SE_R1');
  assert.match(wd.externalId, /R1$/);

  const [ora] = parseBoardResponse(
    JSON.stringify({ items: [{ requisitionList: [{ Id: '55', Title: 'PHP Developer', PrimaryLocation: 'Riyadh', WorkplaceTypeCode: 'ORA_ON_SITE', PostedDate: '2026-11-01' }] }] }),
    board('oracle:eghj.fa.em2.oraclecloud.com/CX_1:NHC'),
  );
  assert.equal(ora.url, 'https://eghj.fa.em2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1/job/55');
  assert.equal(ora.workplace, 'On-site');
});
