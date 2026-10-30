/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  compareTotals,
  FLAG_DROP,
  OVERALL_DROP,
  table,
  waitForReport,
  WEEK_MS,
  weekOld,
} from './coverage-trend.mjs';

const THEN = {
  overall: 90,
  flags: {
    'jvm-unit': 86.47,
    integration: 82.37,
    'typescript-wow-client': 99.64,
  },
};

test('a week within the limits passes', () => {
  const result = compareTotals({
    now: {
      overall: 89.6,
      flags: {
        'jvm-unit': 85.5,
        integration: 83,
        'typescript-wow-client': 98.7,
      },
    },
    then: THEN,
  });
  assert.equal(result.state, 'passed');
  assert.equal(result.rows.length, 4);
  assert.equal(result.rows[0].change.toFixed(2), '-0.40');
});

test('more than half a point overall, or a point on a flag, fails and names it', () => {
  assert.equal(OVERALL_DROP, 0.5);
  assert.equal(FLAG_DROP, 1);
  const overall = compareTotals({
    now: { ...THEN, overall: 89.4 },
    then: THEN,
  });
  assert.equal(overall.state, 'failed');
  assert.match(
    overall.message,
    /overall 90\.00% → 89\.40% \(-0\.60 points, more than 0\.5\)/,
  );
  const flag = compareTotals({
    now: { overall: 90, flags: { ...THEN.flags, integration: 81.2 } },
    then: THEN,
  });
  assert.equal(flag.state, 'failed');
  assert.match(flag.message, /integration 82\.37% → 81\.20%/);
  assert.doesNotMatch(flag.message, /jvm-unit/);
});

test('a total either side lacks is shown and not compared', () => {
  const result = compareTotals({
    now: { overall: 90, flags: { ...THEN.flags, 'typescript-wow-react': 99 } },
    then: THEN,
  });
  assert.equal(result.state, 'passed');
  assert.deepEqual(result.rows.at(-1), {
    name: 'typescript-wow-react',
    current: 99,
    previous: null,
    change: null,
  });
  assert.match(
    table(result, 'b'.repeat(40), 'a'.repeat(40)),
    /\| typescript-wow-react \| – \| 99\.00% \| – \|/,
  );
});

test('the week-old nightly is the newest successful one at least seven days older', () => {
  const at = Date.parse('2026-11-06T19:00:00Z');
  const run = (day, conclusion = 'success') => ({
    created_at: `2026-10-${day}T18:31:00Z`,
    conclusion,
    head_sha: day,
  });
  assert.equal(
    weekOld([run('29'), run('31'), run('30', 'failure'), run('25')], at)
      .head_sha,
    '29',
  );
  assert.equal(weekOld([run('31')], at), null);
  assert.equal(WEEK_MS, 604_800_000);
});

test('the report is waited for, at most 20 minutes', async () => {
  let at = 0;
  const clock = {
    now: () => at,
    sleep: async ms => {
      at += ms;
    },
  };
  const answers = [false, false, true];
  assert.equal(
    await waitForReport({ complete: async () => answers.shift(), ...clock }),
    true,
  );
  assert.equal(at, 60_000);
  at = 0;
  assert.equal(
    await waitForReport({
      complete: async () => {
        throw new Error('502');
      },
      ...clock,
    }),
    false,
  );
  assert.ok(at <= 20 * 60_000);
});

test("ci.yml runs the trend on the nightly's full run on main only", () => {
  const workflow = readFileSync(
    new URL('../workflows/ci.yml', import.meta.url),
    'utf8',
  );
  const start = workflow.indexOf('\n  coverage-trend:\n');
  const job = workflow.slice(start, workflow.indexOf('\n  # ', start + 1));
  assert.match(job, /needs: coverage\n/);
  assert.match(
    job,
    /if: github\.event_name == 'workflow_dispatch' && github\.ref == 'refs\/heads\/main' && needs\.coverage\.result == 'success'/,
  );
  assert.match(job, /run: node \.github\/scripts\/coverage-trend\.mjs/);
  const nightly = readFileSync(
    new URL('../workflows/nightly.yml', import.meta.url),
    'utf8',
  );
  assert.match(nightly, /gh workflow run ci\.yml --ref main/);
});
