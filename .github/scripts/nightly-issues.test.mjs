/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  apply,
  details,
  failedJobs,
  findIssue,
  githubClient,
  issueTitle,
  LABEL,
  nextPage,
  plan,
  report,
} from './nightly-issues.mjs';

const REPOSITORY = 'Ahoo-Wang/Wow';
const SHA = 'ddb557db7bf0077ca3e5466624eb06909474b7ba';

const run = (overrides = {}) => ({
  id: 40969321145,
  name: 'Soak',
  event: 'workflow_dispatch',
  head_branch: 'main',
  head_sha: SHA,
  status: 'completed',
  conclusion: 'failure',
  run_number: 9,
  run_attempt: 1,
  run_started_at: '2026-10-29T22:54:10Z',
  html_url: 'https://github.com/Ahoo-Wang/Wow/actions/runs/40969321145',
  triggering_actor: { login: 'github-actions[bot]' },
  ...overrides,
});

const JOBS = [
  {
    name: 'eShop Soak',
    conclusion: 'failure',
    html_url:
      'https://github.com/Ahoo-Wang/Wow/actions/runs/40969321145/job/123781778636',
  },
  { name: 'Lint', conclusion: 'success', html_url: 'https://example/lint' },
  { name: 'Shard 2', conclusion: 'timed_out', html_url: 'https://example/2' },
  { name: 'Shard 3', conclusion: 'cancelled', html_url: 'https://example/3' },
  { name: 'Skipped', conclusion: 'skipped', html_url: 'https://example/4' },
];

const issue = (overrides = {}) => ({
  number: 5300,
  title: 'Nightly: Soak failed',
  state: 'open',
  labels: [{ name: LABEL }],
  ...overrides,
});

test('the label and the title are fixed', () => {
  assert.equal(LABEL, 'nightly-failure');
  assert.equal(issueTitle('Soak'), 'Nightly: Soak failed');
  assert.equal(issueTitle('GA end-to-end'), 'Nightly: GA end-to-end failed');
});

test("a workflow's issue is the open one with the label and its title", () => {
  const found = issue();
  assert.equal(findIssue([found], 'Soak'), found);
  assert.equal(
    findIssue([issue({ labels: [LABEL] })], 'Soak').number,
    5300,
    'a label given by name',
  );
  // The oldest when two are open.
  assert.equal(
    findIssue([issue({ number: 5301 }), issue({ number: 5300 })], 'Soak')
      .number,
    5300,
  );
  for (const other of [
    issue({ state: 'closed' }),
    issue({ labels: [] }),
    issue({ labels: [{ name: 'bug' }] }),
    issue({ title: 'Nightly: CI failed' }),
    issue({ title: 'Nightly: Soak failed again' }),
    issue({ pull_request: { url: 'https://example' } }),
  ])
    assert.equal(findIssue([other], 'Soak'), null, JSON.stringify(other));
  assert.equal(findIssue([], 'Soak'), null);
});

test('failure, timed_out and startup_failure are failed jobs; cancelled and skipped are not', () => {
  assert.deepEqual(
    failedJobs([
      ...JOBS,
      { name: 'Setup', conclusion: 'startup_failure', html_url: 'x' },
    ]).map(job => job.name),
    ['eShop Soak', 'Shard 2', 'Setup'],
  );
});

test('a failed run with no open issue opens one with the run, the commit and the failed jobs', () => {
  const step = plan({
    run: run(),
    jobs: JOBS,
    issues: [],
    repository: REPOSITORY,
  });
  assert.equal(step.action, 'open');
  assert.equal(step.title, 'Nightly: Soak failed');
  assert.match(
    step.body,
    /^The nightly run of \*\*Soak\*\* on `main` failed\./,
  );
  assert.ok(
    step.body.includes(
      '- Run: [Soak #9](https://github.com/Ahoo-Wang/Wow/actions/runs/40969321145) ' +
        '(attempt 1, 2026-10-29, dispatched by `github-actions[bot]`): `failure`',
    ),
    step.body,
  );
  assert.ok(
    step.body.includes(
      `- Commit: [\`ddb557db7b\`](https://github.com/Ahoo-Wang/Wow/commit/${SHA})`,
    ),
  );
  assert.ok(
    step.body.includes(
      '- Failed jobs:\n' +
        '  - [eShop Soak](https://github.com/Ahoo-Wang/Wow/actions/runs/40969321145/job/123781778636): `failure`\n' +
        '  - [Shard 2](https://example/2): `timed_out`',
    ),
    step.body,
  );
  assert.doesNotMatch(step.body, /Lint|Shard 3|Skipped/);
  assert.match(step.body, /closes this issue/);
});

