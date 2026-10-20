/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  codecovV10Builds,
  gradleProjects,
  shardProblems,
  workflowShards,
} from './v10-integration-shards.mjs';

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
  v10-gate:
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

test("the v10 flag's after_n_builds is read from codecov.yml", () => {
  assert.equal(
    codecovV10Builds(`  individual_flags:
    - name: v10
      after_n_builds: 5
      paths: []
`),
    5,
  );
  assert.equal(codecovV10Builds('flag_management: {}'), undefined);
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

test('v10.yml runs every V10 integration test project exactly once', () => {
  const projects = gradleProjects(readFileSync('build.gradle.kts', 'utf8'));
  const shards = workflowShards(
    readFileSync('.github/workflows/v10.yml', 'utf8'),
  );
  assert.deepEqual(shardProblems(projects, shards), []);
  assert.equal(
    codecovV10Builds(readFileSync('codecov.yml', 'utf8')),
    shards.length + 1,
  );
});
