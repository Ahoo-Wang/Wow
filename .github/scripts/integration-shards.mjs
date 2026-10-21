/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Checks that ci.yml's integration shards run every integration test exactly
// once (ci.yml's scope job runs it):
//
//   node .github/scripts/integration-shards.mjs
//
// Every project of `integrationTestProjects` (build.gradle.kts) must be in
// the `projects` of exactly one shard of the `integration` job's matrix, and a
// shard may name no other project. A project in no shard would never run on a
// pull request; one in two would run twice. Codecov's after_n_builds
// (codecov.yml) must be the uploads of a full run: the `integration` flag's one
// per shard, the `jvm-unit` flag's the check's one, or Codecov would judge its
// statuses early or never.

/** The Gradle paths of `integrationTestProjects = includedProjects(...)`. */
export function gradleProjects(buildScript) {
  const list = /val integrationTestProjects = includedProjects\(([^)]*)\)/.exec(
    buildScript,
  );
  if (!list) {
    throw new Error('build.gradle.kts declares no integrationTestProjects');
  }
  return [...list[1].matchAll(/"(:[^"]+)"/g)].map(match => match[1]);
}

/** The shards of the `integration` job's matrix: `{ shard, projects }`. */
export function workflowShards(workflow) {
  const job = workflow.split(/^ {2}integration:$/m)[1]?.split(/^ {2}\S/m)[0];
  if (!job) {
    throw new Error('ci.yml has no integration job');
  }
  return [...job.matchAll(/- shard: (\S+)\n\s+projects: ([^\n]+)/g)].map(
    ([, shard, projects]) => ({
      shard,
      projects: projects.trim().split(/\s+/),
    }),
  );
}

/** The `after_n_builds` of the `flag` in codecov.yml, or undefined. */
export function codecovBuilds(codecov, flag) {
  const builds = new RegExp(
    `- name: ${flag}\\n\\s+after_n_builds: (\\d+)`,
  ).exec(codecov)?.[1];
  return builds === undefined ? undefined : Number(builds);
}

/** What is wrong with `shards` as a partition of `projects`; empty when nothing is. */
export function shardProblems(projects, shards) {
  const problems = [];
  if (shards.length === 0) {
    problems.push('the integration matrix has no shards');
  }
  const shardsOf = new Map(projects.map(project => [project, []]));
  for (const { shard, projects: shardProjects } of shards) {
    for (const project of shardProjects) {
      if (!shardsOf.has(project)) {
        problems.push(
          `${project} (shard ${shard}) is not in integrationTestProjects`,
        );
      } else {
        shardsOf.get(project).push(shard);
      }
    }
  }
  for (const [project, owners] of shardsOf) {
    if (owners.length === 0) {
      problems.push(`${project} is in no shard`);
    } else if (owners.length > 1) {
      problems.push(
        `${project} is in ${owners.length} shards: ${owners.join(', ')}`,
      );
    }
  }
  return problems;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const projects = gradleProjects(readFileSync('build.gradle.kts', 'utf8'));
  const shards = workflowShards(
    readFileSync('.github/workflows/ci.yml', 'utf8'),
  );
  const problems = shardProblems(projects, shards);
  const codecov = readFileSync('codecov.yml', 'utf8');
  const expected = { 'jvm-unit': 1, integration: shards.length };
  for (const [flag, builds] of Object.entries(expected)) {
    const actual = codecovBuilds(codecov, flag);
    if (actual !== builds) {
      problems.push(
        `codecov.yml: the ${flag} flag's after_n_builds is ${actual}, not ${builds}`,
      );
    }
  }
  for (const { shard, projects: shardProjects } of shards) {
    console.log(`${shard}: ${shardProjects.join(' ')}`);
  }
  if (problems.length > 0) {
    for (const problem of problems) {
      console.error(`::error file=.github/workflows/ci.yml::${problem}`);
    }
    process.exit(1);
  }
  console.log(
    `${projects.length} integration test projects, each in exactly one of ${shards.length} shards; Codecov waits for ${shards.length} integration uploads and 1 unit upload`,
  );
}
