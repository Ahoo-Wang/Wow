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

// Admits a release of the commit checked out at HEAD. Ported from fetcher's
// release-admission.mjs, keeping only what Wow needs:
//
// 1. The commit has a successful TypeScript workflow run (push or manual
//    dispatch, never a pull request run), and `typescript-gate` passed in it.
// 2. Breaking changes ship only in x.Y.0: when any commit since the previous
//    v* tag is a breaking conventional commit, the release must be x.Y.0.

export const WORKFLOW = 'typescript.yml';
export const GATE_JOB = 'typescript-gate';

/** The newest push or dispatch run of the workflow for exactly this commit. */
export function latestRun(runs, sha) {
  return runs
    .filter(
      run =>
        run.head_sha === sha &&
        ['push', 'workflow_dispatch'].includes(run.event),
    )
    .sort((a, b) => b.id - a.id)[0];
}

export function requireSuccessfulRun(run, sha, workflow = WORKFLOW) {
  assert.ok(
    run && run.status === 'completed' && run.conclusion === 'success',
    `${workflow}: latest push or workflow_dispatch run for ${sha} must complete successfully`,
  );
}

export function requireSuccessfulJob(jobs, name = GATE_JOB) {
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
  assert.match(sha, /^[a-f0-9]{40}$/);
  assert.match(repo ?? '', /^[\w.-]+\/[\w.-]+$/);
  const pages = path =>
    JSON.parse(
      execFileSync('gh', ['api', '--paginate', '--slurp', path], {
        encoding: 'utf8',
        maxBuffer: 20 * 1024 * 1024,
      }),
    );

  // A release is often created right after its commit lands, while the push
  // run is still queued or running: wait for it instead of failing the release.
  const waitSeconds = Number(process.env.ADMISSION_WAIT_SECONDS ?? 2400);
  const deadline = Date.now() + waitSeconds * 1000;
  let run;
  for (;;) {
    run = latestRun(
      pages(
        `repos/${repo}/actions/workflows/${WORKFLOW}/runs?head_sha=${sha}&per_page=100`,
      ).flatMap(page => page.workflow_runs),
      sha,
    );
    if (run?.status === 'completed' || Date.now() >= deadline) break;
    console.log(
      `${WORKFLOW}: ${run ? `run ${run.id} is ${run.status}` : 'no run yet'} for ${sha}; waiting`,
    );
    await new Promise(resolve => setTimeout(resolve, 30_000));
  }
  requireSuccessfulRun(run, sha);
  requireSuccessfulJob(
    pages(`repos/${repo}/actions/runs/${run.id}/jobs?per_page=100`).flatMap(
      page => page.jobs,
    ),
  );
  console.log(`${WORKFLOW}: run ${run.id} and ${GATE_JOB} passed for ${sha}`);

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
