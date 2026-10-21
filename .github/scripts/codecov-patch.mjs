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
//   HEAD_SHA=<sha> GITHUB_REPOSITORY=<owner/repo> GITHUB_TOKEN=<token> \
//     node .github/scripts/codecov-patch.mjs
//
// Codecov posts `codecov/patch` as a check run of its app on the head commit,
// once every coverage flag has its after_n_builds uploads (codecov.yml); a
// later notification posts it again, so the newest run decides. Its target
// (90%, threshold 1%) is the merge rule; "Coverage not affected" is a success.
// A run of another app with the same name is ignored. The status is a pull
// request's, so ci.yml waits for it only there.

export const CHECK = 'codecov/patch';
export const APP = 'codecov';

/**
 * What the check runs of the head commit say: `pending` until Codecov's
 * newest patch run is completed, then `passed` or `failed`.
 */
export function patchVerdict(runs) {
  const codecov = runs
    .filter(run => run.name === CHECK && run.app?.slug === APP)
    .sort((a, b) => b.id - a.id);
  const newest = codecov[0];
  if (!newest) return { state: 'pending', message: `no ${CHECK} yet` };
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
  sleep,
  now = () => Date.now(),
  intervalMs = 20_000,
  timeoutMs = 15 * 60_000,
  log = () => {},
}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    const verdict = patchVerdict(await fetchRuns());
    if (verdict.state !== 'pending') return verdict;
    if (now() + intervalMs > deadline)
      return {
        state: 'failed',
        message: `${CHECK} did not complete within ${timeoutMs / 60_000} minutes (${verdict.message}); rerun ci-gate once Codecov has reported`,
      };
    log(`${verdict.message}; waiting`);
    await sleep(intervalMs);
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { HEAD_SHA, GITHUB_REPOSITORY, GITHUB_TOKEN } = process.env;
  if (!/^[0-9a-f]{40}$/.test(HEAD_SHA ?? '') || !GITHUB_REPOSITORY)
    throw new Error('HEAD_SHA and GITHUB_REPOSITORY are required');
  const url = `https://api.github.com/repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}/check-runs?check_name=${encodeURIComponent(CHECK)}&filter=all&per_page=100`;
  const verdict = await waitForPatch({
    fetchRuns: async () => {
      const response = await fetch(url, {
        headers: {
          accept: 'application/vnd.github+json',
          authorization: `Bearer ${GITHUB_TOKEN}`,
          'x-github-api-version': '2022-11-28',
        },
      });
      if (!response.ok)
        throw new Error(`${url}: ${response.status} ${await response.text()}`);
      return (await response.json()).check_runs;
    },
    sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
    log: message => console.log(message),
  });
  if (verdict.state !== 'passed') {
    console.error(`::error::${verdict.message}`);
    process.exit(1);
  }
  console.log(verdict.message);
}
