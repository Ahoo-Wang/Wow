/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  codecovBuilds,
  gradleProjects,
  shardProblems,
  workflowShards,
} from './integration-shards.mjs';

const workflow = `jobs:
  check:
    steps: []
  integration:
    strategy:
      matrix:
        include:
          - shard: stores
            projects: :mongo :postgres
          - shard: apps
            projects: :eshop-e2e
    steps: []
  ci-gate:
    needs: [integration]
`;

test('the shards are read from the integration job of the workflow', () => {
  assert.deepEqual(workflowShards(workflow), [
    { shard: 'stores', projects: [':mongo', ':postgres'] },
    { shard: 'apps', projects: [':eshop-e2e'] },
  ]);
  assert.throws(
    () => workflowShards('jobs:\n  check:\n'),
    /no integration job/,
  );
});

test('the projects are read from build.gradle.kts', () => {
  assert.deepEqual(
    gradleProjects(`val integrationTestProjects = includedProjects(
    ":postgres",
    ":mongo",
)`),
    [':postgres', ':mongo'],
  );
  assert.throws(
    () => gradleProjects('val x = 1'),
    /no integrationTestProjects/,
  );
});

test("a flag's after_n_builds is read from codecov.yml", () => {
  const codecov = `  individual_flags:
    - name: jvm-unit
      after_n_builds: 1
    - name: integration
      after_n_builds: 4
`;
  assert.equal(codecovBuilds(codecov, 'jvm-unit'), 1);
  assert.equal(codecovBuilds(codecov, 'integration'), 4);
  assert.equal(codecovBuilds('flag_management: {}', 'jvm-unit'), undefined);
});

test('a project in no shard, in two, or unknown is a problem', () => {
  const projects = [':mongo', ':postgres', ':eshop-e2e'];
  assert.deepEqual(shardProblems(projects, workflowShards(workflow)), []);
  assert.deepEqual(
    shardProblems(projects, [
      { shard: 'a', projects: [':mongo', ':kafka'] },
      { shard: 'b', projects: [':mongo'] },
    ]),
    [
      ':kafka (shard a) is not in integrationTestProjects',
      ':mongo is in 2 shards: a, b',
      ':postgres is in no shard',
      ':eshop-e2e is in no shard',
    ],
  );
  assert.deepEqual(shardProblems([':mongo'], []), [
    'the integration matrix has no shards',
    ':mongo is in no shard',
  ]);
});

test('ci.yml runs every integration test project exactly once', () => {
  const projects = gradleProjects(readFileSync('build.gradle.kts', 'utf8'));
  const shards = workflowShards(
    readFileSync('.github/workflows/ci.yml', 'utf8'),
  );
  assert.deepEqual(shardProblems(projects, shards), []);
  const codecov = readFileSync('codecov.yml', 'utf8');
  assert.equal(codecovBuilds(codecov, 'integration'), shards.length);
  assert.equal(codecovBuilds(codecov, 'jvm-unit'), 1);
});
