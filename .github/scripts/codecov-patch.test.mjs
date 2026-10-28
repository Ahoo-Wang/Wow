/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  AFTER_N_BUILDS,
  UPLOAD_STEP,
  carryForwardGap,
  flagsComplete,
  incompleteFlags,
  patchVerdict,
  staleBase,
  uploadsDoneAt,
  waitForPatch,
} from './codecov-patch.mjs';
import { codecovBuilds } from './integration-shards.mjs';

// This run's uploads were done at 06:09:13 (PR #4906's timeline).
const UPLOADS_DONE = '2026-10-26T06:09:13Z';
const BEFORE = '2026-10-26T06:01:07Z';
const AFTER = '2026-10-26T06:09:21Z';

const run = (
  id,
  status,
  conclusion,
  title = '',
  slug = 'codecov',
  started_at = AFTER,
) => ({
  id,
  name: 'codecov/patch',
  app: { slug },
  status,
  conclusion,
  started_at,
  output: { title },
});
const stale = (id, status, conclusion, title = '') =>
  run(id, status, conclusion, title, 'codecov', BEFORE);

test("the newest of Codecov's patch runs decides", () => {
  const verdict = runs => patchVerdict(runs, UPLOADS_DONE).state;
  assert.equal(verdict([]), 'pending');
  assert.equal(verdict([run(1, 'in_progress', null)]), 'pending');
  assert.equal(
    verdict([run(1, 'completed', 'success', 'Coverage not affected')]),
    'passed',
  );
  assert.equal(
    verdict([
      run(2, 'completed', 'failure', '85.00% of diff hit (target 90.00%)'),
      run(1, 'completed', 'success'),
    ]),
    'failed',
  );
  assert.equal(
    verdict([run(1, 'completed', 'failure'), run(2, 'completed', 'success')]),
    'passed',
  );
  // A newer run still in progress decides over a completed older one.
  assert.equal(
    verdict([run(1, 'completed', 'success'), run(2, 'queued', null)]),
    'pending',
  );
  // Neutral, cancelled or skipped is not a pass.
  assert.equal(verdict([run(1, 'completed', 'neutral')]), 'failed');
});

