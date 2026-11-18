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

/**
 * A registry stand-in: each URL answers 404 until its read `there[url]`, then
 * 200 (never, without one); `statuses[url]` answers that status at once.
 */
function registry({ there = {}, statuses = {} }) {
  const reads = {};
  const fetch = async url => {
    reads[url] = (reads[url] ?? 0) + 1;
    const status =
      statuses[url] ??
      (there[url] !== undefined && reads[url] >= there[url] ? 200 : 404);
    let cancelled = false;
    return {
      status,
      statusText:
        { 200: 'OK', 404: 'Not Found', 503: 'Service Unavailable' }[status] ??
        '',
      body: { cancel: async () => (cancelled = true) },
      get cancelled() {
        return cancelled;
      },
    };
  };
  return { fetch, reads };
}

test('tarballs that are all there are read once each, with no wait', async () => {
  const { fetch, reads } = registry({
    there: { [CLIENT[1]]: 1, [REACT[1]]: 1 },
  });
  const slept = [];
  const logs = [];
  await waitForTarballs([CLIENT, REACT], {
    fetch,
    sleep: async ms => slept.push(ms),
    log: line => logs.push(line),
  });
  assert.deepEqual(reads, { [CLIENT[1]]: 1, [REACT[1]]: 1 });
  assert.deepEqual(slept, []);
  assert.equal(
    logs.at(-1),
    'registry: serves the tarballs of @ahoo-wang/wow-client, @ahoo-wang/wow-react',
  );
});

test('a tarball that answers 404 is read again at the interval until it answers 200', async () => {
  const { fetch, reads } = registry({
    there: { [CLIENT[1]]: 1, [REACT[1]]: 4 },
  });
  const slept = [];
  await waitForTarballs([CLIENT, REACT], {
    intervalMs: 10_000,
    fetch,
    sleep: async ms => slept.push(ms),
    log: () => {},
  });
  // One already there is not read again.
  assert.deepEqual(reads, { [CLIENT[1]]: 1, [REACT[1]]: 4 });
  assert.deepEqual(slept, [10_000, 10_000, 10_000]);
});

test('a tarball never there fails at the bound, naming the package and how long it was waited for', async () => {
  const { fetch, reads } = registry({ there: { [CLIENT[1]]: 1 } });
  let sleeps = 0;
  await assert.rejects(
    waitForTarballs([CLIENT, REACT], {
      timeoutMs: 600_000,
      intervalMs: 10_000,
      fetch,
      sleep: async () => sleeps++,
      log: () => {},
    }),
    {
      message:
        'the registry still answers 404 for the tarballs of @ahoo-wang/wow-react after 600s (61 reads 10s apart)' +
        `\n  @ahoo-wang/wow-react: ${REACT[1]}`,
    },
  );
  // Ten minutes at ten seconds: the first read, then one every interval until the bound.
  assert.equal(reads[REACT[1]], 61);
  assert.equal(sleeps, 60);
});

test('an answer other than 200 or 404 is reported as it is, not waited on', async () => {
  const { fetch, reads } = registry({ statuses: { [CLIENT[1]]: 503 } });
  let sleeps = 0;
  await assert.rejects(
    waitForTarballs([CLIENT, REACT], {
      fetch,
      sleep: async () => sleeps++,
      log: () => {},
    }),
    {
      message: `the tarball of @ahoo-wang/wow-client, ${CLIENT[1]}, answered 503 Service Unavailable`,
    },
  );
  assert.equal(reads[CLIENT[1]], 1);
  assert.equal(sleeps, 0);
});

test('a read that fails is reported as it is, not waited on', async () => {
  let sleeps = 0;
  await assert.rejects(
    waitForTarballs([CLIENT], {
      fetch: async () => {
        throw new Error('getaddrinfo ENOTFOUND registry.npmjs.org');
      },
      sleep: async () => sleeps++,
      log: () => {},
    }),
    {
      message: `the tarball of @ahoo-wang/wow-client, ${CLIENT[1]}, could not be read: getaddrinfo ENOTFOUND registry.npmjs.org`,
    },
  );
  assert.equal(sleeps, 0);
});
