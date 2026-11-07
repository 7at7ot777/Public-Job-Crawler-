import { test } from 'node:test';
import assert from 'node:assert/strict';
import config from '../src/config.js';
import { evaluate } from '../src/filters.js';
import { AiDataSource, KEY } from '../src/sources/aiData.js';
import { dedupKey, modalitiesOf, placeWanted, variantRank } from '../src/sources/aiDataParser.js';
import { fingerprintOf } from '../src/store.js';

const daysAgo = (days) => new Date(Date.now() - days * 86_400_000).toISOString();

const workableJob = (id, overrides = {}) => ({
  id,
  title: 'Arabic Audio Annotator',
  state: 'published',
  description: '<p>Label Egyptian Arabic speech recordings.</p>',
  requirementsSection: '<ul><li>Native Arabic</li></ul>',
  employmentType: 'Contract',
  url: `https://jobs.workable.com/view/${id}/annotator`,
  locations: ['Cairo, Cairo Governorate, Egypt'],
  location: { city: 'Cairo', countryName: 'Egypt' },
  created: daysAgo(1),
  company: { title: 'Vendor' },
  workplace: 'on_site',
  ...overrides,
});

const page = (jobs) => JSON.stringify({ totalSize: jobs.length, nextPageToken: null, jobs });

function fakeClient(answer) {
  return {
    requests: [],
    lastResponse: null,
    async getHtml(url, query = {}) {
      const target = new URL(url);
      for (const [key, value] of Object.entries(query)) {
        if (value !== null && value !== undefined) target.searchParams.set(key, value);
      }
      this.requests.push(target);
      return answer(target) ?? '';
    },
  };
}

/** Runs fn with config.sources.ai_data patched, then puts it back. */
async function withSettings(patch, fn) {
  const settings = config.sources[KEY];
  const saved = { ...settings };
  Object.assign(settings, patch);
  try {
    return await fn();
  } finally {
    Object.assign(settings, saved);
  }
}

test('modalitiesOf names the data a job works on', () => {
  assert.equal(
    modalitiesOf({ title: 'Arabic Speech Transcriber', excerpt: 'MSA and Egyptian dialect' }),
    'Arabic · Audio',
  );
  assert.equal(modalitiesOf({ title: 'Image Annotation Specialist' }), 'Image');
  assert.equal(modalitiesOf({ title: 'مُقيّم نصوص', excerpt: 'تقييم نصوص اللغة العربية' }), 'Arabic · Text');
  assert.equal(modalitiesOf({ title: 'AI Trainer', excerpt: 'Help us.' }), null);
});

test('placeWanted keeps placed, remote and named-place jobs only', () => {
  const places = ['egypt', 'cairo'];
  assert.equal(placeWanted({ market: 'Egypt', title: 'x' }, places), true);
  assert.equal(placeWanted({ remote: true, title: 'x', location: 'Manila' }, places), true);
  assert.equal(placeWanted({ title: 'Annotator', location: 'Cairo' }, places), true);
  assert.equal(placeWanted({ title: 'Annotator', location: 'Manila, Philippines' }, places), false);
});

test('searches every keyword x location; Anywhere keeps only remote jobs', async () => {
  const client = fakeClient((url) => {
    if (url.searchParams.has('location')) return page([workableJob('eg')]);
    // A borderless search: one remote job, one on-site abroad.
    return page([
      workableJob('remote', {
        // A different role: the same company + title is one role (see below).
        title: 'Arabic Speech Transcriber',
        workplace: 'remote',
        locations: ['Spain'],
        location: { countryName: 'Spain' },
      }),
      workableJob('manila', { locations: ['Manila, Philippines'], location: { countryName: 'Philippines' } }),
    ]);
  });

  const items = await withSettings(
    { keywords: ['Data Annotation', 'Transcription'], locations: ['Egypt', 'Anywhere'], boards: [] },
    () => new AiDataSource(client).fetch(),
  );

  assert.equal(client.requests.length, 4);
  assert.equal(client.requests[0].searchParams.get('location'), 'Egypt');
  assert.equal(client.requests[2].searchParams.has('location'), false);

  const ids = items.map((item) => item.externalId).sort();
  assert.deepEqual(ids, ['eg', 'remote']);

  const eg = items.find((item) => item.externalId === 'eg');
  assert.equal(eg.source, KEY);
  assert.equal(eg.market, 'Egypt');
  assert.equal(eg.keyword, 'Arabic · Audio');
});

