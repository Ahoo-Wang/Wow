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
  carryForwardGap,
  flagsComplete,
  incompleteFlags,
  patchVerdict,
  staleBase,
  waitForPatch,
} from './codecov-patch.mjs';
import {
  codecovBuilds,
  workflowCheckShards,
  workflowShards,
} from './integration-shards.mjs';

const run = (id, status, conclusion, title = '', slug = 'codecov') => ({
  id,
  name: 'codecov/patch',
  app: { slug },
  status,
  conclusion,
  output: { title },
});

test("the newest of Codecov's patch runs decides", () => {
  const verdict = runs => patchVerdict(runs).state;
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

test('a run of another app with the same name is ignored', () => {
  assert.equal(
    patchVerdict([run(1, 'completed', 'success', '', 'github-actions')]).state,
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
    sleep,
    now: () => clock,
  });
  assert.equal(passed.state, 'passed');
  assert.equal(clock, 40_000);

  clock = 0;
  const late = await waitForPatch({
    fetchRuns: async () => [],
    sleep,
    now: () => clock,
    intervalMs: 20_000,
    timeoutMs: 60_000,
  });
  assert.equal(late.state, 'failed');
  assert.match(late.message, /did not complete within 1 minutes/);
  assert.ok(clock <= 60_000);
});

const workflow = readFileSync(
  new URL('../workflows/ci.yml', import.meta.url),
  'utf8',
);
/** A job of ci.yml: from its key to the next job's. */
const job = name => {
  const start = workflow.indexOf(`\n  ${name}:\n`);
  assert.ok(start >= 0, name);
  const end = workflow.slice(start + 1).search(/\n {2}[a-z0-9-]+:\n/);
  return workflow.slice(start, end < 0 ? undefined : start + 1 + end);
};
const needs = name =>
  /\n {4}needs:\s*\[([^\]]*)\]/
    .exec(job(name))[1]
    .split(',')
    .map(need => need.trim())
    .filter(Boolean);

test('each TypeScript package uploads under its own flag alone, which the statuses read by name', () => {
  const codecov = readFileSync(
    new URL('../../codecov.yml', import.meta.url),
    'utf8',
  );
  // A shared flag on every upload would keep Codecov from carrying the other packages forward.
  const uploads = [...workflow.matchAll(/\n\s+flags: (.+)\n/g)].map(
    match => match[1],
  );
  assert.ok(uploads.length > 0);
  for (const flags of uploads) assert.doesNotMatch(flags, /,/, flags);
  // Every package's flag, as its component reads it, is in the patch and project statuses' lists.
  const packages = [...codecov.matchAll(/- "\^(typescript-[\w-]+)\$"/g)].map(
    match => match[1],
  );
  assert.deepEqual(packages.sort(), [
    'typescript-wow-client',
    'typescript-wow-generator',
    'typescript-wow-react',
    'typescript-wow-view-engine',
    'typescript-wow-view-store',
  ]);
  for (const status of ['patch', 'project']) {
    const block = new RegExp(`\\n {4}${status}:\\n(?: {6,}.*\\n)+`).exec(
      codecov,
    )[0];
    for (const flag of packages)
      assert.match(
        block,
        new RegExp(`- ${flag}\\n`),
        `${status} reads ${flag}`,
      );
    assert.doesNotMatch(
      block,
      /- typescript\n/,
      `${status} reads no shared typescript flag`,
    );
  }
  assert.match(codecov, /- name: typescript\n\s+carryforward: false\n/);
});

test("Codecov notifies once, when ci.yml's coverage job triggers it after every upload", () => {
  const codecov = readFileSync(
    new URL('../../codecov.yml', import.meta.url),
    'utf8',
  );
  assert.match(codecov, /\n {2}notify:\n {4}manual_trigger: true\n/);
  // Only the coverage job talks to Codecov; the test jobs keep their reports.
  const uploaders = [
    ...workflow.matchAll(
      /\n {2}([a-z0-9-]+):\n(?:(?!\n {2}[a-z0-9-]+:\n)[\s\S])*?name: coverage-/g,
    ),
  ].map(match => match[1]);
  assert.deepEqual(
    [
      ...workflow.matchAll(
        /\n {2}([a-z0-9-]+):\n(?:(?!\n {2}[a-z0-9-]+:\n)[\s\S])*?uses: codecov\/codecov-action@/g,
      ),
    ].map(match => match[1]),
    ['coverage'],
  );
  assert.deepEqual(needs('coverage').sort(), [...new Set(uploaders)].sort());
  const coverage = job('coverage');
  for (const uploader of new Set(uploaders))
    assert.match(
      coverage,
      new RegExp(`needs\\.${uploader}\\.result != 'skipped'`),
    );
  // The parent wait comes before the first upload, each send before its check.
  const steps = [
    'coverage-notify.mjs parent',
    'uses: codecov/codecov-action@',
    'run_command: send-notifications',
    'coverage-notify.mjs confirm\n',
    'run_command: send-notifications',
    'coverage-notify.mjs confirm\n',
    'run_command: send-notifications',
    'coverage-notify.mjs confirm --last',
  ];
  let at = 0;
  for (const step of steps) {
    const found = coverage.indexOf(step, at);
    assert.ok(found > at, step);
    at = found + 1;
  }
  assert.match(coverage, /if: steps\.confirm\.outputs\.verdict == 'pending'/);
  assert.match(
    coverage,
    /if: steps\.confirm-again\.outputs\.verdict == 'pending'/,
  );
  assert.ok(needs('ci-gate').includes('coverage'));
  const gate = job('ci-gate');
  assert.match(
    gate,
    /if: github\.event_name == 'pull_request' && needs\.coverage\.result == 'success'\n\s+env:/,
  );
  assert.match(
    gate,
    /COVERAGE_VERDICT: \$\{\{ needs\.coverage\.outputs\.verdict \}\}/,
  );
});

