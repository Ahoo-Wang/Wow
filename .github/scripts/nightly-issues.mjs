/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import { pathToFileURL } from 'node:url';

// A failed nightly workflow opens an issue, and its next green run closes it
// (decision D375). nightly.yml dispatches soak.yml, mongo-cluster.yml,
// ga-e2e.yml and a full ci.yml on main every night main moved;
// nightly-issues.yml runs this script when one of those dispatch runs on main
// completes:
//
//   GITHUB_REPOSITORY=<owner/repo> GH_TOKEN=<token> RUN_ID=<id> [RUN_ATTEMPT=<n>] \
//     node .github/scripts/nightly-issues.mjs [--dry-run]
//
// Each workflow has at most one open issue, labelled `nightly-failure` and
// titled `Nightly: <workflow> failed`. A failed run opens it, or comments on
// the open one, with the run, its commit and its failed jobs; a successful run
// comments on the open issue and closes it. A cancelled or skipped run, or a
// run that is not a dispatch on main, changes nothing. A manual dispatch on
// main counts as a nightly; release admission dispatches ci.yml on the release
// tag, which is not main. `--dry-run` reads the run and the open issues and
// prints what it would do without writing anything; without RUN_ATTEMPT it
// reads the run's latest attempt.
//
// The sweep (`--sweep`, or SWEEP=true) takes, for each workflow nightly.yml
// dispatches, its latest completed dispatch run on main in the last
// SWEEP_HOURS (36 by default), and reports it the same way. nightly.yml
// dispatches with the GITHUB_TOKEN, and GitHub emits no workflow_run event
// for a run the GITHUB_TOKEN started, so nightly-issues.yml runs the sweep on
// a schedule after the nightly window; workflow_run still reports a manual
// dispatch as soon as it completes. A failure is reported once per run id: a
// run the open issue already names, in its body or a comment, changes
// nothing, so the sweep and workflow_run never report one run twice.
//
//   GITHUB_REPOSITORY=<owner/repo> GH_TOKEN=<token> [SWEEP_HOURS=<n>] \
//     node .github/scripts/nightly-issues.mjs --sweep [--dry-run]

export const LABEL = 'nightly-failure';

/** The workflows nightly.yml dispatches, by file, in its order. */
export const WORKFLOWS = [
  'soak.yml',
  'mongo-cluster.yml',
  'ga-e2e.yml',
  'ci.yml',
];

/** How far back the sweep looks for a workflow's latest run, by default. */
export const SWEEP_HOURS = 36;
const LABEL_COLOR = 'b60205';
const LABEL_DESCRIPTION =
  'A nightly workflow on main failed (nightly-issues.yml)';

/** Run and job conclusions that count as a failure. */
const FAILED = new Set(['failure', 'timed_out', 'startup_failure']);

export function issueTitle(workflow) {
  return `Nightly: ${workflow} failed`;
}

/** The open issue of a workflow among the repository's issues, or null. */
export function findIssue(issues, workflow) {
  return (
    issues
      .filter(
        issue =>
          !issue.pull_request &&
          issue.state === 'open' &&
          issue.title === issueTitle(workflow) &&
          issue.labels.some(label => (label.name ?? label) === LABEL),
      )
      .sort((a, b) => a.number - b.number)[0] ?? null
  );
}

/** The ids of the runs an issue's texts (its body and comments) link to. */
export function reportedRuns(texts) {
  const ids = new Set();
  for (const text of texts)
    for (const [, id] of (text ?? '').matchAll(/\/actions\/runs\/(\d+)/g))
      ids.add(Number(id));
  return ids;
}

/** The jobs of a run that failed, in the run's order. */
export function failedJobs(jobs) {
  return jobs.filter(job => FAILED.has(job.conclusion));
}

/** The run, its commit and its failed jobs, as Markdown list items. */
export function details({ run, jobs, repository }) {
  const lines = [
    `- Run: [${run.name} #${run.run_number}](${run.html_url}) (attempt ${run.run_attempt}, ` +
      `${(run.run_started_at ?? run.created_at).slice(0, 10)}, ` +
      `dispatched by \`${run.triggering_actor?.login ?? run.actor?.login ?? 'unknown'}\`): \`${run.conclusion}\``,
    `- Commit: [\`${run.head_sha.slice(0, 10)}\`](https://github.com/${repository}/commit/${run.head_sha})`,
  ];
  if (FAILED.has(run.conclusion)) {
    const failed = failedJobs(jobs);
    lines.push(
      failed.length
        ? '- Failed jobs:'
        : '- Failed jobs: none reported; the run shows why it failed',
      ...failed.map(
        job => `  - [${job.name}](${job.html_url}): \`${job.conclusion}\``,
      ),
    );
  }
  return lines.join('\n');
}