test("a failure Codecov started before this run's uploads were done never decides", () => {
  const early = patchVerdict(
    [stale(1, 'completed', 'failure', '40.00% of diff hit')],
    UPLOADS_DONE,
  );
  assert.equal(early.state, 'pending');
  assert.match(early.message, /predates this run's uploads/);
  // Nor does one started in the same second as the last upload's end.
  assert.equal(
    patchVerdict(
      [run(1, 'completed', 'failure', '', 'codecov', UPLOADS_DONE)],
      UPLOADS_DONE,
    ).state,
    'pending',
  );
  assert.equal(
    patchVerdict(
      [
        stale(1, 'completed', 'failure', '40.00% of diff hit'),
        run(2, 'completed', 'success', '96.08% of diff hit'),
      ],
      UPLOADS_DONE,
    ).state,
    'passed',
  );
  const failed = patchVerdict(
    [
      stale(1, 'completed', 'success'),
      run(2, 'completed', 'failure', '85.00% of diff hit (target 90.00%)'),
    ],
    UPLOADS_DONE,
  );
  assert.equal(failed.state, 'failed');
  assert.match(failed.message, /85\.00% of diff hit/);
});

test('an earlier success decides until a later run appears', () => {
  // PR #4907: Codecov's only run, "Coverage not affected", started before the
  // last upload, and Codecov did not post the unchanged result again.
  const early = stale(1, 'completed', 'success', 'Coverage not affected');
  const passed = patchVerdict([early], UPLOADS_DONE);
  assert.equal(passed.state, 'passed');
  assert.match(passed.message, /Coverage not affected/);
  // Only the newest earlier run counts: a later early failure is not passed
  // by an older early success.
  assert.equal(
    patchVerdict(
      [early, stale(2, 'completed', 'failure', '40.00% of diff hit')],
      UPLOADS_DONE,
    ).state,
    'pending',
  );
  assert.equal(
    patchVerdict([stale(1, 'in_progress', null)], UPLOADS_DONE).state,
    'pending',
  );
  // A later run always wins over an earlier success.
  assert.equal(
    patchVerdict(
      [early, run(2, 'completed', 'failure', '85.00% of diff hit')],
      UPLOADS_DONE,
    ).state,
    'failed',
  );
  assert.equal(
    patchVerdict([early, run(2, 'in_progress', null)], UPLOADS_DONE).state,
    'pending',
  );
});

test('a run of another app with the same name is ignored', () => {
  assert.equal(
    patchVerdict(
      [run(1, 'completed', 'success', '', 'github-actions')],
      UPLOADS_DONE,
    ).state,
    'pending',
  );
});

test('the wait polls until the verdict is final, and fails at its deadline', async () => {
  let clock = 0;
  const sleep = async ms => {
    clock += ms;
  };
  const responses = [
    [],
    [run(1, 'in_progress', null)],
    [run(1, 'completed', 'success', 'Coverage not affected')],
  ];
  const passed = await waitForPatch({
    fetchRuns: async () => responses.shift(),
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
  });
  assert.equal(passed.state, 'passed');
  assert.equal(clock, 40_000);

  clock = 0;
  const late = await waitForPatch({
    fetchRuns: async () => [],
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
    intervalMs: 20_000,
    timeoutMs: 60_000,
  });
  assert.equal(late.state, 'failed');
  assert.match(late.message, /did not complete within 1 minutes/);
  assert.ok(clock <= 60_000);
});

test("the wait skips Codecov's early status for the one after the uploads", async () => {
  // PR #4906: a failure judged on carried-forward coverage, then the pass with
  // every upload in.
  let clock = 0;
  const sleep = async ms => {
    clock += ms;
  };
  const early = stale(1, 'completed', 'failure', '40.00% of diff hit');
  const responses = [
    [early],
    [early, run(2, 'in_progress', null)],
    [early, run(2, 'completed', 'success', '96.08% of diff hit')],
  ];
  const passed = await waitForPatch({
    fetchRuns: async () => responses.shift(),
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
  });
  assert.equal(passed.state, 'passed');
  assert.equal(clock, 40_000);

  clock = 0;
  const late = await waitForPatch({
    fetchRuns: async () => [early],
    uploadsDone: UPLOADS_DONE,
    sleep,
    now: () => clock,
    intervalMs: 20_000,
    timeoutMs: 60_000,
  });
  assert.equal(late.state, 'failed');
  assert.match(late.message, /did not complete within 1 minutes/);
  assert.match(late.message, /predates this run's uploads/);
});

const step = (name, completed_at, conclusion = 'success') => ({
  name,
  status: 'completed',
  conclusion,
  completed_at,
});

test("the uploads were done when the run's last upload step finished", () => {
  const jobs = [
    {
      name: 'Check (host)',
      completed_at: '2026-10-26T06:05:30Z',
      steps: [
        step('Check the modules with coverage', '2026-10-26T06:04:40Z'),
        step('Upload unit test coverage', '2026-10-26T06:04:50Z'),
        step('Post Set up Gradle', '2026-10-26T06:05:29Z'),
      ],
    },
    {
      name: 'Integration Test (2)',
      completed_at: '2026-10-26T06:09:20Z',
      steps: [
        step('Upload integration test coverage', UPLOADS_DONE),
        step('Complete job', '2026-10-26T06:09:20Z'),
      ],
    },
    // A job that uploads nothing does not hold the gate back, however late.
    {
      name: 'Storybook / visual',
      completed_at: '2026-10-26T06:30:00Z',
      steps: [step('Compare the screenshots', '2026-10-26T06:29:50Z')],
    },
    // A skipped upload (Node 22's legs) uploaded nothing.
    {
      name: 'Unit (wow-client, Node 22)',
      completed_at: '2026-10-26T06:20:00Z',
      steps: [step('Upload coverage', '2026-10-26T06:20:00Z', 'skipped')],
    },
    // The gate itself is still running.
    {
      name: 'ci-gate',
      completed_at: null,
      steps: [step('Upload the gate coverage', '2026-10-26T06:40:00Z')],
    },
  ];
  assert.equal(uploadsDoneAt(jobs), '2026-10-26T06:09:13.000Z');
  assert.equal(uploadsDoneAt(jobs.slice(2)), null);
  assert.equal(uploadsDoneAt([]), null);
});

test('every Codecov upload step of ci.yml is named so the gate finds it', () => {
  const workflow = readFileSync(
    new URL('../workflows/ci.yml', import.meta.url),
    'utf8',
  );
  const names = [
    ...workflow.matchAll(
      /- name: (.+)\n(?:\s+if: .+\n)?\s+uses: codecov\/codecov-action@/g,
    ),
  ].map(match => match[1]);
  assert.equal(
    names.length,
    workflow.match(/uses: codecov\/codecov-action@/g).length,
  );
  assert.ok(names.length > 0);
  for (const name of names) assert.match(name, UPLOAD_STEP);
});

// #5093's head, 2026-10-28: its main commit (96dadfe) was pushed while
// 715127c's integration shards were still uploading, so it carried forward 2
// of the 4; the head's own commits carried those 2 forward twice over.
const carried = (flag, name, times = 1) =>
  Array.from({ length: times }, (_, i) => ({
    flags: [flag],
    upload_type: 'carriedforward',
    name: `CF[${i + 1}] - ${name}`,
  }));
const uploaded = (flag, name) => ({
  flags: [flag],
  upload_type: 'uploaded',
  name,
});
const JVM_UNIT = [
  'jvm-unit-host',
  'jvm-unit-apps',
  'jvm-unit-tooling',
  'jvm-unit-rest',
];
const INTEGRATION = [
  'integration-mongo-postgres',
  'integration-kafka-elasticsearch-clickhouse',
  'integration-eshop',
  'integration-host-platform',
];
const TYPESCRIPT = {
  flags: ['typescript', 'typescript-wow-view-engine'],
  upload_type: 'uploaded',
  name: 'ts',
};
const GAP_HEAD = [
  ...JVM_UNIT.flatMap(name => carried('jvm-unit', name, 2)),
  ...INTEGRATION.slice(0, 2).flatMap(name => carried('integration', name, 2)),
  TYPESCRIPT,
];
const COMPLETE_MAIN = [
  ...JVM_UNIT.map(name => uploaded('jvm-unit', name)),
  ...INTEGRATION.map(name => uploaded('integration', name)),
];
const HEAD = 'edfaa06aaf6e8f20a42df528d37ce442d48291d3';
const TIP = 'f6d60f5815aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const COMPLETE = '20644d0090589416c17f4c93941a2139291da22e';

test("the gate counts the flags codecov.yml's after_n_builds counts", () => {
  const codecov = readFileSync(
    new URL('../../codecov.yml', import.meta.url),
    'utf8',
  );
  for (const [flag, builds] of Object.entries(AFTER_N_BUILDS))
    assert.equal(codecovBuilds(codecov, flag), builds, flag);
});

test('a flag carried forward short of its after_n_builds, with no upload of its own, is a gap', () => {
  // The same two sessions carried forward twice are two sessions, not four.
  assert.deepEqual(incompleteFlags(GAP_HEAD), [
    { flag: 'integration', have: 2, need: 4 },
  ]);
  assert.equal(flagsComplete(GAP_HEAD), false);
  assert.equal(flagsComplete(COMPLETE_MAIN), true);
  // A flag this commit uploads itself replaces what was carried forward.
  assert.deepEqual(
    incompleteFlags([...GAP_HEAD, uploaded('integration', INTEGRATION[0])]),
    [],
  );
  // Nothing carried forward yet is no report yet, not a gap.
  assert.deepEqual(incompleteFlags([TYPESCRIPT]), []);
});

test('an incomplete carry-forward fails at once, naming the complete main commit and the merge', async () => {
  const uploads = { [HEAD]: GAP_HEAD, [TIP]: [], [COMPLETE]: COMPLETE_MAIN };
  const verdict = await carryForwardGap({
    head: HEAD,
    branch: 'claude/d353-wire-fixtures',
    uploadsOf: async sha => uploads[sha],
    mainCommits: async () => [TIP, COMPLETE],
  });
  assert.equal(verdict.state, 'failed');
  assert.match(verdict.message, /integration \(2 of 4\)/);
  assert.match(verdict.message, new RegExp(`first parent is ${COMPLETE}`));
  assert.match(
    verdict.message,
    new RegExp(
      `git checkout -B codecov-sync ${COMPLETE} && git merge ${HEAD} && git push origin codecov-sync:claude/d353-wire-fixtures`,
    ),
  );
  assert.match(verdict.message, /a plain update from main keeps/);

  // The wait stops on the diagnosis rather than running to its deadline.
  let clock = 0;
  const stopped = await waitForPatch({
    fetchRuns: async () => [],
    uploadsDone: UPLOADS_DONE,
    sleep: async ms => {
      clock += ms;
    },
    now: () => clock,
    diagnose: async () => verdict,
  });
  assert.equal(stopped, verdict);
  assert.equal(clock, 0);
});

test('a complete head goes on to the patch verdict as before', async () => {
  assert.equal(
    await carryForwardGap({
      head: HEAD,
      branch: 'b',
      uploadsOf: async () => [...COMPLETE_MAIN, TYPESCRIPT],
      mainCommits: async () => assert.fail('main is read only for a gap'),
    }),
    null,
  );
  const passed = await waitForPatch({
    fetchRuns: async () => [
      run(1, 'completed', 'success', 'Coverage not affected'),
    ],
    uploadsDone: UPLOADS_DONE,
    sleep: async () => {},
    diagnose: async () => assert.fail('a decided verdict needs no diagnosis'),
  });
  assert.equal(passed.state, 'passed');
});

test("Codecov's API out of reach leaves the wait as it was", async () => {
  const told = [];
  assert.equal(
    await carryForwardGap({
      head: HEAD,
      branch: 'b',
      uploadsOf: async () => {
        throw new Error('fetch failed');
      },
      mainCommits: async () => [],
      log: message => told.push(message),
    }),
    null,
  );
  assert.match(
    told[0],
    /could not be read \(fetch failed\); waiting as before/,
  );

  // A gap whose main commits cannot be read still fails, without a commit to name.
  const unnamed = await carryForwardGap({
    head: HEAD,
    branch: 'b',
    uploadsOf: async sha =>
      sha === HEAD ? GAP_HEAD : Promise.reject(new Error('503')),
    mainCommits: async () => [COMPLETE],
  });
  assert.equal(unnamed.state, 'failed');
  assert.match(unnamed.message, /a main commit with every flag complete/);
});

const OPENED = 'e313372e1c846462512e52d381362ccaee4d2fab';
const MERGED = '8de4aa98f383d889dadf41d1971ec27e5058b4a1';
const failing = () => [
  run(1, 'completed', 'failure', '82.45% of diff hit (target 90.00%)'),
];

test("a patch judged against the pull request's opening base names the refresh that repairs it", async () => {
  const stale = () =>
    staleBase({
      pr: '5127',
      repository: 'Ahoo-Wang/Wow',
      codecovBase: async () => OPENED,
      mergeBase: async () => MERGED,
      commitsBetween: async (from, to) => {
        assert.deepEqual([from, to], [OPENED, MERGED]);
        return 1;
      },
    });
  const verdict = await stale();
  assert.equal(verdict.state, 'failed');
  assert.match(verdict.message, new RegExp(`compared against ${OPENED}`));
  assert.match(verdict.message, new RegExp(`not ${MERGED}`));
  assert.match(verdict.message, /\(1 commit of main\)/);
  assert.match(
    verdict.message,
    /gh api -X PATCH repos\/Ahoo-Wang\/Wow\/pulls\/5127 -f base=main/,
  );
  // The wait gives the diagnosis in place of the failure on main's lines.
  const waited = await waitForPatch({
    fetchRuns: async () => failing(),
    uploadsDone: UPLOADS_DONE,
    sleep: async () => {},
    explain: stale,
  });
  assert.equal(waited.message, verdict.message);
});

test("a patch judged against the head's own merge base fails as Codecov gave it", async () => {
  const explain = () =>
    staleBase({
      pr: '5127',
      repository: 'Ahoo-Wang/Wow',
      codecovBase: async () => MERGED,
      mergeBase: async () => MERGED,
      commitsBetween: async () => assert.fail('nothing to count'),
    });
  assert.equal(await explain(), null);
  const waited = await waitForPatch({
    fetchRuns: async () => failing(),
    uploadsDone: UPLOADS_DONE,
    sleep: async () => {},
    explain,
  });
  assert.equal(waited.state, 'failed');
  assert.match(waited.message, /82\.45% of diff hit/);
  // A pass never asks.
  const passed = await waitForPatch({
    fetchRuns: async () => [run(1, 'completed', 'success', '95% of diff hit')],
    uploadsDone: UPLOADS_DONE,
    sleep: async () => {},
    explain: async () => assert.fail('a pass needs no explanation'),
  });
  assert.equal(passed.state, 'passed');
});

test('a base no API can tell leaves the failure as it was', async () => {
  const told = [];
  assert.equal(
    await staleBase({
      pr: '5127',
      repository: 'Ahoo-Wang/Wow',
      codecovBase: async () => {
        throw new Error('compare: 503');
      },
      mergeBase: async () => MERGED,
      commitsBetween: async () => 0,
      log: message => told.push(message),
    }),
    null,
  );
  assert.match(told[0], /could not be read \(compare: 503\)/);
  // A count out of reach still names the refresh, without the count.
  const uncounted = await staleBase({
    pr: '5127',
    repository: 'Ahoo-Wang/Wow',
    codecovBase: async () => OPENED,
    mergeBase: async () => MERGED,
    commitsBetween: async () => {
      throw new Error('404');
    },
  });
  assert.equal(uncounted.state, 'failed');
  assert.doesNotMatch(uncounted.message, /commits? of main/);
});
