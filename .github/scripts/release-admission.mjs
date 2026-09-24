/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import {
  compareVersions,
  parseVersion,
  readProjectVersion,
} from './project-version.mjs';

// Admits a release of the commit checked out at HEAD, the commit of a v* tag:
//
// 1. The commit is on main or on a maintenance branch (release-x.y), so a tag
//    cannot ship a commit that no pull request brought in.
// 2. Every workflow in REQUIRED has a successful full run on exactly this
//    commit, and its gate job passed in it. Only manual dispatch runs count:
//    a dispatch run has no base to diff against, so its scope job turns every
//    job on, whereas a push run tests only what that push changed. When the
//    commit has no dispatch run yet, admission dispatches one on the release
//    tag and waits for it.
// 3. Breaking changes ship only in x.Y.0: when any commit since the previous
//    v* tag is a breaking conventional commit, the release must be x.Y.0.
//
// The Gradle workflows are not in REQUIRED: the release workflow's preflight
// runs `./gradlew build allIntegrationTest` (unit, contract and integration
// tests) on this very commit before anything is published.

/** Workflows that must pass in full on the release commit, with their gate job. */
export const REQUIRED = [
  { workflow: 'typescript.yml', gate: 'typescript-gate' },
  { workflow: 'typescript-contract.yml', gate: 'typescript-contract-gate' },
  { workflow: 'typescript-storybook.yml', gate: 'typescript-storybook-gate' },
];

/** Remote branches a release commit may come from. */
const RELEASE_BRANCH = /^origin\/(?:main|release-\d+\.\d+)$/;

/** Some remote branch containing the commit is main or a release-x.y line. */
export function requireReleaseBranch(branches, sha) {
  assert.ok(
    branches.some(branch => RELEASE_BRANCH.test(branch.trim())),
    `${sha} is on neither main nor a release-x.y branch (found: ${branches.join(', ') || 'none'})`,
  );
}

/** The newest manual dispatch run of a workflow for exactly this commit. */
export function latestRun(runs, sha) {
  return runs
    .filter(run => run.head_sha === sha && run.event === 'workflow_dispatch')
    .sort((a, b) => b.id - a.id)[0];
}

export function requireSuccessfulRun(
  run,
  sha,
  workflow = REQUIRED[0].workflow,
) {
  assert.ok(
    run && run.status === 'completed' && run.conclusion === 'success',
    `${workflow}: the latest workflow_dispatch run for ${sha} must complete successfully` +
      (run
        ? ` (run ${run.html_url ?? run.id} is ${run.conclusion ?? run.status}; re-run its failed jobs, then re-run this release)`
        : ''),
  );
}

export function requireSuccessfulJob(jobs, name = REQUIRED[0].gate) {
  const latest = jobs
    .filter(job => job.name === name)
    .sort((a, b) => b.id - a.id)[0];
  assert.ok(
    latest && latest.status === 'completed' && latest.conclusion === 'success',
    `${name}: must pass in that run`,
  );
}

const BREAKING_SUBJECT = /^[a-z]+(?:\([^)]*\))?!:/i;
const BREAKING_FOOTER = /^BREAKING[ -]CHANGE:/m;

/** A conventional commit marked breaking by `!` or by a BREAKING CHANGE footer. */
export function isBreaking(message) {
  const [subject = '', ...body] = message.split('\n');
  return (
    BREAKING_SUBJECT.test(subject) || BREAKING_FOOTER.test(body.join('\n'))
  );
}

/** The highest v* tag below the release version, ignoring tags that are not x.y.z. */
export function previousReleaseTag(tags, version) {
  const release = parseVersion(version);
  return tags
    .map(tag => ({ tag, version: parseVersion(tag) }))
    .filter(
      ({ tag, version: parsed }) =>
        tag.startsWith('v') &&
        parsed &&
        parsed.prerelease === undefined &&
        compareVersions(parsed, release) < 0,
    )
    .sort((a, b) => compareVersions(b.version, a.version))[0]?.tag;
}

/**
 * Breaking commits may only ship in x.Y.0. Returns the offending commit
 * subjects so the failure names them.
 */
export function requireBreakingChangesInMinor(version, commits) {
  const breaking = commits.filter(commit => isBreaking(commit.message));
  const parsed = parseVersion(version);
  assert.ok(parsed, `not a version: ${version}`);
  assert.ok(
    breaking.length === 0 || parsed.patch === 0,
    `${version}: breaking changes since the previous release ship only in x.Y.0; bump to ${parsed.major}.${parsed.minor + 1}.0:\n${breaking
      .map(
        commit =>
          `  ${commit.sha.slice(0, 9)} ${commit.message.split('\n')[0]}`,
      )
      .join('\n')}`,
  );
  return breaking;
}