/**
 * What a completed run does to its workflow's issue: `{ action, reason }`
 * with `action` `none`, or `open` (`title`, `body`), `comment` or `close`
 * (`number`, `body`). `reported` holds the ids of the runs the open issue
 * already names: a failed run among them was reported, and changes nothing.
 */
export function plan({ run, jobs, issues, repository, reported = new Set() }) {
  if (run.event !== 'workflow_dispatch' || run.head_branch !== 'main')
    return {
      action: 'none',
      reason: `${run.name} run ${run.id} is a ${run.event} run on ${run.head_branch}, not a dispatch on main`,
    };
  if (run.status !== 'completed')
    return {
      action: 'none',
      reason: `${run.name} run ${run.id} is ${run.status}`,
    };
  const issue = findIssue(issues, run.name);
  const text = details({ run, jobs, repository });
  if (run.conclusion === 'success')
    return issue
      ? {
          action: 'close',
          number: issue.number,
          reason: `${run.name} run ${run.id} passed; closing #${issue.number}`,
          body: `Passed again; closing.\n\n${text}`,
        }
      : {
          action: 'none',
          reason: `${run.name} run ${run.id} passed; no open issue`,
        };
  if (!FAILED.has(run.conclusion))
    return {
      action: 'none',
      reason: `${run.name} run ${run.id} is ${run.conclusion}`,
    };
  if (issue && reported.has(run.id))
    return {
      action: 'none',
      reason: `${run.name} run ${run.id} failed; already reported on #${issue.number}`,
    };
  return issue
    ? {
        action: 'comment',
        number: issue.number,
        reason: `${run.name} run ${run.id} failed; commenting on #${issue.number}`,
        body: `Failed again.\n\n${text}`,
      }
    : {
        action: 'open',
        title: issueTitle(run.name),
        reason: `${run.name} run ${run.id} failed; opening an issue`,
        body: [
          `The nightly run of **${run.name}** on \`main\` failed.`,
          '',
          text,
          '',
          `Opened by \`nightly-issues.yml\` (D375): every later failed nightly run of ${run.name} comments here, ` +
            'and its next successful one closes this issue.',
        ].join('\n'),
      };
}

/** Carries out a plan through the client; the issue number it touched, or null. */
export async function apply(step, client) {
  switch (step.action) {
    case 'open': {
      if (!(await client.labelExists())) await client.createLabel();
      const issue = await client.createIssue({
        title: step.title,
        body: step.body,
        labels: [LABEL],
      });
      // An issue without the label is one the next run cannot find.
      if (!issue.labels.some(label => (label.name ?? label) === LABEL))
        throw new Error(`#${issue.number} was opened without ${LABEL}`);
      return issue.number;
    }
    case 'comment':
      await client.comment(step.number, step.body);
      return step.number;
    case 'close':
      await client.comment(step.number, step.body);
      await client.close(step.number);
      return step.number;
    default:
      return null;
  }
}

/** Reads the run and the open issues, plans, and applies the plan unless `dryRun`. */
export async function report({
  client,
  repository,
  runId,
  attempt,
  dryRun = false,
  log = () => {},
}) {
  const run = await client.run(runId, attempt);
  return reportRun({ client, repository, run, dryRun, log });
}

/**
 * Reports each workflow's latest completed dispatch run on main since
 * `since`, one after another; a workflow without one is logged and passed
 * over. The steps, by workflow.
 */
export async function sweep({
  client,
  repository,
  workflows = WORKFLOWS,
  since,
  dryRun = false,
  log = () => {},
}) {
  const steps = {};
  for (const workflow of workflows) {
    const run = await client.latestDispatchRun(workflow, since);
    if (!run) {
      log(`${workflow}: no completed dispatch run on main since ${since}`);
      steps[workflow] = null;
      continue;
    }
    steps[workflow] = await reportRun({ client, repository, run, dryRun, log });
  }
  return steps;
}

/** Plans a completed run's report against the open issues, and applies it unless `dryRun`. */
async function reportRun({ client, repository, run, dryRun, log }) {
  const failed = FAILED.has(run.conclusion);
  const jobs = failed ? await client.jobs(run.id, run.run_attempt) : [];
  const issues = await client.issues();
  const open = failed ? findIssue(issues, run.name) : null;
  const reported = open
    ? reportedRuns([
        open.body,
        ...(await client.comments(open.number)).map(comment => comment.body),
      ])
    : new Set();
  const step = plan({ run, jobs, issues, repository, reported });
  log(step.reason);
  if (dryRun) {
    if (step.action === 'open' && !(await client.labelExists()))
      log(`would create the label ${LABEL}`);
    if (step.title) log(`title: ${step.title}`);
    if (step.body) log(`body:\n${step.body}`);
    return step;
  }
  const number = await apply(step, client);
  if (number !== null) log(`https://github.com/${repository}/issues/${number}`);
  return step;
}