test('a failed run comments on the open issue instead of opening another', () => {
  const step = plan({
    run: run({ conclusion: 'timed_out', run_attempt: 2 }),
    jobs: JOBS,
    issues: [issue(), issue({ number: 5299, title: 'Nightly: CI failed' })],
    repository: REPOSITORY,
  });
  assert.equal(step.action, 'comment');
  assert.equal(step.number, 5300);
  assert.match(step.body, /^Failed again\./);
  assert.match(step.body, /\(attempt 2, .*\): `timed_out`/);
  assert.match(step.body, /\[eShop Soak\]/);
});

test('a failed run without failed jobs says so', () => {
  const text = details({
    run: run({ conclusion: 'startup_failure' }),
    jobs: [],
    repository: REPOSITORY,
  });
  assert.match(
    text,
    /- Failed jobs: none reported; the run shows why it failed$/,
  );
});

test('a green run comments on the open issue and closes it, and does nothing without one', () => {
  const step = plan({
    run: run({ conclusion: 'success', run_number: 10 }),
    jobs: [],
    issues: [issue()],
    repository: REPOSITORY,
  });
  assert.equal(step.action, 'close');
  assert.equal(step.number, 5300);
  assert.match(step.body, /^Passed again; closing\./);
  assert.match(step.body, /\[Soak #10\]/);
  assert.doesNotMatch(step.body, /Failed jobs/);
  assert.equal(
    plan({
      run: run({ conclusion: 'success' }),
      jobs: [],
      issues: [issue({ title: 'Nightly: CI failed' })],
      repository: REPOSITORY,
    }).action,
    'none',
  );
});

test('a cancelled, skipped or otherwise inconclusive run changes nothing', () => {
  for (const conclusion of [
    'cancelled',
    'skipped',
    'neutral',
    'action_required',
    'stale',
  ]) {
    const step = plan({
      run: run({ conclusion }),
      jobs: JOBS,
      issues: [issue()],
      repository: REPOSITORY,
    });
    assert.equal(step.action, 'none', conclusion);
    assert.match(step.reason, new RegExp(`is ${conclusion}$`));
  }
  assert.equal(
    plan({
      run: run({ status: 'in_progress', conclusion: null }),
      jobs: [],
      issues: [issue()],
      repository: REPOSITORY,
    }).action,
    'none',
  );
});

test('only a dispatch run on main reports: not a push, a pull request or a release tag', () => {
  for (const [event, branch] of [
    ['push', 'main'],
    ['pull_request', 'main'],
    ['schedule', 'main'],
    ['workflow_dispatch', 'v10.0.0-alpha.3'],
    ['workflow_dispatch', 'claude/feature'],
  ]) {
    const step = plan({
      run: run({ event, head_branch: branch }),
      jobs: JOBS,
      issues: [],
      repository: REPOSITORY,
    });
    assert.equal(step.action, 'none', `${event} on ${branch}`);
    assert.match(step.reason, /not a dispatch on main/);
  }
});

/** A client that records its writes. */
function fakeClient({
  theRun = run(),
  jobs = JOBS,
  issues = [],
  label = true,
  created,
} = {}) {
  const calls = [];
  return {
    calls,
    run: async (id, attempt) => {
      calls.push(['run', id, attempt]);
      return theRun;
    },
    jobs: async (id, attempt) => {
      calls.push(['jobs', id, attempt]);
      return jobs;
    },
    issues: async () => issues,
    labelExists: async () => label,
    createLabel: async () => calls.push(['createLabel']),
    createIssue: async body => {
      calls.push(['createIssue', body]);
      return created ?? { number: 5301, labels: [{ name: LABEL }] };
    },
    comment: async (number, body) => calls.push(['comment', number, body]),
    close: async number => calls.push(['close', number]),
  };
}

const writes = client =>
  client.calls.filter(([name]) => !['run', 'jobs'].includes(name));

test('a report opens the issue with the label, creating the label once', async () => {
  const client = fakeClient({ label: false });
  const lines = [];
  const step = await report({
    client,
    repository: REPOSITORY,
    runId: '40969321145',
    attempt: '1',
    log: line => lines.push(line),
  });
  assert.equal(step.action, 'open');
  assert.deepEqual(client.calls[0], ['run', '40969321145', '1']);
  assert.deepEqual(client.calls[1], ['jobs', 40969321145, 1]);
  assert.deepEqual(
    writes(client).map(([name]) => name),
    ['createLabel', 'createIssue'],
  );
  const [, created] = writes(client)[1];
  assert.equal(created.title, 'Nightly: Soak failed');
  assert.deepEqual(created.labels, [LABEL]);
  assert.equal(lines.at(-1), 'https://github.com/Ahoo-Wang/Wow/issues/5301');

  const labelled = fakeClient();
  await report({ client: labelled, repository: REPOSITORY, runId: '1' });
  assert.deepEqual(
    writes(labelled).map(([name]) => name),
    ['createIssue'],
  );
});

test('a report comments on the open issue, and a green one comments and closes it', async () => {
  const failed = fakeClient({ issues: [issue()] });
  await report({ client: failed, repository: REPOSITORY, runId: '1' });
  assert.deepEqual(
    writes(failed).map(([name, number]) => [name, number]),
    [['comment', 5300]],
  );

  const green = fakeClient({
    theRun: run({ conclusion: 'success' }),
    issues: [issue()],
  });
  await report({ client: green, repository: REPOSITORY, runId: '1' });
  assert.deepEqual(
    green.calls.map(([name, number]) => [name, number]),
    [
      ['run', '1'],
      ['comment', 5300],
      ['close', 5300],
    ],
    'reads no jobs for a green run',
  );
});

test('a cancelled run writes nothing', async () => {
  const client = fakeClient({
    theRun: run({ conclusion: 'cancelled' }),
    issues: [issue()],
  });
  const step = await report({ client, repository: REPOSITORY, runId: '1' });
  assert.equal(step.action, 'none');
  assert.deepEqual(writes(client), []);
  assert.ok(!client.calls.some(([name]) => name === 'jobs'));
});

test('a dry run prints the issue it would open and writes nothing', async () => {
  const client = fakeClient({ label: false });
  const lines = [];
  const step = await report({
    client,
    repository: REPOSITORY,
    runId: '40969321145',
    dryRun: true,
    log: line => lines.push(line),
  });
  assert.equal(step.action, 'open');
  assert.deepEqual(writes(client), []);
  assert.deepEqual(lines.slice(0, 3), [
    'Soak run 40969321145 failed; opening an issue',
    'would create the label nightly-failure',
    'title: Nightly: Soak failed',
  ]);
  assert.match(lines[3], /^body:\nThe nightly run of \*\*Soak\*\*/);
});

test('an issue opened without the label fails the report', async () => {
  const step = plan({
    run: run(),
    jobs: JOBS,
    issues: [],
    repository: REPOSITORY,
  });
  await assert.rejects(
    apply(step, fakeClient({ created: { number: 5301, labels: [] } })),
    /#5301 was opened without nightly-failure/,
  );
});

/** A `fetch` that answers from `routes` (`'METHOD path'` → response) and records requests. */
function fakeFetch(routes) {
  const requests = [];
  const request = async (url, init) => {
    requests.push({ url, ...init });
    const key = `${init.method} ${url.replace('https://api.github.com/repos/Ahoo-Wang/Wow', '')}`;
    const { status = 200, body = {}, link } = routes[key] ?? { status: 404 };
    return {
      ok: status >= 200 && status < 300,
      status,
      headers: { get: name => (name === 'link' ? (link ?? null) : null) },
      json: async () => body,
      text: async () => JSON.stringify(body),
    };
  };
  return { request, requests };
}

test('the client follows the pages, authenticates and reads the label', async () => {
  const page2 =
    'https://api.github.com/repositories/1/actions/runs/7/attempts/2/jobs?per_page=100&page=2';
  const { request, requests } = fakeFetch({
    'GET /actions/runs/7/attempts/2/jobs?per_page=100': {
      body: { jobs: [{ name: 'a' }] },
      link: `<${page2}>; rel="next", <${page2}>; rel="last"`,
    },
    [`GET ${page2}`]: { body: { jobs: [{ name: 'b' }] } },
    'GET /issues?labels=nightly-failure&state=open&per_page=100': {
      body: [issue()],
    },
    'GET /actions/runs/7': { body: run({ id: 7 }) },
    'GET /actions/runs/7/attempts/2': { body: run({ id: 7, run_attempt: 2 }) },
    'POST /labels': { status: 422 },
    'POST /issues/5300/comments': { status: 201, body: { id: 1 } },
    'PATCH /issues/5300': { body: { state: 'closed' } },
  });
  const client = githubClient({ repository: REPOSITORY, token: 't', request });
  assert.deepEqual(
    (await client.jobs(7, 2)).map(job => job.name),
    ['a', 'b'],
  );
  assert.equal(requests[0].headers.authorization, 'Bearer t');
  assert.equal((await client.issues()).length, 1);
  assert.equal((await client.run(7)).run_attempt, 1);
  assert.equal((await client.run(7, 2)).run_attempt, 2);
  assert.equal(await client.labelExists(), false, '404: no label yet');
  await client.createLabel();
  await client.comment(5300, 'Passed again; closing.');
  await client.close(5300);
  const patch = requests.at(-1);
  assert.equal(patch.method, 'PATCH');
  assert.deepEqual(JSON.parse(patch.body), {
    state: 'closed',
    state_reason: 'completed',
  });
  const label = requests.find(
    r => r.method === 'POST' && r.url.endsWith('/labels'),
  );
  assert.equal(JSON.parse(label.body).name, LABEL);
  await assert.rejects(
    client.createIssue({ title: 'x' }),
    /POST \/issues: 404/,
  );
  const anonymous = githubClient({ repository: REPOSITORY, request });
  await anonymous.run(7);
  assert.equal(requests.at(-1).headers.authorization, undefined);
});

test('the next page comes from the Link header', () => {
  assert.equal(nextPage(null), null);
  assert.equal(nextPage('<https://a?page=5>; rel="last"'), null);
  assert.equal(
    nextPage('<https://a?page=1>; rel="prev", <https://a?page=3>; rel="next"'),
    'https://a?page=3',
  );
});

test('the workflow reports every workflow nightly.yml dispatches, with the write permission on its one job', () => {
  const read = name =>
    readFileSync(new URL(`../workflows/${name}`, import.meta.url), 'utf8');
  const nightly = read('nightly.yml');
  const dispatched = [
    ...nightly.matchAll(/gh workflow run (\S+\.yml) --ref main/g),
  ].map(([, file]) => /^name: (.+)$/m.exec(read(file))[1].trim());
  assert.deepEqual(dispatched, [
    'Soak',
    'MongoDB cluster',
    'GA end-to-end',
    'CI',
  ]);

  const workflow = read('nightly-issues.yml');
  const on = /^on:\n {2}workflow_run:\n((?: {4}.+\n)+)/m.exec(workflow)?.[1];
  assert.ok(on, 'triggered by workflow_run alone');
  const listed = /workflows: \[(.+)\]/
    .exec(on)[1]
    .split(',')
    .map(name => name.trim());
  assert.deepEqual([...listed].sort(), [...dispatched].sort());
  assert.match(on, /types: \[completed\]/);
  assert.match(on, /branches: \[main\]/);
  assert.match(
    workflow,
    /if: github\.repository == 'Ahoo-Wang\/Wow' && github\.event\.workflow_run\.event == 'workflow_dispatch'/,
  );
  assert.match(workflow, /^permissions:\n {2}contents: read\n\n/m);
  assert.equal(workflow.match(/issues: write/g).length, 1);
  assert.equal(workflow.match(/actions: read/g).length, 1);
  assert.match(workflow, /run: node \.github\/scripts\/nightly-issues\.mjs$/m);
});
