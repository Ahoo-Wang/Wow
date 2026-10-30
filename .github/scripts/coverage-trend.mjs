/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { appendFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import {
  AFTER_N_BUILDS,
  codecovClients,
  flagsComplete,
} from './codecov-report.mjs';

// Coverage must not drift down over time: the nightly's full ci.yml run on
// main (nightly.yml dispatches it, and a dispatch runs every scope) compares
// its commit's coverage, overall and per flag, with the nightly of a week
// earlier, and fails on a drop of more than half a point overall or one point
// on any flag (ci.yml's coverage-trend job):
//
//   HEAD_SHA=<sha> GITHUB_REPOSITORY=<owner/repo> GITHUB_TOKEN=<token> \
//     node .github/scripts/coverage-trend.mjs
//
// Pull requests judge only their changed lines (patch-coverage.mjs), so a
// slow loss across many of them shows here. Both totals come from Codecov,
// which holds every nightly's report; the week-old one is the newest
// successful dispatch of ci.yml on main at least seven days older. The
// nightly's own report is read once Codecov holds every flag's sessions
// (codecov-report.mjs), at most 20 minutes after its uploads. With no
// week-old nightly, or a flag the older report lacks, there is nothing to
// compare and the job says so.

export const OVERALL_DROP = 0.5;
export const FLAG_DROP = 1;
export const WEEK_MS = 7 * 24 * 60 * 60_000;

/**
 * The comparison of `now` with `then` (each `{ overall, flags: { flag: % } }`,
 * a missing total null).
 */
export function compareTotals({
  now,
  then,
  overallDrop = OVERALL_DROP,
  flagDrop = FLAG_DROP,
}) {
  const rows = [];
  const drops = [];
  const row = (name, current = null, previous = null, limit) => {
    if (current == null || previous == null) {
      rows.push({ name, current, previous, change: null });
      return;
    }
    const change = current - previous;
    rows.push({ name, current, previous, change });
    if (-change > limit)
      drops.push(
        `${name} ${previous.toFixed(2)}% → ${current.toFixed(2)}% (${change.toFixed(2)} points, more than ${limit})`,
      );
  };
  row('overall', now.overall, then.overall, overallDrop);
  for (const flag of Object.keys(now.flags))
    row(flag, now.flags[flag], then.flags[flag], flagDrop);
  return {
    state: drops.length ? 'failed' : 'passed',
    rows,
    message: drops.length
      ? `coverage dropped over the week: ${drops.join('; ')}`
      : 'no drop over the week beyond the limits',
  };
}

/** The newest successful run of `runs` at least a week before `at`, or null. */
export function weekOld(runs, at) {
  return (
    runs
      .filter(
        run =>
          run.conclusion === 'success' &&
          Date.parse(run.created_at) <= at - WEEK_MS,
      )
      .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at))[0] ??
    null
  );
}

/** Polls until `complete()` or the deadline; whether it completed. */
export async function waitForReport({
  complete,
  sleep,
  now = () => Date.now(),
  intervalMs = 30_000,
  timeoutMs = 20 * 60_000,
  log = () => {},
}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    if (await complete().catch(() => false)) return true;
    if (now() + intervalMs > deadline) return false;
    log("Codecov does not hold every flag's sessions yet; waiting");
    await sleep(intervalMs);
  }
}

/** The comparison as a Markdown table, for the job summary. */
export function table(result, head, base) {
  const format = value => (value == null ? '–' : `${value.toFixed(2)}%`);
  return `${[
    `### Coverage over the week: ${result.state}`,
    '',
    `${base.slice(0, 10)} (a week ago) → ${head.slice(0, 10)}`,
    '',
    '| | a week ago | now | change |',
    '|---|---|---|---|',
    ...result.rows.map(
      ({ name, current, previous, change }) =>
        `| ${name} | ${format(previous)} | ${format(current)} | ${change == null ? '–' : change.toFixed(2)} |`,
    ),
    '',
    result.message,
  ].join('\n')}\n`;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { HEAD_SHA, GITHUB_REPOSITORY, GITHUB_TOKEN, GITHUB_STEP_SUMMARY } =
    process.env;
  if (!/^[0-9a-f]{40}$/.test(HEAD_SHA ?? '') || !GITHUB_REPOSITORY)
    throw new Error('HEAD_SHA and GITHUB_REPOSITORY are required');
  const { api, uploadsOf, totalsOf } = codecovClients({
    repository: GITHUB_REPOSITORY,
    token: GITHUB_TOKEN,
  });
  const runs = (
    await api(
      `https://api.github.com/repos/${GITHUB_REPOSITORY}/actions/workflows/ci.yml/runs?branch=main&event=workflow_dispatch&status=completed&per_page=100`,
    )
  ).workflow_runs;
  const base = weekOld(runs, Date.now());
  if (!base) {
    console.log(
      '::notice::no successful nightly of a week ago to compare with',
    );
    process.exit(0);
  }
  const complete = await waitForReport({
    complete: async () => flagsComplete(await uploadsOf(HEAD_SHA)),
    sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
    log: message => console.log(message),
  });
  if (!complete) {
    console.error(
      `::error::Codecov does not hold every flag's sessions of ${HEAD_SHA} 20 minutes after the uploads; rerun the coverage-trend job once it does`,
    );
    process.exit(1);
  }
  const totals = async sha => ({
    overall: await totalsOf(sha),
    flags: Object.fromEntries(
      await Promise.all(
        Object.keys(AFTER_N_BUILDS).map(async flag => [
          flag,
          await totalsOf(sha, flag),
        ]),
      ),
    ),
  });
  const result = compareTotals({
    now: await totals(HEAD_SHA),
    then: await totals(base.head_sha),
  });
  const text = table(result, HEAD_SHA, base.head_sha);
  console.log(text);
  if (GITHUB_STEP_SUMMARY) appendFileSync(GITHUB_STEP_SUMMARY, text);
  if (result.state !== 'passed') {
    console.error(`::error::${result.message}`);
    process.exit(1);
  }
}
