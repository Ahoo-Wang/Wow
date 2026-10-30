/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  AFTER_N_BUILDS,
  flagSessions,
  flagsComplete,
} from './codecov-report.mjs';
import {
  codecovBuilds,
  workflowCheckShards,
  workflowShards,
} from './integration-shards.mjs';

const workflow = readFileSync(
  new URL('../workflows/ci.yml', import.meta.url),
  'utf8',
);
/** A job of ci.yml: from its key to the next job's. */
const job = name => {
  const start = workflow.indexOf(`\n  ${name}:\n`);
  assert.ok(start >= 0, name);
  const end = workflow.slice(start + 1).search(/\n {2}[a-z0-9-]+:\n/);
  return workflow.slice(start, end < 0 ? undefined : start + 1 + end);
};
const needs = name =>
  /\n {4}needs:\s*\[([^\]]*)\]/
    .exec(job(name))[1]
    .split(',')
    .map(need => need.trim())
    .filter(Boolean);

test('each TypeScript package uploads under its own flag alone, which the statuses read by name', () => {
  const codecov = readFileSync(
    new URL('../../codecov.yml', import.meta.url),
    'utf8',
  );
  // A shared flag on every upload would keep Codecov from carrying the other packages forward.
  const uploads = [...workflow.matchAll(/\n\s+flags: (.+)\n/g)].map(
    match => match[1],
  );
  assert.ok(uploads.length > 0);
  for (const flags of uploads) assert.doesNotMatch(flags, /,/, flags);
  // Every package's flag, as its component reads it, is in the patch and project statuses' lists.
  const packages = [...codecov.matchAll(/- "\^(typescript-[\w-]+)\$"/g)].map(
    match => match[1],
  );
  assert.deepEqual(packages.sort(), [
    'typescript-wow-client',
    'typescript-wow-generator',
    'typescript-wow-react',
    'typescript-wow-view-engine',
    'typescript-wow-view-store',
  ]);
  for (const status of ['patch', 'project']) {
    const block = new RegExp(`\\n {4}${status}:\\n(?: {6,}.*\\n)+`).exec(
      codecov,
    )[0];
    for (const flag of packages)
      assert.match(
        block,
        new RegExp(`- ${flag}\\n`),
        `${status} reads ${flag}`,
      );
    assert.doesNotMatch(
      block,
      /- typescript\n/,
      `${status} reads no shared typescript flag`,
    );
  }
  assert.match(codecov, /- name: typescript\n\s+carryforward: false\n/);
});

test("Codecov notifies once, when ci.yml's coverage job triggers it after every upload, and blocks nothing", () => {
  const codecov = readFileSync(
    new URL('../../codecov.yml', import.meta.url),
    'utf8',
  );
  assert.match(codecov, /\n {2}notify:\n {4}manual_trigger: true\n/);
  // Only the coverage job talks to Codecov; the test jobs keep their reports.
  const uploaders = [
    ...workflow.matchAll(
      /\n {2}([a-z0-9-]+):\n(?:(?!\n {2}[a-z0-9-]+:\n)[\s\S])*?name: coverage-/g,
    ),
  ].map(match => match[1]);
  assert.deepEqual(
    [
      ...workflow.matchAll(
        /\n {2}([a-z0-9-]+):\n(?:(?!\n {2}[a-z0-9-]+:\n)[\s\S])*?uses: codecov\/codecov-action@/g,
      ),
    ].map(match => match[1]),
    ['coverage'],
  );
  assert.deepEqual(needs('coverage').sort(), [...new Set(uploaders)].sort());
  const coverage = job('coverage');
  for (const uploader of new Set(uploaders))
    assert.match(
      coverage,
      new RegExp(`needs\\.${uploader}\\.result != 'skipped'`),
    );
  // One send, after every upload; no Codecov step can fail the job.
  const steps = coverage.split('\n      - ').slice(1);
  const codecovSteps = steps.filter(step =>
    step.includes('uses: codecov/codecov-action@'),
  );
  assert.equal(
    codecovSteps.filter(step => step.includes('send-notifications')).length,
    1,
  );
  assert.ok(codecovSteps.at(-1).includes('send-notifications'));
  for (const step of codecovSteps)
    assert.match(step, /fail_ci_if_error: false/);
  // Every status Codecov posts is informational.
  const statuses = [...codecov.matchAll(/\n( +)(target|type): /g)].length;
  assert.ok(statuses > 0);
  assert.equal(
    [...codecov.matchAll(/\n +informational: true\n/g)].length,
    3,
    'patch, project and the components',
  );
});