/** The URL of the next page in a `Link` header, or null. */
export function nextPage(link) {
  return /<([^>]+)>;\s*rel="next"/.exec(link ?? '')?.[1] ?? null;
}

/** GitHub's REST API for one repository, through `fetch`. */
export function githubClient({ repository, token, request = fetch }) {
  const base = `https://api.github.com/repos/${repository}`;
  const call = (method, path, body) =>
    request(path.startsWith('https://') ? path : `${base}${path}`, {
      method,
      headers: {
        accept: 'application/vnd.github+json',
        'x-github-api-version': '2022-11-28',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...(body ? { 'content-type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(20_000),
    });
  const ok = async (response, method, path) => {
    if (!response.ok)
      throw new Error(
        `${method} ${path}: ${response.status} ${await response.text()}`,
      );
    return response;
  };
  const json = async (method, path, body) =>
    (await ok(await call(method, path, body), method, path)).json();
  const pages = async (path, items = page => page) => {
    const all = [];
    for (let next = path; next;) {
      const response = await ok(await call('GET', next), 'GET', next);
      all.push(...items(await response.json()));
      next = nextPage(response.headers.get('link'));
    }
    return all;
  };
  return {
    run: (id, attempt) =>
      json(
        'GET',
        attempt
          ? `/actions/runs/${id}/attempts/${attempt}`
          : `/actions/runs/${id}`,
      ),
    jobs: (id, attempt) =>
      pages(
        `/actions/runs/${id}/attempts/${attempt}/jobs?per_page=100`,
        page => page.jobs,
      ),
    issues: () => pages(`/issues?labels=${LABEL}&state=open&per_page=100`),
    comments: number => pages(`/issues/${number}/comments?per_page=100`),
    latestDispatchRun: async (workflow, since) =>
      (
        await json(
          'GET',
          `/actions/workflows/${workflow}/runs?branch=main&event=workflow_dispatch` +
            `&status=completed&created=${encodeURIComponent(`>=${since}`)}&per_page=1`,
        )
      ).workflow_runs[0] ?? null,
    labelExists: async () => {
      const response = await call('GET', `/labels/${LABEL}`);
      if (response.status === 404) return false;
      await ok(response, 'GET', `/labels/${LABEL}`);
      return true;
    },
    createLabel: async () => {
      const response = await call('POST', '/labels', {
        name: LABEL,
        color: LABEL_COLOR,
        description: LABEL_DESCRIPTION,
      });
      // 422: another workflow's report created it a moment ago.
      if (response.status !== 422) await ok(response, 'POST', '/labels');
    },
    createIssue: issue => json('POST', '/issues', issue),
    comment: (number, body) =>
      json('POST', `/issues/${number}/comments`, { body }),
    close: number =>
      json('PATCH', `/issues/${number}`, {
        state: 'closed',
        state_reason: 'completed',
      }),
  };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const {
    GITHUB_REPOSITORY,
    GH_TOKEN,
    GITHUB_TOKEN,
    RUN_ID,
    RUN_ATTEMPT,
    SWEEP,
    SWEEP_HOURS: HOURS,
  } = process.env;
  if (!/^[\w.-]+\/[\w.-]+$/.test(GITHUB_REPOSITORY ?? ''))
    throw new Error('GITHUB_REPOSITORY is required');
  const client = githubClient({
    repository: GITHUB_REPOSITORY,
    token: GH_TOKEN ?? GITHUB_TOKEN,
  });
  const dryRun = process.argv.includes('--dry-run');
  const log = message => console.log(message);
  if (process.argv.includes('--sweep') || SWEEP === 'true') {
    if (HOURS && !/^\d+$/.test(HOURS))
      throw new Error(`SWEEP_HOURS is not a number: ${HOURS}`);
    const hours = HOURS ? Number(HOURS) : SWEEP_HOURS;
    const since = new Date(Date.now() - hours * 3_600_000).toISOString();
    await sweep({ client, repository: GITHUB_REPOSITORY, since, dryRun, log });
  } else {
    if (!/^\d+$/.test(RUN_ID ?? ''))
      throw new Error('RUN_ID is required without --sweep');
    if (RUN_ATTEMPT && !/^\d+$/.test(RUN_ATTEMPT))
      throw new Error(`RUN_ATTEMPT is not a number: ${RUN_ATTEMPT}`);
    await report({
      client,
      repository: GITHUB_REPOSITORY,
      runId: RUN_ID,
      attempt: RUN_ATTEMPT,
      dryRun,
      log,
    });
  }
}
