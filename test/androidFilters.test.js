import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate } from '../src/filters.js';
import { replaceMutes, muteKey, muteReason, titleWords } from '../src/mutes.js';
import { fitsFreelance, freelanceStack, budget } from '../src/sources/freelance.js';

// The rules the Android app added to the shared filters (its FiltersTest, MuteTest, FreelanceTest).

const job = (title, extra = {}) => ({
  source: 'wuzzuf',
  externalId: title,
  itemType: 'job',
  title,
  url: 'https://wuzzuf.net/jobs/p/1',
  company: 'Acme',
  location: 'Cairo, Egypt',
  market: 'Egypt',
  excerpt: 'We build APIs with Laravel and PHP.',
  ...extra,
});

const reason = (item) => evaluate(item).reason;

test('a level range is open to its lower level, but a lead is still a lead', () => {
  assert.equal(reason(job('Middle / Senior Full Stack PHP Developer')), null);
  assert.equal(reason(job('Mid-Senior Laravel Developer')), null);
  assert.equal(reason(job('Senior/Mid Backend Developer')), null);
  assert.equal(reason(job('Junior to Senior PHP Developer')), null);
  assert.equal(
    reason(job('Middle/Senior PHP Developer', { excerpt: 'We hire a Middle/Senior PHP developer for Laravel APIs.' })),
    null,
  );
  assert.equal(reason(job('Mid/Senior PHP Tech Lead')), 'seniority-excluded');
  assert.equal(reason(job('Senior Middleware PHP Developer')), 'seniority-excluded');
});

test('Gulf residency, iqama and nationality wording means no', () => {
  const riyadh = (excerpt) => reason(job('PHP Developer', { location: 'Riyadh', market: 'Saudi Arabia', excerpt }));
  assert.equal(riyadh('Laravel role. Candidates must be currently in KSA.'), 'ineligible');
  assert.equal(riyadh('Laravel role. Transferable Iqama is a must.'), 'ineligible');
  assert.equal(riyadh('Laravel role. Saudization position, Saudis only.'), 'ineligible');
  assert.equal(riyadh('Laravel role. GCC nationals only.'), 'ineligible');
  assert.equal(riyadh('Laravel وظيفة للسعوديين فقط'), 'ineligible');
  assert.equal(riyadh('Laravel role. Must be based in Riyadh.'), 'ineligible');
  assert.equal(riyadh('Laravel role. Iqama is required.'), 'ineligible');
  assert.equal(riyadh('Laravel role. Candidates must be locally available.'), 'ineligible');
  assert.equal(riyadh('Laravel role. Residence visa and iqama transfer fees covered.'), null);
  assert.equal(riyadh('Laravel role. Open to all nationalities; relocation package provided.'), null);
});

test('a residency phrase pointing at Egypt is where the person is', () => {
  assert.equal(reason(job('PHP Developer', { excerpt: 'Laravel role. Must be based in Cairo.' })), null);
  assert.equal(reason(job('PHP Developer', { excerpt: 'Laravel role. Candidates currently residing in Egypt.' })), null);
  assert.equal(reason(job('PHP Developer', { excerpt: 'Laravel role, Egypt team. Must be based in Dubai.' })), 'ineligible');
});

test('a Gulf job has to say it takes someone relocating from Egypt', () => {
  const gulf = (excerpt) =>
    reason(job('Backend Developer', { location: 'Riyadh, Saudi Arabia', market: 'Saudi Arabia', excerpt }));
  assert.equal(gulf('Laravel and PHP backend role in Riyadh.'), 'gulf-local-only');
  assert.equal(gulf('Laravel and PHP role. Relocation package provided.'), null);
  assert.equal(gulf('Laravel and PHP role. Visa sponsorship for the right candidate.'), null);
  assert.equal(gulf('Laravel and PHP role. Open to all nationalities.'), null);
  assert.equal(gulf('Laravel role. استقدام من الخارج ونوفر الإقامة.'), null);
  assert.equal(reason(job('Backend Developer', { excerpt: 'Laravel and PHP role in Cairo.' })), null);
});

test('mutes: a company, a whole word', () => {
  try {
    replaceMutes([{ kind: 'company', value: muteKey('ACME Inc.'), label: 'ACME Inc.' }]);
    assert.equal(muteReason(job('Backend Developer', { company: 'Acme, Inc' })), 'muted-company');
    assert.equal(reason(job('Anything at all', { company: 'acme inc' })), 'muted-company');

    replaceMutes([{ kind: 'word', value: 'wordpress', label: 'WordPress' }]);
    assert.equal(muteReason(job('WordPress PHP Developer')), 'muted-title');
    assert.equal(muteReason(job('Laravel Developer')), null);

    replaceMutes([{ kind: 'word', value: 'go', label: 'go' }]);
    assert.equal(muteReason(job('Google Cloud Engineer')), null);
  } finally {
    replaceMutes([]);
  }

  assert.deepEqual(titleWords('Senior WordPress Developer (Remote)'), ['wordpress']);
});

test('freelance: the stack in English or Arabic', () => {
  assert.deepEqual(freelanceStack('مطلوب مبرمج لارافيل لبناء متجر'), ['Laravel']);
  assert.ok(fitsFreelance('تطوير الباك اند لتطبيق'));
  assert.ok(!fitsFreelance('نود أن نصمم شعار جودة عالية'));
  assert.equal(budget('$50.00 - $100.00'), '$50–100');
  assert.equal(budget('$250'), '$250');
  assert.equal(budget(null), null);
});