/** Commits in previous..HEAD, excluding merge commits themselves. */
function commitsSince(previous) {
  const log = execFileSync(
    'git',
    ['log', '--no-merges', '--format=%H%x00%B%x1e', `${previous}..HEAD`],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
  );
  return log
    .split('\x1e')
    .map(record => record.replace(/^\n/, ''))
    .filter(Boolean)
    .map(record => {
      const [sha, message] = record.split('\0');
      return { sha, message: message.trim() };
    });
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], {
    encoding: 'utf8',
  }).trim();
  const repo = process.env.GITHUB_REPOSITORY;
  // The ref a missing run is dispatched on: the release tag.
  const ref = process.env.ADMISSION_REF;
  assert.match(sha, /^[a-f0-9]{40}$/);
  assert.match(repo ?? '', /^[\w.-]+\/[\w.-]+$/);
  assert.match(ref ?? '', /^refs\/tags\/v/, 'ADMISSION_REF must be a v* tag');

  requireReleaseBranch(
    execFileSync(
      'git',
      [
        'branch',
        '--remotes',
        '--contains',
        'HEAD',
        '--format=%(refname:short)',
      ],
      { encoding: 'utf8' },
    )
      .split('\n')
      .filter(Boolean),
    sha,
  );
  console.log(`${sha} is on main or a release-x.y branch`);

  const pages = path =>
    JSON.parse(
      execFileSync('gh', ['api', '--paginate', '--slurp', path], {
        encoding: 'utf8',
        maxBuffer: 20 * 1024 * 1024,
      }),
    );
  const runOf = workflow =>
    latestRun(
      pages(
        `repos/${repo}/actions/workflows/${workflow}/runs?head_sha=${sha}&event=workflow_dispatch&per_page=100`,
      ).flatMap(page => page.workflow_runs),
      sha,
    );

  // Dispatch the full runs this commit lacks; a run that exists (a maintainer
  // dispatched it before tagging, or an earlier attempt of this release did)
  // is reused, whatever its state.
  for (const { workflow } of REQUIRED) {
    if (runOf(workflow)) continue;
    execFileSync(
      'gh',
      [
        'api',
        '--method',
        'POST',
        `repos/${repo}/actions/workflows/${workflow}/dispatches`,
        '-f',
        `ref=${ref}`,
      ],
      { stdio: 'inherit' },
    );
    console.log(`${workflow}: dispatched a full run on ${ref}`);
  }

  // A full contract and Storybook run takes a while: wait for all of them.
  const waitSeconds = Number(process.env.ADMISSION_WAIT_SECONDS ?? 5400);
  const deadline = Date.now() + waitSeconds * 1000;
  const runs = new Map();
  for (;;) {
    for (const { workflow } of REQUIRED) runs.set(workflow, runOf(workflow));
    const pending = REQUIRED.filter(
      ({ workflow }) => runs.get(workflow)?.status !== 'completed',
    );
    if (pending.length === 0 || Date.now() >= deadline) break;
    console.log(
      pending
        .map(({ workflow }) => {
          const run = runs.get(workflow);
          return `${workflow}: ${run ? `run ${run.id} is ${run.status}` : 'no run yet'}`;
        })
        .join('; ') + `; waiting for ${sha}`,
    );
    await new Promise(resolve => setTimeout(resolve, 30_000));
  }
  for (const { workflow, gate } of REQUIRED) {
    const run = runs.get(workflow);
    requireSuccessfulRun(run, sha, workflow);
    requireSuccessfulJob(
      pages(`repos/${repo}/actions/runs/${run.id}/jobs?per_page=100`).flatMap(
        page => page.jobs,
      ),
      gate,
    );
    console.log(`${workflow}: run ${run.id} and ${gate} passed for ${sha}`);
  }

  const version = readProjectVersion();
  const tags = execFileSync(
    'git',
    ['tag', '--merged', 'HEAD', '--list', 'v*'],
    {
      encoding: 'utf8',
    },
  )
    .split('\n')
    .filter(Boolean);
  const previous = previousReleaseTag(tags, version);
  if (previous) {
    const breaking = requireBreakingChangesInMinor(
      version,
      commitsSince(previous),
    );
    console.log(
      `${version}: ${breaking.length} breaking commit(s) since ${previous}`,
    );
  } else {
    console.log(`${version}: no earlier v* tag; breaking-change rule skipped`);
  }
  console.log(`Release admitted for ${sha}`);
}
