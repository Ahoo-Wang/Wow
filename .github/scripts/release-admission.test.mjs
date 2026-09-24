/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  isBreaking,
  REQUIRED,
  latestRun,
  previousReleaseTag,
  requireBreakingChangesInMinor,
  requireReleaseBranch,
  requireSuccessfulJob,
  requireSuccessfulRun,
} from './release-admission.mjs';

const run = {
  id: 1,
  head_sha: 'target',
  event: 'workflow_dispatch',
  status: 'completed',
  conclusion: 'success',
};

test('the release commit needs its own latest full (dispatch) run to succeed', () => {
  assert.doesNotThrow(() =>
    requireSuccessfulRun(latestRun([run], 'target'), 'target'),
  );
  for (const runs of [
    [],
    [{ ...run, head_sha: 'old' }],
    // Push runs test only what the push changed; pull request runs are not
    // the release commit.
    [{ ...run, event: 'push' }],
    [{ ...run, event: 'pull_request' }],
    [run, { ...run, id: 2, conclusion: 'failure' }],
    [run, { ...run, id: 2, status: 'in_progress' }],
    [run, { ...run, id: 2, conclusion: 'cancelled' }],
  ])
    assert.throws(() =>
      requireSuccessfulRun(latestRun(runs, 'target'), 'target'),
    );
  // Newer push or pull request runs for the same commit do not shadow it.
  assert.equal(
    latestRun(
      [
        run,
        { ...run, id: 2, event: 'pull_request', conclusion: 'failure' },
        { ...run, id: 3, event: 'push', conclusion: 'failure' },
      ],
      'target',
    ),
    run,
  );
  assert.throws(
    () =>
      requireSuccessfulRun(
        { ...run, conclusion: 'failure', html_url: 'https://x/runs/1' },
        'target',
        'typescript-contract.yml',
      ),
    /typescript-contract.yml: .*run https:\/\/x\/runs\/1 is failure; re-run its failed jobs/,
  );
});

test('TypeScript, contract and Storybook gates are all required', () => {
  assert.deepEqual(REQUIRED, [
    { workflow: 'typescript.yml', gate: 'typescript-gate' },
    { workflow: 'typescript-contract.yml', gate: 'typescript-contract-gate' },
    { workflow: 'typescript-storybook.yml', gate: 'typescript-storybook-gate' },
  ]);
});

test('each gate must have passed in its run', () => {
  const gate = {
    id: 1,
    name: 'typescript-gate',
    status: 'completed',
    conclusion: 'success',
  };
  assert.doesNotThrow(() =>
    requireSuccessfulJob([{ ...gate, id: 0, name: 'Quality' }, gate]),
  );
  assert.doesNotThrow(() =>
    requireSuccessfulJob(
      [{ ...gate, name: 'typescript-storybook-gate' }],
      'typescript-storybook-gate',
    ),
  );
  for (const jobs of [
    [],
    [{ ...gate, name: 'Quality' }],
    [{ ...gate, conclusion: 'failure' }],
    [{ ...gate, conclusion: 'skipped' }],
    [gate, { ...gate, id: 2, status: 'in_progress' }],
  ])
    assert.throws(() => requireSuccessfulJob(jobs));
  assert.throws(() => requireSuccessfulJob([gate], 'typescript-contract-gate'));
});

test('a release commit comes from main or a release-x.y branch', () => {
  for (const branches of [
    ['origin/main'],
    ['origin/feature', 'origin/release-9.1'],
    ['  origin/main'],
  ])
    assert.doesNotThrow(() => requireReleaseBranch(branches, 'target'));
  for (const branches of [
    [],
    ['origin/feature'],
    ['origin/main-copy'],
    ['origin/release-9'],
    ['fork/main'],
  ])
    assert.throws(
      () => requireReleaseBranch(branches, 'target'),
      /target is on neither main nor a release-x.y branch/,
    );
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
