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
  calibrationText,
  rawAnomalies,
  rawArgs,
  rawTable,
  renderRaw,
  settingsText,
} from './bench-raw.mjs';

const CALIBRATED = {
  metric: 'walBytes',
  wowPerCommand: 1290,
  rawPerCommand: 1310,
  ratio: 1.0155,
  tolerance: 0.1,
  passed: true,
};

const STEP = {
  case: 'snapshot-4-async-ff100',
  unit: 'row',
  prices: 'P0: today',
  connections: 64,
  rate: null,
  seconds: 20,
  units: 52000,
  flushes: 13000,
  failures: 0,
  unitsPerSecond: 2600,
  p50FlushMs: 3.2,
  p99FlushMs: 11.5,
  storeCpuMsPerUnit: 0.0512,
  perUnit: { walBytes: 640.4, walSyncs: 0.01, walFpi: 0.02, commits: 0.25 },
  ratios: { hotShare: 0.75 },
};

const RUN = {
  name: 'head',
  status: 'ok',
  raw: {
    backend: 'postgres',
    calibration: CALIBRATED,
    logBatch: 10,
    snapshotBatch: 4,
    batches: [4, 10, 25],
    aggregates: 100000,
    store: { server_version: '17.6', synchronous_commit: 'on', fsync: 'on' },
    steps: [STEP],
  },
};

test('the raw suites are measure suites of the raw program on the head', () => {
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

test("a raw run passes the program's timing, the head's install and the server's CPUs", () => {
  assert.deepEqual(
    rawArgs(
      { backend: 'postgres', install: 'head' },
      { head: '/install/head' },
      { server: '0-2' },
      '/out/head',
    ),
    [
      'measure',
      '--program=raw',
      '--backend=postgres',
      '--install=/install/head',
      `--connections=${RAW.connections}`,
      `--warm-up=${RAW.warmUp}`,
      `--seconds=${RAW.seconds}`,
      '--server-cpus=0-2',
      '--out=/out/head',
    ],
  );
});

test('the calibration says whether the clone writes what Wow writes, and an uncalibrated run is read for nothing', () => {
  assert.match(
    calibrationText(RUN.raw),
    /^Calibrated: the clone's walBytes per command is 1310 against Wow's own 1290 \(1\.016, within ±10%\)\.$/,
  );
  assert.match(
    calibrationText({
      calibration: {
        ...CALIBRATED,
        rawPerCommand: 1500,
        ratio: 1.163,
        passed: false,
      },
    }),
    /^\*\*Uncalibrated: .*outside ±10%\)\. No case of this run is read\.\*\*$/,
  );
  assert.match(
    calibrationText({
      calibration: {
        metric: 'journalBytes',
        wowPerCommand: null,
        rawPerCommand: 900,
        ratio: null,
        tolerance: 0.1,
        passed: false,
      },
    }),
    /could not be read on both sides \(Wow none, clone 900\)/,
  );
  assert.match(calibrationText({}), /recorded no calibration/);
});

test('the store and the batch shape a ceiling is quoted with', () => {
  assert.equal(
    settingsText(RUN.raw),
    'Store: server_version 17.6, synchronous_commit on, fsync on. Batches: 10 commands a flush, their snapshots 4 a statement or call; cases at 4, 10, 25 a statement or call; 100000 aggregates.',
  );
});

test("a PostgreSQL step's row is per unit, its HOT share beside it, a paced step's rate named", () => {
  const [head, , row, paced] = rawTable({
    raw: {
      ...RUN.raw,
      steps: [STEP, { ...STEP, case: 'p2-combined', rate: 1200 }],
    },
  });
  assert.match(head, /WAL bytes\/unit .* HOT share/);
  assert.equal(
    row,
    '| snapshot-4-async-ff100 | P0: today | row | 64 | closed loop | 2600 | 3.20 / 11.50 ms | 51.2 | 640 | 0.010 | 0.020 | 0.250 | 75% | 0 |',
  );
  assert.match(paced, /^\| p2-combined \| .* \| 1200\/s \|/);
});

test("a MongoDB step's row is mongod's CPU, the write latency, the journal and the oplog per update", () => {
  const step = {
    ...STEP,
    case: 'm1-10-state',
    prices: 'B: M1',
    unit: 'update',
    storeCpuMsPerUnit: null,
    perUnit: { journalBytes: 512.2, oplogBytes: 300.6, journalSyncs: 0.004 },
    ratios: { snapshotWriteMicros: 41.25 },
  };
  const [head, , row] = rawTable({
    raw: { backend: 'mongo', steps: [step] },
  });
  assert.match(head, /mongod CPU µs\/unit \| Write latency µs/);
  assert.equal(
    row,
    '| m1-10-state | B: M1 | update | 64 | 2600 | 3.20 / 11.50 ms | not recorded | 41.3 | 512 | 301 | 0.004 | 0 |',
  );
});

test('a failed run, an uncalibrated one and failed flushes are anomalies; the summary leads each run with its calibration', () => {
  const suite = {
    name: 'raw-mongo',
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
        raw: {
          ...RUN.raw,
          backend: 'mongo',
          calibration: { ...CALIBRATED, passed: false, ratio: 1.3 },
          steps: [{ ...STEP, failures: 3 }],
        },
      },
      { name: 'other', status: 'failed' },
    ],
  };
  assert.deepEqual(
    rawAnomalies(suite).map(row => row.kind),
    ['uncalibrated', 'failed flushes', 'run failed'],
  );
  const summary = renderRaw(suite, SUITES['raw-mongo']);
  assert.match(summary, /^## raw-mongo@100k/);
  assert.match(summary, /Eclipse Adoptium 25\.0\.5\+8-LTS/);
  assert.match(summary, /### head\n\n\*\*Uncalibrated:/);
  assert.match(summary, /`c-transaction` is a multi-document transaction/);
  assert.match(summary, /\*\*1 run\(s\) failed\*\*/);
});