test('the coverage job uploads each check shard, integration shard and package as a session of its own', () => {
  const coverage = job('coverage');
  const sessions = [
    ...coverage.matchAll(
      /if: hashFiles\('coverage\/coverage-([\w-]+)\/[\w.-]+'\) != ''\n[\s\S]*?flags: ([\w-]+)\n\s+name: ([\w-]+)\n[\s\S]*?files: \.\/coverage\/coverage-([\w-]+)\//g,
    ),
  ].map(([, artifact, flag, name, file]) => {
    assert.equal(artifact, name);
    assert.equal(file, name);
    return { flag, name };
  });
  const expected = [
    ...workflowCheckShards(workflow).map(shard => ({
      flag: 'jvm-unit',
      name: `jvm-unit-${shard}`,
    })),
    ...workflowShards(workflow).map(({ shard }) => ({
      flag: 'integration',
      name: `integration-${shard}`,
    })),
    ...[
      'wow-client',
      'wow-react',
      'wow-generator',
      'wow-view-store',
      'wow-view-engine',
    ].map(name => ({ flag: `typescript-${name}`, name: `typescript-${name}` })),
  ];
  assert.deepEqual(sessions, expected);
  // Each test job keeps its report under the name the coverage job reads.
  for (const artifact of [
    'coverage-jvm-unit-${{ matrix.shard }}',
    'coverage-integration-${{ matrix.shard }}',
    'coverage-typescript-${{ matrix.package }}',
    'coverage-typescript-wow-view-engine',
  ])
    assert.ok(workflow.includes(`name: ${artifact}\n`), artifact);
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
const PACKAGES = [
  'wow-client',
  'wow-generator',
  'wow-react',
  'wow-view-engine',
  'wow-view-store',
];
const TYPESCRIPT = uploaded(
  'typescript-wow-view-engine',
  'typescript-wow-view-engine',
);
// The other packages, carried forward beside the one this commit tested.
const CARRIED_PACKAGES = PACKAGES.filter(
  name => name !== 'wow-view-engine',
).flatMap(name => carried(`typescript-${name}`, `typescript-${name}`));
const GAP_HEAD = [
  ...JVM_UNIT.flatMap(name => carried('jvm-unit', name, 2)),
  ...INTEGRATION.slice(0, 2).flatMap(name => carried('integration', name, 2)),
  ...CARRIED_PACKAGES,
  TYPESCRIPT,
];
const COMPLETE_MAIN = [
  ...JVM_UNIT.map(name => uploaded('jvm-unit', name)),
  ...INTEGRATION.map(name => uploaded('integration', name)),
  ...PACKAGES.map(name => uploaded(`typescript-${name}`, `typescript-${name}`)),
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

test('a TypeScript package carried forward by no session is a gap', () => {
  // ebbde37, 2026-10-29: a Kotlin-only main commit pushed before its parent's view-engine upload was in.
  const kotlinOnly = [
    ...JVM_UNIT.map(name => uploaded('jvm-unit', name)),
    ...INTEGRATION.map(name => uploaded('integration', name)),
    ...CARRIED_PACKAGES,
  ];
  assert.deepEqual(incompleteFlags(kotlinOnly), [
    { flag: 'typescript-wow-view-engine', have: 0, need: 1 },
  ]);
  assert.equal(flagsComplete(kotlinOnly), false);
  // A run that tests it fills the gap.
  assert.deepEqual(incompleteFlags([...kotlinOnly, TYPESCRIPT]), []);
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
    sleep: async () => {},
    explain,
  });
  assert.equal(waited.state, 'failed');
  assert.match(waited.message, /82\.45% of diff hit/);
  // A pass never asks.
  const passed = await waitForPatch({
    fetchRuns: async () => [run(1, 'completed', 'success', '95% of diff hit')],
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
