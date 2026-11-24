/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
// The idle suites on a runner (bench-idle-suites.mjs, #6702): bench.sh idle for the baseline and the head at one node
// and three, their idle.json read back, a report that gives the head reads a second exactly and says the CPU is a shared
// runner's. bench.sh is a fake that writes the idle.json a session would.
import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { SUITES, suitesOf } from './bench.mjs';
import {
  IDLE,
  idleAnomalies,
  idleArgs,
  idleTable,
  NODE_READS_SLACK,
  renderIdle,
  runIdle,
} from './bench-idle-suites.mjs';
import { measureAnomalies, renderMeasure } from './bench-measure.mjs';

/**
 * A fake bench.sh: an idle session's idle.json, its head reads by arm (the install), the same at any node count, and
 * its counter reads: the baseline's split among its nodes, the head's 2.3 a second a node unless [threeCounters] gives
 * the head's at three.
 */
function fakeBench({ fail = '', threeCounters = '' } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'idle-bench-'));
  const path = join(dir, 'bench.sh');
  writeFileSync(
    path,
    `#!/usr/bin/env bash
out=""; install=""; nodes=""
for a in "$@"; do
  case "$a" in --out=*) out="\${a#--out=}" ;; --install=*) install="\${a#--install=}" ;; --nodes=*) nodes="\${a#--nodes=}" ;; esac
done
case "$install:$nodes" in ${fail || 'never'}) exit 1 ;; esac
heads=70.4; counters=70.5; added=6.6
if [ "$install" = /head ]; then heads=0.3; counters=2.3; added=1.4; fi
if [ "$install:$nodes" = /head:3 ]; then counters=${threeCounters || '6.9'}; fi
mkdir -p "$out"
cat >"$out/idle.json" <<JSON
{"schema":"wow-idle/1","backend":"mongo","nodes":$nodes,"logHeadReadsPerSecond":$heads,"logCounterReadsPerSecond":$counters,
 "machine":{"chip":"AMD EPYC 7763 64-Core Processor","cores":4},"scripts":"$WOW_BENCH_SCRIPTS",
 "storeCpu":{"withNodes":18.3,"withoutNodes":11.7,"added":$added},
 "reads":{"opcountersPerSecond":{"command":$heads,"query":4},
  "operations":[{"operation":"command aggregate transfer.wow_log__transfer__partition","perSecond":$heads}]}}
JSON
`,
  );
  chmodSync(path, 0o755);
  return path;
}

const run = (definition, installs, benchPath = fakeBench()) => {
  const suite = {
    name: 'idle-mongo',
    kind: 'measure',
    program: 'idle',
    runs: [],
    failures: 0,
  };
  runIdle(definition, suite, {
    benchPath,
    out: mkdtempSync(join(tmpdir(), 'idle-out-')),
    cpuset: { server: '0-2' },
    arms: definition.arms,
    installs,
    save: () => {},
  });
  return suite;
};

