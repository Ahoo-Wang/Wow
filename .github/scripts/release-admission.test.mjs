/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  isBreaking,
  latestRun,
  previousReleaseTag,
  requireBreakingChangesInMinor,
  requireSuccessfulJob,
  requireSuccessfulRun,
} from './release-admission.mjs';

const run = {
  id: 1,
  head_sha: 'target',
  event: 'push',
  status: 'completed',
  conclusion: 'success',
};

test('the release commit needs its own latest push or dispatch run to succeed', () => {
  assert.doesNotThrow(() =>
    requireSuccessfulRun(latestRun([run], 'target'), 'target'),
  );
  assert.doesNotThrow(() =>
    requireSuccessfulRun(
      latestRun([{ ...run, event: 'workflow_dispatch' }], 'target'),
      'target',
    ),
  );
  for (const runs of [
    [],
    [{ ...run, head_sha: 'old' }],
    [{ ...run, event: 'pull_request' }],
    [run, { ...run, id: 2, conclusion: 'failure' }],
    [run, { ...run, id: 2, status: 'in_progress' }],
    [run, { ...run, id: 2, conclusion: 'cancelled' }],
  ])
    assert.throws(() =>
      requireSuccessfulRun(latestRun(runs, 'target'), 'target'),
    );
  // A pull request run for the same commit does not shadow the push run.
  assert.equal(
    latestRun([run, { ...run, id: 2, event: 'pull_request' }], 'target'),
    run,
  );
});

test('typescript-gate must have passed in that run', () => {
  const gate = {
    id: 1,
    name: 'typescript-gate',
    status: 'completed',
    conclusion: 'success',
  };
  assert.doesNotThrow(() =>
    requireSuccessfulJob([{ ...gate, id: 0, name: 'Quality' }, gate]),
  );
  for (const jobs of [
    [],
    [{ ...gate, name: 'Quality' }],
    [{ ...gate, conclusion: 'failure' }],
    [{ ...gate, conclusion: 'skipped' }],
    [gate, { ...gate, id: 2, status: 'in_progress' }],
  ])
    assert.throws(() => requireSuccessfulJob(jobs));
});

test('breaking commits are marked with ! or a BREAKING CHANGE footer', () => {
  for (const message of [
    'refactor!: unify storage batching and harden shutdown (#3248)',
    'feat(query)!: drop RAW',
    'fix: x\n\nBREAKING CHANGE: the default limit is 0',
    'fix: x\n\nBREAKING-CHANGE: renamed',
  ])
    assert.ok(isBreaking(message), message);
  for (const message of [
    'feat(query): add filters',
    'fix(deps) Update typescript-eslint to ^8.70.1 (#3279)',
    'Merge fetcher history of the Wow TypeScript packages',
    'docs: explain feat!: in prose',
    'feat: x\n\n* refactor!: a squashed sub-commit',
    'feat: x\n\nMentions BREAKING CHANGE: mid-line',
    'BREAKING CHANGE: in the subject only',
  ])
    assert.ok(!isBreaking(message), message);
});

test('the previous release is the highest x.y.z tag below the version', () => {
  const tags = [
    'v9.1.3',
    'v9.1.5',
    'vv5.22.3',
    'v9.2.0',
    'v9.1.6-rc.1',
    'v8.11.5',
    '9.1.4',
  ];
  assert.equal(previousReleaseTag(tags, '9.1.6'), 'v9.1.5');
  assert.equal(previousReleaseTag(tags, '9.2.0'), 'v9.1.5');
  assert.equal(previousReleaseTag(tags, '9.1.5'), 'v9.1.3');
  assert.equal(previousReleaseTag(tags, '9.2.1'), 'v9.2.0');
  assert.equal(previousReleaseTag(['vv5.22.3'], '9.1.6'), undefined);
});

test('a breaking commit forces an x.Y.0 release', () => {
  const plain = { sha: 'a'.repeat(40), message: 'fix(query): x' };
  const breaking = { sha: 'b'.repeat(40), message: 'refactor!: y (#3248)' };
  assert.deepEqual(requireBreakingChangesInMinor('9.1.6', [plain]), []);
  assert.deepEqual(requireBreakingChangesInMinor('9.2.0', [plain, breaking]), [
    breaking,
  ]);
  assert.doesNotThrow(() =>
    requireBreakingChangesInMinor('10.0.0', [breaking]),
  );
  assert.throws(
    () => requireBreakingChangesInMinor('9.1.6', [plain, breaking]),
    /ship only in x\.Y\.0; bump to 9\.2\.0:\n {2}bbbbbbbbb refactor!: y \(#3248\)/,
  );
});