test('the coverage job uploads each check shard, integration shard and package as a session of its own', () => {
  const coverage = job('coverage');
  const sessions = [
    ...coverage.matchAll(
      /if: hashFiles\('coverage\/coverage-([\w-]+)\/[\w.-]+'\) != ''\n[\s\S]*?flags: ([\w-]+)\n\s+name: ([\w-]+)\n[\s\S]*?files: \.\/coverage\/coverage-([\w-]+)\//g,
    ),
  ].map(([, artifact, flag, name, file]) => {
    assert.equal(artifact, name);
    assert.equal(file, name);
    return { flag, name };
  });
  const expected = [
    ...workflowCheckShards(workflow).map(shard => ({
      flag: 'jvm-unit',
      name: `jvm-unit-${shard}`,
    })),
    ...workflowShards(workflow).map(({ shard }) => ({
      flag: 'integration',
      name: `integration-${shard}`,
    })),
    ...[
      'wow-client',
      'wow-react',
      'wow-generator',
      'wow-view-store',
      'wow-view-engine',
    ].map(name => ({ flag: `typescript-${name}`, name: `typescript-${name}` })),
  ];
  assert.deepEqual(sessions, expected);
  // Each test job keeps its report under the name the coverage job reads.
  for (const artifact of [
    'coverage-jvm-unit-${{ matrix.shard }}',
    'coverage-integration-${{ matrix.shard }}',
    'coverage-typescript-${{ matrix.package }}',
    'coverage-typescript-wow-view-engine',
  ])
    assert.ok(workflow.includes(`name: ${artifact}\n`), artifact);
});

// #5093's head, 2026-10-28: its main commit (96dadfe) was pushed while
// 715127c's integration shards were still uploading, so it carried forward 2
// of the 4; the head's own commits carried those 2 forward twice over.
const carried = (flag, name, times = 1) =>
  Array.from({ length: times }, (_, i) => ({
    flags: [flag],
    upload_type: 'carriedforward',
    name: `CF[${i + 1}] - ${name}`,
  }));
const uploaded = (flag, name) => ({
  flags: [flag],
  upload_type: 'uploaded',
  name,
});
const JVM_UNIT = [
  'jvm-unit-host',
  'jvm-unit-apps',
  'jvm-unit-tooling',
  'jvm-unit-rest',
];
const INTEGRATION = [
  'integration-mongo-postgres',
  'integration-kafka-elasticsearch-clickhouse',
  'integration-eshop',
  'integration-host-platform',
];
const PACKAGES = [
  'wow-client',
  'wow-generator',
  'wow-react',
  'wow-view-engine',
  'wow-view-store',
];
const TYPESCRIPT = uploaded(
  'typescript-wow-view-engine',
  'typescript-wow-view-engine',
);
// The other packages, carried forward beside the one this commit tested.
const CARRIED_PACKAGES = PACKAGES.filter(
  name => name !== 'wow-view-engine',
).flatMap(name => carried(`typescript-${name}`, `typescript-${name}`));
const GAP_HEAD = [
  ...JVM_UNIT.flatMap(name => carried('jvm-unit', name, 2)),
  ...INTEGRATION.slice(0, 2).flatMap(name => carried('integration', name, 2)),
  ...CARRIED_PACKAGES,
  TYPESCRIPT,
];
const COMPLETE_MAIN = [
  ...JVM_UNIT.map(name => uploaded('jvm-unit', name)),
  ...INTEGRATION.map(name => uploaded('integration', name)),
  ...PACKAGES.map(name => uploaded(`typescript-${name}`, `typescript-${name}`)),
];
test("the trend counts the flags codecov.yml's after_n_builds counts", () => {
  const codecov = readFileSync(
    new URL('../../codecov.yml', import.meta.url),
    'utf8',
  );
  for (const [flag, builds] of Object.entries(AFTER_N_BUILDS))
    assert.equal(codecovBuilds(codecov, flag), builds, flag);
});

test('a report is complete once each flag has its after_n_builds distinct sessions', () => {
  // The same two sessions carried forward twice are two sessions, not four.
  const gap = GAP_HEAD;
  assert.equal(flagSessions(gap).integration.carried.size, 2);
  assert.equal(flagsComplete(gap), false);
  assert.equal(flagsComplete(COMPLETE_MAIN), true);
  // A flag this commit uploads itself counts beside what it carried forward.
  assert.equal(
    flagsComplete([
      ...gap,
      ...INTEGRATION.slice(2).map(name => uploaded('integration', name)),
    ]),
    true,
  );
  // A TypeScript package no session carries is missing (ebbde37, 2026-10-29).
  assert.equal(
    flagsComplete(
      COMPLETE_MAIN.filter(
        u => !u.flags.includes('typescript-wow-view-engine'),
      ),
    ),
    false,
  );
});
