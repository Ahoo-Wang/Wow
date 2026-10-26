/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  UPLOAD_STEP,
  patchVerdict,
  uploadsDoneAt,
  waitForPatch,
} from './codecov-patch.mjs';

// This run's uploads were done at 06:09:13 (PR #4906's timeline).
const UPLOADS_DONE = '2026-10-26T06:09:13Z';
const BEFORE = '2026-10-26T06:01:07Z';
const AFTER = '2026-10-26T06:09:21Z';

const run = (
  id,
  status,
  conclusion,
  title = '',
  slug = 'codecov',
  started_at = AFTER,
) => ({
  id,
  name: 'codecov/patch',
  app: { slug },
  status,
  conclusion,
  started_at,
  output: { title },
});
const stale = (id, status, conclusion, title = '') =>
  run(id, status, conclusion, title, 'codecov', BEFORE);

test("the newest of Codecov's patch runs decides", () => {
  const verdict = runs => patchVerdict(runs, UPLOADS_DONE).state;
  assert.equal(verdict([]), 'pending');
  assert.equal(verdict([run(1, 'in_progress', null)]), 'pending');
  assert.equal(
    verdict([run(1, 'completed', 'success', 'Coverage not affected')]),
    'passed',
  );
  assert.equal(
    verdict([
      run(2, 'completed', 'failure', '85.00% of diff hit (target 90.00%)'),
      run(1, 'completed', 'success'),
    ]),
    'failed',
  );
  assert.equal(
    verdict([run(1, 'completed', 'failure'), run(2, 'completed', 'success')]),
    'passed',
  );
  // A newer run still in progress decides over a completed older one.
  assert.equal(
    verdict([run(1, 'completed', 'success'), run(2, 'queued', null)]),
    'pending',
  );
  // Neutral, cancelled or skipped is not a pass.
  assert.equal(verdict([run(1, 'completed', 'neutral')]), 'failed');
});

test("a run Codecov started before this run's uploads were done never decides", () => {
  const early = patchVerdict(
    [stale(1, 'completed', 'failure', '40.00% of diff hit')],
    UPLOADS_DONE,
  );
  assert.equal(early.state, 'pending');
  assert.match(early.message, /predates this run's uploads/);
  // Nor does one started in the same second as the last upload's end.
  assert.equal(
    patchVerdict(
      [run(1, 'completed', 'success', '', 'codecov', UPLOADS_DONE)],
      UPLOADS_DONE,
    ).state,
    'pending',
  );
  assert.equal(
    patchVerdict(
      [
        stale(1, 'completed', 'failure', '40.00% of diff hit'),
        run(2, 'completed', 'success', '96.08% of diff hit'),
      ],
      UPLOADS_DONE,
    ).state,
    'passed',
  );
  const failed = patchVerdict(
    [
      stale(1, 'completed', 'success'),
      run(2, 'completed', 'failure', '85.00% of diff hit (target 90.00%)'),
    ],
    UPLOADS_DONE,
  );
  assert.equal(failed.state, 'failed');
  assert.match(failed.message, /85\.00% of diff hit/);
});

test('a run of another app with the same name is ignored', () => {
  assert.equal(
    patchVerdict(
      [run(1, 'completed', 'success', '', 'github-actions')],
      UPLOADS_DONE,
    ).state,
    'pending',
  );
});

test('the wait polls until the verdict is final, and fails at its deadline', async () => {
  let clock = 0;
  const sleep = async ms => {
    clock += ms;
  };
  const responses = [
    [],
    [run(1, 'in_progress', null)],
    [run(1, 'completed', 'success', 'Coverage not affected')],
  ];
  const passed = await waitForPatch({
    fetchRuns: async () => responses.shift(),
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
  });
  assert.equal(passed.state, 'passed');
  assert.equal(clock, 40_000);

  clock = 0;
  const late = await waitForPatch({
    fetchRuns: async () => [],
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
    intervalMs: 20_000,
    timeoutMs: 60_000,
  });
  assert.equal(late.state, 'failed');
  assert.match(late.message, /did not complete within 1 minutes/);
  assert.ok(clock <= 60_000);
});

test("the wait skips Codecov's early status for the one after the uploads", async () => {
  // PR #4906: a failure judged on carried-forward coverage, then the pass with
  // every upload in.
  let clock = 0;
  const sleep = async ms => {
    clock += ms;
  };
  const early = stale(1, 'completed', 'failure', '40.00% of diff hit');
  const responses = [
    [early],
    [early, run(2, 'in_progress', null)],
    [early, run(2, 'completed', 'success', '96.08% of diff hit')],
  ];
  const passed = await waitForPatch({
    fetchRuns: async () => responses.shift(),
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
  });
  assert.equal(passed.state, 'passed');
  assert.equal(clock, 40_000);

  clock = 0;
  const late = await waitForPatch({
    fetchRuns: async () => [early],
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
    intervalMs: 20_000,
    timeoutMs: 60_000,
  });
  assert.equal(late.state, 'failed');
  assert.match(late.message, /did not complete within 1 minutes/);
  assert.match(late.message, /predates this run's uploads/);
});

const step = (name, completed_at, conclusion = 'success') => ({
  name,
  status: 'completed',
  conclusion,
  completed_at,
});

test("the uploads were done when the run's last upload step finished", () => {
  const jobs = [
    {
      name: 'Check (host)',
      completed_at: '2026-10-26T06:05:30Z',
      steps: [
        step('Check the modules with coverage', '2026-10-26T06:04:40Z'),
        step('Upload unit test coverage', '2026-10-26T06:04:50Z'),
        step('Post Set up Gradle', '2026-10-26T06:05:29Z'),
      ],
    },
    {
      name: 'Integration Test (2)',
      completed_at: '2026-10-26T06:09:20Z',
      steps: [
        step('Upload integration test coverage', UPLOADS_DONE),
        step('Complete job', '2026-10-26T06:09:20Z'),
      ],
    },
    // A job that uploads nothing does not hold the gate back, however late.
    {
      name: 'Storybook / visual',
      completed_at: '2026-10-26T06:30:00Z',
      steps: [step('Compare the screenshots', '2026-10-26T06:29:50Z')],
    },
    // A skipped upload (Node 22's legs) uploaded nothing.
    {
      name: 'Unit (wow-client, Node 22)',
      completed_at: '2026-10-26T06:20:00Z',
      steps: [step('Upload coverage', '2026-10-26T06:20:00Z', 'skipped')],
    },
    // The gate itself is still running.
    {
      name: 'ci-gate',
      completed_at: null,
      steps: [step('Upload the gate coverage', '2026-10-26T06:40:00Z')],
    },
  ];
  assert.equal(uploadsDoneAt(jobs), '2026-10-26T06:09:13.000Z');
  assert.equal(uploadsDoneAt(jobs.slice(2)), null);
  assert.equal(uploadsDoneAt([]), null);
});

test('every Codecov upload step of ci.yml is named so the gate finds it', () => {
  const workflow = readFileSync(
    new URL('../workflows/ci.yml', import.meta.url),
    'utf8',
  );
  const names = [
    ...workflow.matchAll(
      /- name: (.+)\n(?:\s+if: .+\n)?\s+uses: codecov\/codecov-action@/g,
    ),
  ].map(match => match[1]);
  assert.equal(
    names.length,
    workflow.match(/uses: codecov\/codecov-action@/g).length,
  );
  assert.ok(names.length > 0);
  for (const name of names) assert.match(name, UPLOAD_STEP);
});
