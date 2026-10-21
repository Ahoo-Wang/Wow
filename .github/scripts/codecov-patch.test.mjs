/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { patchVerdict, waitForPatch } from './codecov-patch.mjs';

const run = (id, status, conclusion, title = '', slug = 'codecov') => ({
  id,
  name: 'codecov/patch',
  app: { slug },
  status,
  conclusion,
  output: { title },
});

test("the newest of Codecov's patch runs decides", () => {
  assert.equal(patchVerdict([]).state, 'pending');
  assert.equal(patchVerdict([run(1, 'in_progress', null)]).state, 'pending');
  assert.equal(
    patchVerdict([run(1, 'completed', 'success', 'Coverage not affected')])
      .state,
    'passed',
  );
  assert.equal(
    patchVerdict([
      run(2, 'completed', 'failure', '85.00% of diff hit (target 90.00%)'),
      run(1, 'completed', 'success'),
    ]).state,
    'failed',
  );
  assert.equal(
    patchVerdict([
      run(1, 'completed', 'failure'),
      run(2, 'completed', 'success'),
    ]).state,
    'passed',
  );
  // Neutral, cancelled or skipped is not a pass.
  assert.equal(patchVerdict([run(1, 'completed', 'neutral')]).state, 'failed');
});

test('a run of another app with the same name is ignored', () => {
  assert.equal(
    patchVerdict([run(1, 'completed', 'success', '', 'github-actions')]).state,
    'pending',
  );
});

test('the wait polls until the verdict is final, and fails at its deadline', async () => {
  let clock = 0;
  const responses = [
    [],
    [run(1, 'in_progress', null)],
    [run(1, 'completed', 'success', 'Coverage not affected')],
  ];
  const passed = await waitForPatch({
    fetchRuns: async () => responses.shift(),
    sleep: async ms => {
      clock += ms;
    },
    now: () => clock,
  });
  assert.equal(passed.state, 'passed');
  assert.equal(clock, 40_000);

  clock = 0;
  const late = await waitForPatch({
    fetchRuns: async () => [],
    sleep: async ms => {
      clock += ms;
    },
    now: () => clock,
    intervalMs: 20_000,
    timeoutMs: 60_000,
  });
  assert.equal(late.state, 'failed');
  assert.match(late.message, /did not complete within 1 minutes/);
  assert.ok(clock <= 60_000);
});
