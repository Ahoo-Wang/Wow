/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */

// What Codecov holds of a commit, for the nightly coverage trend
// (coverage-trend.mjs): its sessions per flag, whether every flag has the
// sessions codecov.yml's after_n_builds asks for, and its totals. Codecov is
// informational (codecov.yml): nothing here gates a pull request.
//
// A commit's report is complete when each flag has its after_n_builds
// distinct sessions, uploaded or carried forward. A main commit pushed while
// its parent's shards were still uploading carries forward only those already
// in (96dadfe, 2026-10-28: 2 of `integration`'s 4), so a report is read only
// once it is complete.

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

/** Whether every counted flag of a commit has its sessions. */
export function flagsComplete(uploads, builds = AFTER_N_BUILDS) {
  return Object.entries(flagSessions(uploads, builds)).every(
    ([flag, { uploaded, carried }]) =>
      new Set([...uploaded, ...carried]).size >= builds[flag],
  );
}

/**
 * The GitHub and Codecov readers: `api(url)` reads GitHub's REST API,
 * `uploadsOf(sha)` a commit's uploads and `totalsOf(sha, flag)` its coverage
 * (overall without a flag), both from Codecov's public API.
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
  const codecov = `https://api.codecov.io/api/v2/github/${owner}/repos/${repo}`;
  const read = async url => {
    const response = await fetch(url, { signal: AbortSignal.timeout(20_000) });
    if (!response.ok) throw new Error(`${url}: ${response.status}`);
    return response.json();
  };
  const uploadsOf = async sha => {
    const uploads = [];
    let next = `${codecov}/commits/${sha}/uploads/?page_size=100`;
    while (next) {
      const page = await read(next);
      uploads.push(...page.results);
      next = page.next;
    }
    return uploads;
  };
  const totalsOf = async (sha, flag) => {
    const query = flag ? `&flag=${encodeURIComponent(flag)}` : '';
    const { totals } = await read(`${codecov}/totals/?sha=${sha}${query}`);
    return totals?.coverage ?? null;
  };
  return { api, uploadsOf, totalsOf };
}