test('the idle suites are measure suites of the idle program, the baseline against the head, in a group of their own', () => {
  assert.deepEqual(suitesOf('idle'), ['idle-pg', 'idle-mongo']);
  for (const name of suitesOf('idle')) {
    assert.equal(SUITES[name].kind, 'measure');
    assert.equal(SUITES[name].measure, 'idle');
    assert.deepEqual(
      SUITES[name].arms.map(arm => arm.id),
      ['base', 'head'],
    );
    assert.match(SUITES[name].answers, /shared runner's/);
  }
  assert.deepEqual(IDLE.nodes, [1, 3]);
});

test('an idle run passes the backend, the node count, the arm’s install and the servers’ CPUs', () => {
  const arm = SUITES['idle-pg'].arms[1];
  assert.deepEqual(
    idleArgs(arm, 3, { head: '/head' }, { server: '0-2' }, '/out/head-3'),
    [
      'idle',
      '--backend=postgres',
      '--nodes=3',
      '--install=/head',
      '--server-cpus=0-2',
      '--out=/out/head-3',
    ],
  );
  assert.deepEqual(idleArgs(arm, 1, {}, {}, '/o'), [
    'idle',
    '--backend=postgres',
    '--nodes=1',
    '--out=/o',
  ]);
});

test('each arm runs at one node and three, and the report gives the head reads exactly and the CPU as a runner’s', () => {
  const suite = run(SUITES['idle-mongo'], { base: '/base', head: '/head' });
  assert.deepEqual(
    suite.runs.map(it => [it.name, it.status]),
    [
      ['base-1', 'ok'],
      ['head-1', 'ok'],
      ['base-3', 'ok'],
      ['head-3', 'ok'],
    ],
  );
  assert.equal(suite.failures, 0);
  // bench.sh idle is told where this suite's scripts are: a runner checks out the measured tree's test/benchmarks alone.
  assert.equal(
    suite.runs[0].idle.scripts,
    dirname(fileURLToPath(new URL('./bench-idle-suites.mjs', import.meta.url))),
  );
  const table = idleTable(suite);
  assert.equal(table.length, 6);
  assert.match(
    table[2],
    /^\| base-1 \| `base` \| 1 \| 70\.40 \| 4\.00 \| 18\.3 % \| 11\.7 % \| 6\.6 \|$/,
  );
  assert.match(table[3], /^\| head-1 \| `head` \| 1 \| 0\.30 \|/);
  const summary = renderMeasure(suite, SUITES['idle-mongo']);
  assert.equal(summary, renderIdle(suite, SUITES['idle-mongo']));
  assert.match(summary, /\*\*The store CPU here is a shared runner's\.\*\*/);
  assert.match(summary, /Runner: AMD EPYC 7763 64-Core Processor, 4 cores/);
  assert.match(
    summary,
    /70\.40\/s command aggregate transfer\.wow_log__transfer__partition/,
  );
  assert.deepEqual(measureAnomalies(suite), []);
});

test('without a baseline the head runs alone; a failed run is an anomaly and counted', () => {
  const alone = run(SUITES['idle-mongo'], { head: '/head' });
  assert.deepEqual(
    alone.runs.map(it => it.status),
    ['unavailable', 'ok', 'unavailable', 'ok'],
  );
  const failed = run(
    SUITES['idle-mongo'],
    { base: '/base', head: '/head' },
    fakeBench({ fail: '/head:3' }),
  );
  assert.equal(failed.failures, 1);
  assert.deepEqual(idleAnomalies(failed), [
    {
      suite: 'idle-mongo@100k',
      run: 'head-3',
      context: 'idle',
      kind: 'run failed',
      detail: 'bench.sh idle failed; see the job log',
    },
  ]);
  assert.match(
    renderIdle(failed, SUITES['idle-mongo']),
    /\*\*1 run\(s\) failed\*\*/,
  );
});

test("an arm whose node of three reads the log's counters more than its one node alone is an anomaly, within the slack it is not", () => {
  assert.deepEqual(NODE_READS_SLACK, { ratio: 1.1, perSecond: 0.25 });
  const installs = { base: '/base', head: '/head' };
  // 2.3 a node at one node; three nodes read three times as much, and the baseline's three split one's 70.5.
  assert.deepEqual(idleAnomalies(run(SUITES['idle-mongo'], installs)), []);
  // 8.1 at three is 2.70 a node, within a tenth and a quarter of a read a second (2.78); 12.9 is 4.30, past it.
  const within = run(
    SUITES['idle-mongo'],
    installs,
    fakeBench({ threeCounters: '8.1' }),
  );
  assert.deepEqual(idleAnomalies(within), []);
  const grown = run(
    SUITES['idle-mongo'],
    installs,
    fakeBench({ threeCounters: '12.9' }),
  );
  assert.deepEqual(idleAnomalies(grown), [
    {
      suite: 'idle-mongo@100k',
      run: 'head-3',
      context: 'idle',
      kind: 'a node of three reads more than one alone',
      detail:
        "4.30 log counter reads a second a node at three nodes, 2.30 at one: a node's reads grow with the nodes",
    },
  ]);
  assert.match(
    renderIdle(grown, SUITES['idle-mongo']),
    /a node of three reads more than one alone/,
  );
  // Without its one-node run (failed), a three-node run has nothing to be read against.
  const alone = run(
    SUITES['idle-mongo'],
    installs,
    fakeBench({ fail: '/head:1', threeCounters: '12.9' }),
  );
  assert.deepEqual(
    idleAnomalies(alone).map(it => it.kind),
    ['run failed'],
  );
});
