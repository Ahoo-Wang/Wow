/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { APP, carryForwardGap, codecovClients } from './codecov-patch.mjs';

// The two waits of ci.yml's coverage job, which uploads every coverage report
// of a run and then triggers Codecov's notifications:
//
//   HEAD_SHA=<sha> GITHUB_REPOSITORY=<owner/repo> GITHUB_TOKEN=<token> \
//     node .github/scripts/coverage-notify.mjs parent
//   HEAD_SHA=<sha> HEAD_REF=<branch> GITHUB_REPOSITORY=<owner/repo> \
//     GITHUB_TOKEN=<token> GITHUB_OUTPUT=<file> \
//     node .github/scripts/coverage-notify.mjs confirm [--last]
//
// `parent`, before the uploads. Codecov builds a commit's report when its
// first upload lands and fixes the sessions it carries forward from the
// parent's report at that moment. A commit whose first upload lands while its
// parent's slowest upload is still running carries forward what was in, and
// every commit built on it inherits the hole (#5093, ebbde37, 2026-10-28/29).
// So the uploads wait until no ci.yml run of the first parent is queued or in
// progress, at most 20 minutes; a cancelled or failed parent has nothing
// left to upload and is no reason to wait. On the deadline the job uploads
// anyway with a warning, and `confirm` names any gap that follows.
//
// `confirm`, after each `send-notifications`. Codecov now and then accepts
// the trigger and posts nothing (#5172, 2026-10-29: posted once the job was
// rerun). It polls the commit for any Codecov check run or status for three
// minutes and writes the job output `verdict`: `posted`, `pending` (ci.yml
// sends the notifications again, twice at most) or, after the last send
// (`--last`), `silent`. A commit whose report carried forward too few
// sessions of a flag (codecov-patch.mjs) is never notified, whatever is sent:
// that is `gap`, with the merge that repairs it in the output `message`, and
// no resend. The job passes either way; ci-gate reads the verdict.

/** The run states that still upload: GitHub's queued, waiting and running states. */
const ACTIVE = new Set([
  'queued',
  'requested',
  'waiting',
  'pending',
  'in_progress',
]);

/**
 * Waits until no run of `parent` is active. `runsOf(sha)` lists the ci.yml
 * runs of a commit. A run that cannot be read is no reason to block the
 * uploads: the wait ends and says so.
 */
export async function waitForParent({
  parent,
  runsOf,
  sleep,
  now = () => Date.now(),
  intervalMs = 30_000,
  timeoutMs = 20 * 60_000,
  log = () => {},
}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    let active;
    try {
      active = (await runsOf(parent)).filter(run => ACTIVE.has(run.status));
    } catch (error) {
      return {
        state: 'unknown',
        message: `the runs of ${parent} could not be read (${error.message}); uploading`,
      };
    }
    if (!active.length)
      return {
        state: 'complete',
        message: `no ci.yml run of ${parent} is still uploading`,
      };
    const urls = active.map(run => run.html_url).join(', ');
    if (now() + intervalMs > deadline)
      return {
        state: 'timeout',
        message: `${parent}'s run is still ${active[0].status} after ${timeoutMs / 60_000} minutes (${urls}); uploading anyway, and this commit may carry forward a partial report`,
      };
    log(`${parent}'s run is ${active[0].status} (${urls}); waiting`);
    await sleep(intervalMs);
  }
}

/** Whether Codecov posted anything on the commit: a check run of its app or a `codecov/` status. */
export function codecovPosted(checkRuns, statuses) {
  return (
    checkRuns.some(run => run.app?.slug === APP) ||
    statuses.some(status => status.context?.startsWith('codecov/'))
  );
}

/**
 * Polls for Codecov's notifications. `posted()` says whether any arrived,
 * `gap()` is codecov-patch.mjs's carry-forward diagnosis (null when there is
 * none, or it cannot be made). The verdict after the window is `pending`, or
 * `silent` after the last send.
 */
export async function confirmNotified({
  posted,
  gap,
  last = false,
  sleep,
  now = () => Date.now(),
  intervalMs = 20_000,
  windowMs = 3 * 60_000,
  log = () => {},
}) {
  const stuck = await gap();
  if (stuck) return { verdict: 'gap', message: stuck.message };
  const deadline = now() + windowMs;
  for (;;) {
    if (await posted())
      return { verdict: 'posted', message: 'Codecov posted its statuses' };
    if (now() + intervalMs > deadline)
      return last
        ? {
            verdict: 'silent',
            message: `Codecov posted nothing within ${windowMs / 60_000} minutes of each of 3 sends; rerun the Coverage job`,
          }
        : {
            verdict: 'pending',
            message: `Codecov posted nothing within ${windowMs / 60_000} minutes; sending again`,
          };
    log('no Codecov status yet; waiting');
    await sleep(intervalMs);
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const [command, flag] = process.argv.slice(2);
  const { HEAD_SHA, HEAD_REF, GITHUB_REPOSITORY, GITHUB_TOKEN, GITHUB_OUTPUT } =
    process.env;
  if (!/^[0-9a-f]{40}$/.test(HEAD_SHA ?? '') || !GITHUB_REPOSITORY)
    throw new Error('HEAD_SHA and GITHUB_REPOSITORY are required');
  const { api, uploadsOf, mainCommits } = codecovClients({
    repository: GITHUB_REPOSITORY,
    token: GITHUB_TOKEN,
  });
  const repo = `https://api.github.com/repos/${GITHUB_REPOSITORY}`;
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const log = message => console.log(message);
  if (command === 'parent') {
    const parent = (await api(`${repo}/commits/${HEAD_SHA}`)).parents[0]?.sha;
    if (!parent) {
      log(`${HEAD_SHA} has no parent`);
    } else {
      const wait = await waitForParent({
        parent,
        runsOf: async sha =>
          (
            await api(
              `${repo}/actions/workflows/ci.yml/runs?head_sha=${sha}&per_page=50`,
            )
          ).workflow_runs,
        sleep,
        log,
      });
      console.log(
        wait.state === 'complete' ? wait.message : `::warning::${wait.message}`,
      );
    }
  } else if (command === 'confirm') {
    const result = await confirmNotified({
      last: flag === '--last',
      posted: async () =>
        codecovPosted(
          (
            await api(
              `${repo}/commits/${HEAD_SHA}/check-runs?filter=all&per_page=100`,
            )
          ).check_runs,
          await api(`${repo}/commits/${HEAD_SHA}/statuses?per_page=100`),
        ),
      gap: () =>
        carryForwardGap({
          head: HEAD_SHA,
          branch: HEAD_REF || '<branch>',
          uploadsOf,
          mainCommits,
          log,
        }),
      sleep,
      log,
    });
    console.log(
      result.verdict === 'posted'
        ? result.message
        : `::warning::${result.message}`,
    );
    appendFileSync(
      GITHUB_OUTPUT,
      `verdict=${result.verdict}\nmessage=${result.message.replace(/\n/g, ' ')}\n`,
    );
  } else {
    throw new Error('usage: coverage-notify.mjs parent | confirm [--last]');
  }
}
