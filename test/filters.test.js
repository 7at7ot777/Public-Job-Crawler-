import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate, filtersFor, hiringIntent } from '../src/filters.js';
import config from '../src/config.js';

const post = (excerpt) => ({
  source: 'linkedin_posts',
  itemType: 'post',
  title: excerpt.split('\n')[0],
  excerpt,
  location: 'Egypt',
  market: 'Egypt',
});

// A real post that was dropped as "intent unclear": its only employer phrase
// was written with a curly apostrophe.
const ALEXANDRIA = `We’re looking for talented professionals to join our growing team across the following positions:
• Flutter Developer
• Laravel Developer
• WordPress Developer
• QC Engineer
• Scrum Master
• DevOps Engineer
Experience: 3–5 years
Location: Alexandria, Egypt
If you’re looking for your next career opportunity and believe your experience matches one of these roles, we’d be happy to hear from you.
📩 Send your updated CV to: hiring@objects.ws
Please mention the position title in the email subject.`;

test('a hiring post written with curly apostrophes is sent', () => {
  const item = post(ALEXANDRIA);

  assert.equal(hiringIntent(item), 'hiring');
  assert.equal(evaluate(item).pass, true);
});

test('curly apostrophes count on both sides', () => {
  assert.equal(hiringIntent(post('We’re hiring a Laravel developer in Cairo')), 'hiring');
  assert.equal(hiringIntent(post('I’m looking for a Laravel role in Cairo')), 'seeking');
});

test('a hiring mailbox is an employer signal, a personal address is not', () => {
  assert.equal(hiringIntent(post('Laravel Developer, Cairo. CVs: careers@acme.io')), 'hiring');
  assert.equal(hiringIntent(post('Laravel Developer, Cairo. Reach me: ahmed.hr@gmail.com')), 'unclear');
});

test('a full stack hiring post is kept, though no other stack word is named', () => {
  const item = post('We are hiring a Full-Stack Developer (React, .NET) in Cairo. Send your CV to careers@acme.io');

  assert.equal(evaluate(item).pass, true);
  assert.equal(evaluate({ ...item, excerpt: item.excerpt.replace('Full-Stack', 'Fullstack') }).pass, true);
});

test('full stack is a tech word for posts only, not for job sources', () => {
  assert.ok(filtersFor('linkedin_posts').tech.includes('full stack'));
  assert.ok(filtersFor('x_session').tech.includes('fullstack'));
  assert.ok(!filtersFor('naukrigulf').tech.includes('full stack'));
});

/*
 * The general roles are only wanted from anywhere. FILTER_REMOTE_ONLY is per
 * source and every source carries stack roles too, so the rule is scoped to
 * the titles instead: these pin both halves of it.
 */
test('a general role is kept only when it is remote', () => {
  const general = (title, extra = {}) => ({
    source: 'wuzzuf',
    itemType: 'job',
    title,
    company: 'Acme',
    location: 'Cairo, Egypt',
    excerpt: `${title} for our operations team. Shift work, full time.`,
    ...extra,
  });

  // On-site, so the role is not worth having.
  assert.equal(evaluate(general('Technical Support Specialist')).reason, 'not-remote');
  assert.equal(evaluate(general('Data Annotator')).reason, 'not-remote');
  assert.equal(evaluate(general('Administrative Assistant')).reason, 'not-remote');
  assert.equal(evaluate(general('Content Moderator')).reason, 'not-remote');
  assert.equal(evaluate(general('Transcriptionist')).reason, 'not-remote');

  // The posting's own words settle it.
  assert.equal(
    evaluate(
      general('Content Moderator', {
        excerpt: 'Content Moderator, fully remote, work from home.',
      }),
    ).pass,
    true,
  );

  // Or the search did: f_WT=2 sets item.remote outright.
  assert.equal(evaluate(general('Transcriptionist', { remote: true })).pass, true);

  // A hybrid role is on-site work with fewer days.
  assert.equal(
    evaluate(
      general('Administrative Assistant', {
        excerpt: 'Administrative Assistant, hybrid remote, three days in our Cairo office.',
      }),
    ).reason,
    'not-remote',
  );
});

test('a stack role is exempt from the remote-title rule', () => {
  assert.equal(
    evaluate({
      source: 'wuzzuf',
      itemType: 'job',
      title: 'Laravel Developer',
      company: 'Acme',
      location: 'Cairo, Egypt',
      excerpt: 'Laravel Developer, on-site in our Cairo office. PHP, MySQL.',
    }).pass,
    true,
  );
});

// A keyword that clears neither gate is a wasted request every pass, which is
// how 'project manager' and 'product owner' were found to deliver nothing.
test('every remote keyword can reach delivery through both gates', () => {
  const { tech, titleTerms } = filtersFor('linkedin_jobs');

  for (const keyword of config.remoteKeywords) {
    const term = keyword.toLowerCase();
    assert.ok(tech.includes(term), `${keyword} missing from FILTER_TECH`);
    assert.ok(titleTerms.includes(term), `${keyword} missing from FILTER_TITLE`);
  }
});