test('reads vendor boards and skips one that answers nothing', async () => {
  const lever = JSON.stringify([
    {
      id: 'l1',
      text: 'Image Annotation Analyst',
      hostedUrl: 'https://jobs.lever.co/vendor/l1',
      categories: { location: 'Remote', commitment: 'Freelance' },
      workplaceType: 'remote',
      createdAt: Date.now() - 86_400_000,
      descriptionPlain: 'Draw bounding boxes on street images.',
    },
  ]);

  const client = fakeClient((url) => (url.hostname === 'api.lever.co' ? lever : ''));

  const items = await withSettings(
    { keywords: [], boards: ['greenhouse:gone', 'lever:vendor:Vendor', 'nope:x'] },
    () => new AiDataSource(client).fetch(),
  );

  assert.equal(items.length, 1);
  assert.equal(items[0].company, 'Vendor');
  assert.equal(items[0].keyword, 'Image');
  assert.equal(items[0].externalId, 'lever:vendor:l1');
});

test('the filter keeps labelling roles and drops everything else', () => {
  const job = (title, extra = {}) => ({
    source: KEY,
    itemType: 'job',
    title,
    location: 'Remote',
    remote: true,
    excerpt: 'Freelance work.',
    ...extra,
  });

  assert.equal(evaluate(job('Arabic Data Annotator')).pass, true);
  assert.equal(evaluate(job('Audio Transcriptionist (Egyptian Arabic)')).pass, true);
  assert.equal(evaluate(job('AI Rater - Arabic')).pass, true);
  // On-site in Cairo is wanted here, unlike the general roles elsewhere.
  assert.equal(evaluate(job('Image Annotator', { remote: false, location: 'Cairo, Egypt' })).pass, true);

  assert.equal(evaluate(job('Account Executive')).reason, 'title');
  assert.equal(evaluate(job('Data Annotator', { location: 'Bangalore, India' })).reason, 'location-blocked');
});

test('only generalist AI work, in Arabic or Arabic with English', () => {
  const job = (title, excerpt = 'Freelance work.') => ({
    source: KEY,
    itemType: 'job',
    title,
    location: 'Remote',
    remote: true,
    excerpt,
  });

  // General, and Arabic or no language named: kept.
  assert.equal(evaluate(job('AI Trainer')).pass, true);
  assert.equal(evaluate(job('Arabic AI Trainer')).pass, true);
  assert.equal(evaluate(job('AI Trainer (Arabic & English)')).pass, true);
  assert.equal(evaluate(job('مدرب ذكاء اصطناعي', 'العمل باللغة العربية')).pass, true);
  assert.equal(evaluate(job('Data Annotator', 'Fluent in Arabic and English.')).pass, true);
  assert.equal(evaluate(job('AI Trainer', 'French, German and Arabic speakers welcome.')).pass, true);
  // "Germany" is a place, not the language.
  assert.equal(evaluate(job('AI Rater', 'Our office is in Germany.')).pass, true);

  // Software and coding gigs, and image labelling in no particular field: kept.
  assert.equal(evaluate(job('AI Trainer - Software Engineering')).pass, true);
  assert.equal(evaluate(job('Python Coding AI Trainer')).pass, true);
  assert.equal(evaluate(job('Image Labeler')).pass, true);
  assert.equal(evaluate(job('Image Annotation Engineer')).pass, true);
  assert.equal(evaluate(job('Video Labeling Specialist')).pass, true);
  assert.equal(evaluate(job('وسم الصور - عمل حر')).pass, true);

  // Tied to a field that needs a degree: dropped.
  assert.equal(evaluate(job('Medical Image Annotator')).reason, 'title-excluded');
  assert.equal(evaluate(job('Mechanical Engineering AI Trainer')).reason, 'title-excluded');
  assert.equal(evaluate(job('Freelance Mechanical CAD&Simulation Engineer - AI Trainer')).reason, 'title-excluded');
  assert.equal(evaluate(job('CAD Designer - AI Trainer')).reason, 'title-excluded');
  assert.notEqual(evaluate(job('Academy AI Trainer')).reason, 'title-excluded');
  assert.equal(evaluate(job('Medical AI Trainer')).reason, 'title-excluded');
  assert.equal(evaluate(job('Physics Expert AI Trainer')).reason, 'title-excluded');
  assert.equal(evaluate(job('Pharmacist AI Rater')).reason, 'title-excluded');
  assert.equal(evaluate(job('Medical Transcriptionist')).reason, 'title-excluded');
  assert.equal(evaluate(job('مدرب ذكاء اصطناعي - مهندس مدني')).reason, 'title-excluded');

  // Another language and no Arabic: dropped.
  assert.equal(evaluate(job('French AI Trainer')).reason, 'language');
  assert.equal(evaluate(job('AI Rater', 'Native German speaker required.')).reason, 'language');
});

