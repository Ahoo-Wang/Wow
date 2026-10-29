/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { pathToFileURL } from 'node:url';

// Waits for Codecov's patch status on a pull request's head commit and
// requires it to pass (ci.yml's ci-gate, on a pull request whose run uploaded
// coverage and triggered Codecov's notifications):
//
//   HEAD_SHA=<sha> GITHUB_REPOSITORY=<owner/repo> GITHUB_TOKEN=<token> \
//     COVERAGE_VERDICT=<verdict> COVERAGE_MESSAGE=<message> \
//     node .github/scripts/codecov-patch.mjs
//
// The coverage job has already looked (coverage-notify.mjs): `gap` (a flag
// carried forward short, which Codecov never notifies) and `silent` (nothing
// posted after three sends) fail at once with its message; `posted` waits at
// most five minutes for the patch status, which then arrives within one or
// two.
//
// Codecov posts `codecov/patch` as a check run of its app on the head commit.
// It holds every notification until ci.yml's coverage-notify job triggers it,
// after every coverage upload of the run (codecov.yml:
// codecov.notify.manual_trigger), so each run it posts is judged on the run's
// whole report and the newest one decides: `pending` until it exists and is
// completed, then `passed` or `failed`. The target (90%, threshold 1%) is the
// merge rule; "Coverage not affected" is a success. A run of another app with
// the same name is ignored. The status is a pull request's, so ci.yml waits for
// it only there.
//
// The one state that never resolves is an incomplete carry-forward. A commit
// whose run skips the JVM jobs (a TypeScript-only change) takes the JVM flags'
// sessions from its parent, and Codecov fixes them when it creates the
// commit's report. A main commit pushed while its parent's JVM shards were
// still uploading carries forward only those already in, say 2 of
// `integration`'s 4, and every commit built on it inherits the 2. A flag below
// its after_n_builds never lets Codecov judge the statuses that read it, patch
// among them, so the wait would run to its deadline (#5093, 2026-10-28). The
// gate reads the head's sessions from Codecov's public API instead, and when a
// flag has fewer distinct carried-forward sessions than `AFTER_N_BUILDS` and no
// upload of its own, it fails at once, naming the newest main commit whose
// flags are complete and the first-parent merge that inherits from it (a
// plain update from main keeps the head as the first parent and the gap with
// it). This is a diagnosis only: when Codecov's API cannot be read, the gate
// waits as before. Each TypeScript package's flag counts one session: a main
// commit pushed before its parent's slowest upload, view-engine's, carries
// none of that package forward, so a flag with no session at all is a gap too
// once the commit carried any other forward (ebbde37, 2026-10-29).
//
// A failure can also be judged on someone else's lines. Codecov diffs a pull
// request from its base, and the base is GitHub's `base.sha`: main as it was
// when the pull request was opened, which a merge of main into the head does
// not move. Every line main gained since then is then this pull request's
// patch, and a rerun keeps the same base (#5127, 2026-10-28: 82% on #5126's
// Kotlin). When the patch fails, the gate asks Codecov's compare API which
// base it judged against and GitHub for the merge base of the head and main;
// when they differ it fails with the one command that repairs it, a refresh
// of the pull request's base, instead of the failure on those lines. When
// either API cannot tell, the failure stands as Codecov gave it.

export const CHECK = 'codecov/patch';
export const APP = 'codecov';
/**
 * The flags whose statuses wait for a number of sessions: codecov.yml's
 * `after_n_builds` (the test holds the two to each other).
 */
export const AFTER_N_BUILDS = {
  'jvm-unit': 4,
  integration: 4,
  'typescript-wow-client': 1,
  'typescript-wow-generator': 1,
  'typescript-wow-react': 1,
  'typescript-wow-view-engine': 1,
  'typescript-wow-view-store': 1,
};
/**
 * What the check runs of the head commit say: the newest of Codecov's patch
 * runs decides, `pending` until there is one and it is completed.
 */
