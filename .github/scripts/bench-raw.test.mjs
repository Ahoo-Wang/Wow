/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SUITES } from './bench.mjs';
import {
  RAW,
  rawAnomalies,
  rawArgs,
  rawTable,
  renderRaw,
} from './bench-raw.mjs';

const STEP = {
  shape: 'today',
  variant: 'fillfactor-100',
  connections: 16,
  seconds: 20,
  commands: 52000,
  flushes: 5200,
  failures: 0,
  commandsPerSecond: 2600,
  p50FlushMs: 3.2,
  p99FlushMs: 11.5,
  storeCpuMsPerCommand: 0.512,
  perCommand: {
    walBytes: 1288.4,
    walSyncs: 0.31,
    walSyncMs: 0.22,
    commits: 0.34,
  },
};

test('the raw suites are measure suites of the raw program on the head, bench.mjs unchanged', () => {
  for (const [name, backend] of [
    ['raw-pg', 'postgres'],
    ['raw-mongo', 'mongo'],
  ]) {
    const suite = SUITES[name];
    assert.equal(suite.kind, 'measure');
    assert.equal(suite.measure, 'raw');
    assert.equal(suite.group, 'raw');
    assert.deepEqual(
      suite.arms.map(arm => [arm.install, arm.backend]),
      [['head', backend]],
    );
  }
});

test("a raw run passes the program's settings, the head's install and the server's CPUs", () => {
  const args = rawArgs(
    { backend: 'postgres', install: 'head' },
    { head: '/install/head' },
    { server: '0-2' },
    '/out/head',
  );
  assert.deepEqual(args, [
    'measure',
    '--program=raw',
    '--backend=postgres',
    '--install=/install/head',
    `--connections=${RAW.connections}`,
    `--warm-up=${RAW.warmUp}`,
    `--seconds=${RAW.seconds}`,
    `--log-batch=${RAW.logBatch}`,
    `--snapshot-batch=${RAW.snapshotBatch}`,
    '--server-cpus=0-2',
    '--out=/out/head',
  ]);
});

test("a PostgreSQL step's row is what it did and each command's WAL, syncs and commits", () => {
  const [head, , row] = rawTable({
    raw: { backend: 'postgres', steps: [STEP] },
  });
  assert.match(head, /WAL bytes\/command/);
  assert.equal(
    row,
    '| today | fillfactor-100 | 16 | 2600 | 3.20 / 11.50 ms | 0.512 | 1288 | 0.310 | 0.220 | 0.340 | 0 |',
  );
});

test("a MongoDB step's row is the journal's and the operations', and an unread CPU says so", () => {
  const step = {
    ...STEP,
    variant: 'pipeline',
    storeCpuMsPerCommand: null,
    perCommand: {
      journalSyncs: 0.12,
      journalSyncMs: 0.07,
      inserts: 0.1,
      updates: 0.25,
    },
  };
  const [head, , row] = rawTable({ raw: { backend: 'mongo', steps: [step] } });
  assert.match(head, /Journal syncs\/command/);
  assert.match(
    row,
    /\| not recorded \| 0\.120 \| 0\.070 \| 0\.100 \| 0\.250 \| 0 \|$/,
  );
});

test('a failed run and failed flushes are anomalies, and the summary lists each run', () => {
  const suite = {
    name: 'raw-pg',
    runner: {
      cpu: 'AMD EPYC 7763',
      cores: 4,
      jdk: 'Eclipse Adoptium 25.0.5+8-LTS',
    },
    failures: 1,
    runs: [
      {
        name: 'head',
        status: 'ok',
        raw: { backend: 'postgres', steps: [{ ...STEP, failures: 3 }] },
      },
      { name: 'other', status: 'failed' },
    ],
  };
  const kinds = rawAnomalies(suite).map(row => row.kind);
  assert.deepEqual(kinds, ['failed flushes', 'run failed']);
  const summary = renderRaw(suite, SUITES['raw-pg']);
  assert.match(summary, /^## raw-pg@100k/);
  assert.match(summary, /Eclipse Adoptium 25\.0\.5\+8-LTS/);
  assert.match(summary, /### head/);
  assert.match(summary, /\*\*1 run\(s\) failed\*\*/);
});
