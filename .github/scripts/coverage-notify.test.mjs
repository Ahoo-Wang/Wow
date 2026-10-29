/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  codecovPosted,
  confirmNotified,
  waitForParent,
} from './coverage-notify.mjs';

const PARENT = 'a'.repeat(40);

/** A clock that `sleep` advances, so the waits run without waiting. */
function clock() {
  let at = 0;
  return {
    now: () => at,
    sleep: async ms => {
      at += ms;
    },
  };
}

const run = (status, conclusion = null) => ({
  status,
  conclusion,
  html_url: `https://x/runs/${status}`,
});

test("the uploads wait until the parent's run has finished", async () => {
  const time = clock();
  const states = [
    [run('in_progress')],
    [run('queued')],
    [run('completed', 'success')],
  ];
  const asked = [];
  const wait = await waitForParent({
    parent: PARENT,
    runsOf: async sha => {
      asked.push(sha);
      return states.shift();
    },
    ...time,
  });
  assert.equal(wait.state, 'complete');
  assert.deepEqual(asked, [PARENT, PARENT, PARENT]);
  assert.equal(time.now(), 60_000);
});

test('a cancelled, failed or missing parent run is nothing to wait for', async () => {
  for (const runs of [
    [run('completed', 'cancelled')],
    [run('completed', 'failure')],
    [],
  ]) {
    const time = clock();
    const wait = await waitForParent({
      parent: PARENT,
      runsOf: async () => runs,
      ...time,
    });
    assert.equal(wait.state, 'complete');
    assert.equal(time.now(), 0);
  }
});

test('the wait ends at 20 minutes and uploads anyway', async () => {
  const time = clock();
  const wait = await waitForParent({
    parent: PARENT,
    runsOf: async () => [run('completed', 'success'), run('in_progress')],
    ...time,
  });
  assert.equal(wait.state, 'timeout');
  assert.match(wait.message, /still in_progress after 20 minutes/);
  assert.match(wait.message, /uploading anyway/);
  assert.ok(time.now() <= 20 * 60_000);
});

test('runs that cannot be read do not hold the uploads', async () => {
  const wait = await waitForParent({
    parent: PARENT,
    runsOf: async () => {
      throw new Error('502');
    },
    ...clock(),
  });
  assert.equal(wait.state, 'unknown');
  assert.match(wait.message, /could not be read \(502\); uploading/);
});

test('a check run of the Codecov app or a codecov/ status is a notification', () => {
  assert.equal(codecovPosted([], []), false);
  assert.equal(
    codecovPosted([{ name: 'codecov/patch', app: { slug: 'other' } }], []),
    false,
  );
  assert.equal(
    codecovPosted([{ name: 'codecov/project', app: { slug: 'codecov' } }], []),
    true,
  );
  assert.equal(codecovPosted([], [{ context: 'codecov/patch' }]), true);
  assert.equal(codecovPosted([], [{ context: 'ci-gate' }]), false);
});

test('a notification that arrives within the window is posted', async () => {
  const time = clock();
  const answers = [false, false, true];
  const result = await confirmNotified({
    posted: async () => answers.shift(),
    gap: async () => null,
    ...time,
  });
  assert.deepEqual(result.verdict, 'posted');
  assert.equal(time.now(), 40_000);
});

test('nothing posted is pending, and silent after the last send', async () => {
  const pending = await confirmNotified({
    posted: async () => false,
    gap: async () => null,
    ...clock(),
  });
  assert.equal(pending.verdict, 'pending');
  assert.match(pending.message, /sending again/);
  const time = clock();
  const silent = await confirmNotified({
    posted: async () => false,
    gap: async () => null,
    last: true,
    ...time,
  });
  assert.equal(silent.verdict, 'silent');
  assert.match(silent.message, /each of 3 sends; rerun the Coverage job/);
  assert.ok(time.now() <= 3 * 60_000);
});

test('a carry-forward gap is named at once, and nothing is sent again', async () => {
  let polled = false;
  const result = await confirmNotified({
    posted: async () => {
      polled = true;
      return false;
    },
    gap: async () => ({
      state: 'failed',
      message:
        'Codecov carried forward too few sessions to x: integration (2 of 4).',
    }),
    ...clock(),
  });
  assert.equal(result.verdict, 'gap');
  assert.match(result.message, /integration \(2 of 4\)/);
  assert.equal(polled, false);
});