export function patchVerdict(runs) {
  const newest = runs
    .filter(run => run.name === CHECK && run.app?.slug === APP)
    .sort((a, b) => b.id - a.id)[0];
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

/** A session's name without Codecov's carry-forward prefix (`CF[2] - `). */
function sessionName(upload) {
  return (upload.name ?? '').replace(/^CF\[\d+\] - /, '');
}

/**
 * The distinct sessions of each counted flag in a commit's uploads (Codecov's
 * API: `flags`, `upload_type`, `name`), uploaded and carried forward apart.
 */
export function flagSessions(uploads, builds = AFTER_N_BUILDS) {
  const sessions = Object.fromEntries(
    Object.keys(builds).map(flag => [
      flag,
      { uploaded: new Set(), carried: new Set() },
    ]),
  );
  for (const upload of uploads)
    for (const flag of upload.flags ?? []) {
      const of = sessions[flag];
      if (!of) continue;
      const kind =
        upload.upload_type === 'carriedforward' ? 'carried' : 'uploaded';
      of[kind].add(sessionName(upload));
    }
  return sessions;
}

/**
 * The counted flags a commit carried forward too few sessions of and uploads
 * nothing of: Codecov will never judge a status that reads one. A flag with no
 * session at all is one once the commit carried any session forward; before,
 * Codecov has made no report of the commit to read.
 */
export function incompleteFlags(uploads, builds = AFTER_N_BUILDS) {
  const carriedAny = uploads.some(
    upload => upload.upload_type === 'carriedforward',
  );
  return Object.entries(flagSessions(uploads, builds))
    .filter(
      ([flag, { uploaded, carried }]) =>
        uploaded.size === 0 &&
        (carried.size > 0 || carriedAny) &&
        carried.size < builds[flag],
    )
    .map(([flag, { carried }]) => ({
      flag,
      have: carried.size,
      need: builds[flag],
    }));
}

/** Whether every counted flag of a commit has its sessions. */
export function flagsComplete(uploads, builds = AFTER_N_BUILDS) {
  return Object.entries(flagSessions(uploads, builds)).every(
    ([flag, { uploaded, carried }]) =>
      new Set([...uploaded, ...carried]).size >= builds[flag],
  );
}

/**
 * The verdict when the head carried forward an incomplete flag, or null.
 * `uploadsOf(sha)` reads a commit's uploads from Codecov, `mainCommits()` the
 * newest commits of main, newest first. Any error is a diagnosis that could
 * not be made: null, and the gate waits as it always has.
 */
export async function carryForwardGap({
  head,
  branch,
  uploadsOf,
  mainCommits,
  log = () => {},
}) {
  let missing;
  try {
    missing = incompleteFlags(await uploadsOf(head));
  } catch (error) {
    log(
      `Codecov's uploads of ${head} could not be read (${error.message}); waiting as before`,
    );
    return null;
  }
  if (!missing.length) return null;
  const gaps = missing
    .map(({ flag, have, need }) => `${flag} (${have} of ${need})`)
    .join(', ');
  let base;
  try {
    for (const sha of await mainCommits())
      if (flagsComplete(await uploadsOf(sha))) {
        base = sha;
        break;
      }
  } catch (error) {
    log(`main's commits could not be read from Codecov (${error.message})`);
  }
  const merge = base
    ? ` Merge it into a commit whose first parent is ${base}, the newest main commit with every flag complete: git fetch origin && git checkout -B codecov-sync ${base} && git merge ${head} && git push origin codecov-sync:${branch} (a fast-forward; a plain update from main keeps ${head} as the first parent and the gap with it).`
    : ' Merge it into a commit whose first parent is a main commit with every flag complete (Codecov: its uploads); a plain update from main keeps the head as the first parent and the gap with it.';
  return {
    state: 'failed',
    message: `Codecov carried forward too few sessions to ${head}: ${gaps}. It will never judge ${CHECK} on this head.${merge}`,
  };
}

/**
 * The verdict when Codecov judged the patch against an older base than the
 * head's own, or null. `codecovBase()` reads the base commit of Codecov's
 * comparison, `mergeBase()` the merge base of the head and main, and
 * `commitsBetween(a, b)` how many commits `b` has that `a` has not. Any error
 * is a diagnosis that could not be made: null, and the failure stands.
 */
export async function staleBase({
  pr,
  repository,
  codecovBase,
  mergeBase,
  commitsBetween,
  log = () => {},
}) {
  let base;
  let merge;
  try {
    [base, merge] = await Promise.all([codecovBase(), mergeBase()]);
  } catch (error) {
    log(
      `the base Codecov compared against could not be read (${error.message})`,
    );
    return null;
  }
  if (!base || !merge || base === merge) return null;
  let since = '';
  try {
    const count = await commitsBetween(base, merge);
    since = ` (${count} commit${count === 1 ? '' : 's'} of main)`;
  } catch {
    // The count only sharpens the message.
  }
  return {
    state: 'failed',
    message: `Codecov compared against ${base}, the pull request's base when it was opened, not ${merge}, where the head meets main: every line main gained in between${since} counts as this pull request's patch. Refresh the pull request's base (gh api -X PATCH repos/${repository}/pulls/${pr} -f base=main) and rerun ci-gate once Codecov has reported again.`,
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
  diagnose = async () => null,
  explain = async () => null,
}) {
  const deadline = now() + timeoutMs;
  for (;;) {
    const verdict = patchVerdict(await fetchRuns());
    if (verdict.state === 'failed') return (await explain()) ?? verdict;
    if (verdict.state !== 'pending') return verdict;
    const stuck = await diagnose();
    if (stuck) return stuck;
    if (now() + intervalMs > deadline)
      return {
        state: 'failed',
        message: `Codecov's ${CHECK} did not complete within ${timeoutMs / 60_000} minutes (${verdict.message}); rerun ci-gate once Codecov has reported`,
      };
    log(`${verdict.message}; waiting`);
    await sleep(intervalMs);
  }
}

/**
 * The GitHub and Codecov readers the gate and ci.yml's coverage job share:
 * `api(url)` reads GitHub's REST API, `uploadsOf(sha)` a commit's uploads from
 * Codecov's public API, and `mainCommits()` main's newest commits.
 */
export function codecovClients({ repository, token }) {
  const api = async url => {
    const response = await fetch(url, {
      headers: {
        accept: 'application/vnd.github+json',
        authorization: `Bearer ${token}`,
        'x-github-api-version': '2022-11-28',
      },
    });
    if (!response.ok)
      throw new Error(`${url}: ${response.status} ${await response.text()}`);
    return response.json();
  };
  const [owner, repo] = repository.split('/');
  const uploadsOf = async sha => {
    const uploads = [];
    let next = `https://api.codecov.io/api/v2/github/${owner}/repos/${repo}/commits/${sha}/uploads/?page_size=100`;
    while (next) {
      const response = await fetch(next, {
        signal: AbortSignal.timeout(20_000),
      });
      if (!response.ok) throw new Error(`${next}: ${response.status}`);
      const page = await response.json();
      uploads.push(...page.results);
      next = page.next;
    }
    return uploads;
  };
  const mainCommits = async () =>
    (
      await api(
        `https://api.github.com/repos/${repository}/commits?sha=main&per_page=20`,
      )
    ).map(commit => commit.sha);
  return { api, uploadsOf, mainCommits };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { HEAD_SHA, HEAD_REF, PR_NUMBER, GITHUB_REPOSITORY, GITHUB_TOKEN } =
    process.env;
  if (!/^[0-9a-f]{40}$/.test(HEAD_SHA ?? '') || !GITHUB_REPOSITORY)
    throw new Error('HEAD_SHA and GITHUB_REPOSITORY are required');
  const coverage = process.env.COVERAGE_VERDICT;
  if (coverage === 'gap' || coverage === 'silent') {
    console.error(`::error::${process.env.COVERAGE_MESSAGE}`);
    process.exit(1);
  }
  const { api, uploadsOf, mainCommits } = codecovClients({
    repository: GITHUB_REPOSITORY,
    token: GITHUB_TOKEN,
  });
  const url = `https://api.github.com/repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}/check-runs?check_name=${encodeURIComponent(CHECK)}&filter=all&per_page=100`;
  const [owner, repo] = GITHUB_REPOSITORY.split('/');
  const verdict = await waitForPatch({
    fetchRuns: async () => (await api(url)).check_runs,
    // The coverage job saw Codecov post on this commit: patch follows within minutes.
    timeoutMs: 5 * 60_000,
    sleep: ms => new Promise(resolve => setTimeout(resolve, ms)),
    log: message => console.log(message),
    explain: async () =>
      /^\d+$/.test(PR_NUMBER ?? '')
        ? staleBase({
            pr: PR_NUMBER,
            repository: GITHUB_REPOSITORY,
            codecovBase: async () => {
              const response = await fetch(
                `https://api.codecov.io/api/v2/github/${owner}/repos/${repo}/compare/?pullid=${PR_NUMBER}`,
                { signal: AbortSignal.timeout(20_000) },
              );
              if (!response.ok) throw new Error(`compare: ${response.status}`);
              return (await response.json()).base_commit;
            },
            mergeBase: async () =>
              (
                await api(
                  `https://api.github.com/repos/${GITHUB_REPOSITORY}/compare/main...${HEAD_SHA}`,
                )
              ).merge_base_commit.sha,
            commitsBetween: async (from, to) =>
              (
                await api(
                  `https://api.github.com/repos/${GITHUB_REPOSITORY}/compare/${from}...${to}`,
                )
              ).ahead_by,
            log: message => console.log(message),
          })
        : null,
    diagnose: () =>
      carryForwardGap({
        head: HEAD_SHA,
        branch: HEAD_REF ?? '<branch>',
        uploadsOf,
        mainCommits,
        log: message => console.log(message),
      }),
  });
  if (verdict.state !== 'passed') {
    console.error(`::error::${verdict.message}`);
    process.exit(1);
  }
  console.log(verdict.message);
}
