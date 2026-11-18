/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { waitForTarballs } from './registry-wait.mjs';

const CLIENT = [
  '@ahoo-wang/wow-client',
  'https://registry.npmjs.org/@ahoo-wang/wow-client/-/wow-client-10.0.0-beta.10.tgz',
];
const REACT = [
  '@ahoo-wang/wow-react',
  'https://registry.npmjs.org/@ahoo-wang/wow-react/-/wow-react-10.0.0-beta.10.tgz',
];

const TEXTS = {
  200: 'OK',
  403: 'Forbidden',
  404: 'Not Found',
  503: 'Service Unavailable',
};

/**
 * A registry stand-in: each URL plays its list of answers in turn, a status or
 * an Error the read throws, and repeats the last one.
 */
function registry(answers) {
  const reads = {};
  const fetch = async url => {
    const read = (reads[url] = (reads[url] ?? 0) + 1);
    const plan = answers[url];
    const answer = plan[Math.min(read, plan.length) - 1];
    if (answer instanceof Error) throw answer;
    return {
      status: answer,
      statusText: TEXTS[answer] ?? '',
      body: { cancel: async () => {} },
    };
  };
  return { fetch, reads };
}

/** Waits for [tarballs] at ten minutes, ten seconds apart, counting the waits. */
async function wait(fetch, tarballs = [CLIENT, REACT]) {
  const slept = [];
  const logs = [];
  await waitForTarballs(tarballs, {
    timeoutMs: 600_000,
    intervalMs: 10_000,
    fetch,
    sleep: async ms => slept.push(ms),
    log: line => logs.push(line),
  });
  return { slept, logs };
}

test('tarballs that are all there are read once each, with no wait', async () => {
  const { fetch, reads } = registry({ [CLIENT[1]]: [200], [REACT[1]]: [200] });
  const { slept, logs } = await wait(fetch);
  assert.deepEqual(reads, { [CLIENT[1]]: 1, [REACT[1]]: 1 });
  assert.deepEqual(slept, []);
  assert.equal(
    logs.at(-1),
    'registry: serves the tarballs of @ahoo-wang/wow-client, @ahoo-wang/wow-react',
  );
});

test('a tarball that answers 404 is read again at the interval until it answers 200', async () => {
  const { fetch, reads } = registry({
    [CLIENT[1]]: [200],
    [REACT[1]]: [404, 404, 404, 200],
  });
  const { slept } = await wait(fetch);
  // One already there is not read again.
  assert.deepEqual(reads, { [CLIENT[1]]: 1, [REACT[1]]: 4 });
  assert.deepEqual(slept, [10_000, 10_000, 10_000]);
});

test('a 503 is waited on like a 404, until the tarball answers 200', async () => {
  const { fetch, reads } = registry({
    [CLIENT[1]]: [503, 200],
    [REACT[1]]: [200],
  });
  const { slept } = await wait(fetch);
  assert.equal(reads[CLIENT[1]], 2);
  assert.deepEqual(slept, [10_000]);
});

test('a read that fails is waited on, until the tarball answers 200', async () => {
  const { fetch, reads } = registry({
    [CLIENT[1]]: [new Error('read ECONNRESET'), 200],
    [REACT[1]]: [200],
  });
  const { slept } = await wait(fetch);
  assert.equal(reads[CLIENT[1]], 2);
  assert.deepEqual(slept, [10_000]);
});

test('tarballs never there fail at the bound, each named with its last answer or error', async () => {
  const { fetch, reads } = registry({
    [CLIENT[1]]: [404, 503],
    [REACT[1]]: [404, new Error('read ECONNRESET')],
  });
  let slept = 0;
  await assert.rejects(
    waitForTarballs([CLIENT, REACT], {
      timeoutMs: 600_000,
      intervalMs: 10_000,
      fetch,
      sleep: async () => slept++,
      log: () => {},
    }),
    {
      message:
        'the registry does not serve the tarballs of @ahoo-wang/wow-client, @ahoo-wang/wow-react after 600s (61 reads 10s apart)' +
        `\n  @ahoo-wang/wow-client: ${CLIENT[1]} answered 503 Service Unavailable` +
        `\n  @ahoo-wang/wow-react: ${REACT[1]} could not be read: read ECONNRESET`,
    },
  );
  // Ten minutes at ten seconds: the first read, then one every interval until the bound.
  assert.deepEqual(reads, { [CLIENT[1]]: 61, [REACT[1]]: 61 });
  assert.equal(slept, 60);
});

test('a 403, which no wait turns into a 200, is reported at once', async () => {
  const { fetch, reads } = registry({ [CLIENT[1]]: [403], [REACT[1]]: [200] });
  let slept = 0;
  await assert.rejects(
    waitForTarballs([CLIENT, REACT], {
      fetch,
      sleep: async () => slept++,
      log: () => {},
    }),
    {
      message: `the tarball of @ahoo-wang/wow-client, ${CLIENT[1]}, answered 403 Forbidden`,
    },
  );
  assert.equal(reads[CLIENT[1]], 1);
  assert.equal(slept, 0);
});