test('the generalist and language rules are ai_data only', () => {
  const linkedin = {
    source: 'linkedin',
    itemType: 'job',
    title: 'French AI Trainer',
    location: 'Remote',
    remote: true,
    excerpt: 'Freelance work.',
  };

  const { reason } = evaluate(linkedin);
  assert.notEqual(reason, 'language');
  assert.notEqual(reason, 'title-excluded');
});

test('one role posted once per country is one item in a run', async () => {
  const requisition = (id, country, overrides = {}) =>
    workableJob(id, {
      title: 'Freelance Arabic AI Trainer',
      company: { title: 'Mindrift' },
      workplace: 'remote',
      locations: [country],
      location: { countryName: country },
      ...overrides,
    });

  // Every search answers with the same role, once per country, plus one
  // unrelated role posted twice; case and dashes differ between requisitions.
  const client = fakeClient(() =>
    page([
      requisition('in', 'India'),
      requisition('es', 'Spain'),
      requisition('pl', 'Poland', { title: 'freelance arabic ai trainer' }),
      requisition('eg', 'Egypt'),
      requisition('ke', 'Kenya', { title: 'Ultrasound Image Annotator', company: { title: 'CloudFactory' } }),
      requisition('ma', 'Morocco', { title: 'Ultrasound Image Annotator – ', company: { title: 'CLOUDFACTORY' } }),
    ]),
  );

  const items = await withSettings(
    { keywords: ['AI Trainer', 'Data Annotation'], locations: ['Egypt', 'Anywhere'], boards: [] },
    () => new AiDataSource(client).fetch(),
  );

  assert.equal(client.requests.length, 4);
  assert.deepEqual(
    items.map((item) => item.externalId),
    ['eg', 'ke'],
  );
  // The Egypt requisition, placed there by the Egypt search, is the one kept.
  assert.equal(items[0].market, 'Egypt');
  assert.equal(evaluate(items[0]).pass, true);
});

test('variantRank prefers placed, then remote, then unblocked; dedupKey is the store fingerprint', () => {
  const blocked = ['india'];
  const base = { title: 'AI Trainer', company: 'Mindrift' };
  assert.equal(variantRank({ ...base, market: 'Egypt', location: 'Cairo' }, blocked), 0);
  assert.equal(variantRank({ ...base, remote: true, location: 'Spain' }, blocked), 1);
  assert.equal(variantRank({ ...base, location: 'Cairo' }, blocked), 2);
  assert.equal(variantRank({ ...base, remote: true, location: 'Bangalore, India' }, blocked), 3);

  assert.equal(dedupKey(base), `fp:${fingerprintOf(base)}`);
  assert.equal(dedupKey({ ...base, company: 'MINDRIFT', title: 'ai trainer!' }), dedupKey(base));
  assert.notEqual(dedupKey({ ...base, company: 'Other' }), dedupKey(base));
  // No company: never collapsed on the title alone.
  assert.notEqual(
    dedupKey({ title: 'AI Trainer', url: 'https://a' }),
    dedupKey({ title: 'AI Trainer', url: 'https://b' }),
  );
});
