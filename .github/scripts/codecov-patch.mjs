/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { pathToFileURL } from 'node:url';

// Waits for Codecov's patch status on a pull request's head commit and
// requires it to pass (ci.yml's ci-gate, on a pull request whose run uploaded
// coverage):
//
//   HEAD_SHA=<sha> GITHUB_REPOSITORY=<owner/repo> GITHUB_RUN_ID=<id> \
//     GITHUB_TOKEN=<token> node .github/scripts/codecov-patch.mjs
//
// Codecov posts `codecov/patch` as a check run of its app on the head commit,
// once every coverage flag it reads has its after_n_builds sessions
// (codecov.yml). Carried-forward sessions count toward after_n_builds until a
// flag's first real upload, so Codecov may post an early patch status, judged
// on the base's carried-forward JVM coverage, as soon as another flag (the
// TypeScript packages') uploads; it posts again once the JVM shards have
// uploaded, unless the result is unchanged. The gate therefore weighs a run by
// whether Codecov started it after this workflow run's last coverage upload
// (the reference: the newest completion of an upload step, `UPLOAD_STEP`, in
// the run's latest attempt). The newest run started after it decides. Without
// one, the newest earlier run decides only if it passed: an early pass, or
// "Coverage not affected", is not the carried-forward failure, and Codecov
// does not post an unchanged result again; an early failure waits for a later
// run. A rerun of ci-gate alone keeps the upload jobs and their times, so it
// takes a run Codecov started after them, even one started before the rerun.
// The target (90%, threshold 1%) is the merge
// rule; "Coverage not affected" is a success. A run of another app with the
// same name is ignored. The status is a pull request's, so ci.yml waits for it
// only there.

export const CHECK = 'codecov/patch';
export const APP = 'codecov';
/** ci.yml's gate job, still running while it reads the run's jobs. */
export const GATE_JOB = 'ci-gate';
/** ci.yml's codecov/codecov-action steps (the test holds every one to it). */
export const UPLOAD_STEP = /^Upload\b.*\bcoverage$/;

/**
 * When the run's last coverage upload finished: the newest `completed_at` of
 * an upload step that ran, among the jobs other than the gate. Null when no
 * job uploaded.
 */
export function uploadsDoneAt(jobs) {
  const done = jobs
    .filter(job => job.name !== GATE_JOB)
    .flatMap(job => job.steps ?? [])
    .filter(
      step =>
        UPLOAD_STEP.test(step.name) &&
        step.status === 'completed' &&
        step.conclusion !== 'skipped' &&
        step.completed_at,
    )
    .map(step => Date.parse(step.completed_at));
  return done.length ? new Date(Math.max(...done)).toISOString() : null;
}

/**
 * What the check runs of the head commit say. The newest of Codecov's patch
 * runs started after `uploadsDone` decides: `pending` until it is completed,
 * then `passed` or `failed`. Without one, the newest run started at or before
 * `uploadsDone` (which may lack this run's uploads) passes if it passed, and
 * is `pending` otherwise.
 */
export function patchVerdict(runs, uploadsDone) {
  const codecov = runs
    .filter(run => run.name === CHECK && run.app?.slug === APP)
    .sort((a, b) => b.id - a.id);
  const isFresh = run => Date.parse(run.started_at) > Date.parse(uploadsDone);
  const newest = codecov.find(isFresh);
  if (!newest) {
    const stale = codecov[0];
    if (!stale) return { state: 'pending', message: `no ${CHECK} yet` };
    if (stale.status === 'completed' && stale.conclusion === 'success')
      return {
        state: 'passed',
        message: `${CHECK}: ${stale.output?.title ?? ''} (started before this run's uploads)`,
      };
    return {
      state: 'pending',
      message: `Codecov's ${CHECK} (${stale.conclusion ?? stale.status}) predates this run's uploads (done at ${uploadsDone})`,
    };
  }
  if (newest.status !== 'completed')
    return { state: 'pending', message: `${CHECK} is ${newest.status}` };
  const title = newest.output?.title ?? '';
  return newest.conclusion === 'success'
    ? { state: 'passed', message: `${CHECK}: ${title}` }
    : {
        state: 'failed',
        message: `${CHECK} is ${newest.conclusion}: ${title} (${newest.html_url ?? ''})`,
      };
}

/** Polls `fetchRuns` until the verdict is final or `timeoutMs` has passed. */
export async function waitForPatch({
  fetchRuns,
  uploadsDone,
  sleep,
  now = () => Date.now(),
  intervalMs = 20_000,
  timeoutMs = 15 * 60_000,
  log = () => {},
}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    const verdict = patchVerdict(await fetchRuns(), uploadsDone);
    if (verdict.state !== 'pending') return verdict;
    if (now() + intervalMs > deadline)
      return {
        state: 'failed',
        message: `Codecov's ${CHECK} with this run's uploads did not complete within ${timeoutMs / 60_000} minutes (${verdict.message}); rerun ci-gate once Codecov has reported`,
      };
    log(`${verdict.message}; waiting`);
    await sleep(intervalMs);
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { HEAD_SHA, GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_TOKEN } =
    process.env;
  if (
    !/^[0-9a-f]{40}$/.test(HEAD_SHA ?? '') ||
    !GITHUB_REPOSITORY ||
    !/^\d+$/.test(GITHUB_RUN_ID ?? '')
  )
    throw new Error(
      'HEAD_SHA, GITHUB_REPOSITORY and GITHUB_RUN_ID are required',
    );
  const api = async url => {
    const response = await fetch(url, {
      headers: {
        accept: 'application/vnd.github+json',
        authorization: `Bearer ${GITHUB_TOKEN}`,
        'x-github-api-version': '2022-11-28',
      },
    });
    if (!response.ok)
      throw new Error(`${url}: ${response.status} ${await response.text()}`);
    return response.json();
  };
  const jobs = [];
  for (let page = 1; ; page++) {
    const { total_count, jobs: batch } = await api(
      `https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}/jobs?filter=latest&per_page=100&page=${page}`,
    );
    jobs.push(...batch);
    if (!batch.length || jobs.length >= total_count) break;
  }
  const uploadsDone = uploadsDoneAt(jobs);
  if (!uploadsDone)
    throw new Error(`run ${GITHUB_RUN_ID} has no completed coverage upload`);
  console.log(`This run's coverage uploads were done at ${uploadsDone}`);
  const url = `https://api.github.com/repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}/check-runs?check_name=${encodeURIComponent(CHECK)}&filter=all&per_page=100`;
  const verdict = await waitForPatch({
    fetchRuns: async () => (await api(url)).check_runs,
    uploadsDone,
    sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
    log: message => console.log(message),
  });
  if (verdict.state !== 'passed') {
    console.error(`::error::${verdict.message}`);
    process.exit(1);
  }
  console.log(verdict.message);
}
