import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isNearDuplicate, postTokens, similarity } from '../src/postSimilarity.js';

const AD = `We're hiring a Laravel Developer to build our payments platform in Cairo.
Requirements: 2+ years of PHP and Laravel, MySQL, Redis, REST APIs, Git,
queues and unit testing. Nice to have: Vue.js, Docker, AWS.
Send your CV to careers@acme.io #hiring #laravel`;

test('same ad under another author, with different hashtags and link', () => {
  const repost = `${AD.replace('#hiring #laravel', '#jobs #egypt')}\nhttps://lnkd.in/abc123`;
  assert.ok(isNearDuplicate({ excerpt: AD }, { excerpt: repost }));
});

test('search snippet against the full body of the same post', () => {
  const snippet = "We're hiring a Laravel Developer to build our payments platform in Cairo. Requirements: 2+ years of PHP and Laravel, MySQL, Redis ...";
  assert.ok(isNearDuplicate({ excerpt: snippet }, { excerpt: AD }));
});

test('two different backend ads are not duplicates', () => {
  const other = `Hiring a Backend Developer (Node.js) for our logistics startup in Alexandria.
You will own microservices on NestJS, PostgreSQL and Kafka, with CI on GitHub Actions.
3+ years required. Apply at jobs@shipfast.com`;
  assert.equal(isNearDuplicate({ excerpt: AD }, { excerpt: other }), false);
});

test('an Arabic repost is caught', () => {
  const ar = `مطلوب مطور لارافيل للعمل في شركة برمجيات بالقاهرة
خبرة سنتين على الاقل في PHP و Laravel و MySQL
معرفة بـ Redis و Docker ميزة إضافية
الراتب مجزي وتأمين طبي وإجازات
ارسل السيرة الذاتية على hr@example.com`;
  const repost = `${ar}\n#وظائف #لارافيل`;
  assert.ok(isNearDuplicate({ excerpt: ar }, { excerpt: repost }));
});

test('too little text is never called a duplicate', () => {
  assert.equal(
    isNearDuplicate({ excerpt: 'Hiring Laravel developer Cairo' }, { excerpt: 'Hiring Laravel developer Cairo' }),
    false,
  );
});

test('same inbox and same role, reworded body', () => {
  const a = { title: 'Laravel Developer needed', excerpt: 'short', contactEmails: ['hr@acme.io'] };
  const b = { title: 'Hiring: Laravel Developer', excerpt: 'different', contactEmails: 'HR@acme.io, x@y.com' };
  assert.ok(isNearDuplicate(a, b));
});

test('same inbox, different role, is kept', () => {
  const a = { title: 'Laravel Developer', excerpt: 'short', contactEmails: ['hr@acme.io'] };
  const b = { title: 'Flutter Mobile Engineer', excerpt: 'other', contactEmails: ['hr@acme.io'] };
  assert.equal(isNearDuplicate(a, b), false);
});

test('tokens drop links, hashtags and mentions', () => {
  const tokens = postTokens('Laravel @john_doe #hiring https://x.com/y developer');
  assert.deepEqual([...tokens].sort(), ['developer', 'laravel']);
  assert.equal(similarity(new Set(), tokens), 0);
});
